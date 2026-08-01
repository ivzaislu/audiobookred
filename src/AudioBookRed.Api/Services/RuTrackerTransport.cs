using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

/// <summary>
/// Общий транспорт RuTracker.
///
/// При заданном RuTrackerAlias:Url публичные GET-запросы к viewforum.php,
/// tracker.php и viewtopic.php направляются через закрытый Cloudflare Worker.
/// Авторизация RuTracker в этом режиме не выполняется.
///
/// Без alias сохраняется прежняя схема: общая CookieContainer-сессия,
/// POST login.php без редиректа и необязательный HTTP/SOCKS-прокси.
/// </summary>
public sealed class RuTrackerTransport : IDisposable
{
    private static readonly Encoding Cp1251;
    private readonly IConfiguration _configuration;
    private readonly CookieContainer _cookies = new();
    private readonly HttpClient _redirectClient;
    private readonly HttpClient _noRedirectClient;
    private readonly SemaphoreSlim _loginLock = new(1, 1);
    private readonly Uri _baseUri;
    private readonly Uri? _aliasBaseUri;
    private readonly string _aliasToken;
    private readonly string _routeDescription;
    private readonly bool _proxyConfigured;

    private DateTimeOffset _authenticatedUntil = DateTimeOffset.MinValue;
    private int? _lastHttpStatus;
    private string? _lastError;

    static RuTrackerTransport()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        Cp1251 = Encoding.GetEncoding(1251);
    }

    public RuTrackerTransport(IConfiguration configuration)
    {
        _configuration = configuration;

        var baseUrl = configuration["RuTracker:BaseUrl"] ?? "https://rutracker.org";
        _baseUri = new Uri(baseUrl.TrimEnd('/') + "/", UriKind.Absolute);

        _aliasBaseUri = ParseAliasUrl(configuration["RuTrackerAlias:Url"]);
        _aliasToken = configuration["RuTrackerAlias:Token"]?.Trim() ?? "";

        IWebProxy? proxy = null;
        if (_aliasBaseUri is not null)
        {
            _routeDescription = $"alias:{_aliasBaseUri.GetLeftPart(UriPartial.Authority)}";
            _proxyConfigured = false;
        }
        else
        {
            proxy = CreateProxy(configuration, out _routeDescription);
            _proxyConfigured = proxy is not null;
        }

        _redirectClient = CreateClient(CreateHandler(true, proxy));
        _noRedirectClient = CreateClient(CreateHandler(false, proxy));
    }

    private string Username => _configuration["RuTracker:Username"] ?? "";
    private string Password => _configuration["RuTracker:Password"] ?? "";
    private bool AliasConfigured => _aliasBaseUri is not null;
    private int DefaultForumId => _configuration.GetValue<int?>("RuTracker:DefaultForumId") ?? 2388;
    private int SessionHours => Math.Clamp(
        _configuration.GetValue<int?>("RuTrackerNetwork:SessionHours") ?? 168,
        1,
        24 * 30);
    private int TimeoutSeconds => Math.Clamp(
        _configuration.GetValue<int?>("RuTrackerNetwork:TimeoutSeconds") ?? 45,
        10,
        180);

    public Uri BaseUri => _baseUri;

    public RuTrackerNetworkStatus GetStatus() => new(
        !string.IsNullOrWhiteSpace(Username) && !string.IsNullOrWhiteSpace(Password),
        _proxyConfigured,
        AliasConfigured,
        _aliasBaseUri?.GetLeftPart(UriPartial.Authority),
        _routeDescription,
        !AliasConfigured && _authenticatedUntil > DateTimeOffset.UtcNow,
        !AliasConfigured && _authenticatedUntil > DateTimeOffset.UtcNow ? _authenticatedUntil : null,
        _lastHttpStatus,
        _lastError);

    public async Task<RuTrackerNetworkProbeResult> ProbeAsync(CancellationToken ct)
    {
        var probeUri = AliasConfigured
            ? new Uri(_baseUri, $"forum/viewforum.php?f={DefaultForumId}")
            : new Uri(_baseUri, "forum/index.php");

        int? anonymousStatus = null;
        string? anonymousTitle = null;
        var challenge = false;
        var publicRouteOk = false;
        string? publicRouteError = null;

        try
        {
            var probe = await ProbePageAsync(probeUri, new Uri(_baseUri, "forum/"), ct);
            anonymousStatus = probe.StatusCode;
            anonymousTitle = probe.PageTitle;
            challenge = probe.Challenge;
            publicRouteOk = probe.StatusCode is >= 200 and < 300 && !probe.Challenge;

            if (!publicRouteOk)
            {
                publicRouteError = AliasConfigured && probe.StatusCode == (int)HttpStatusCode.Unauthorized
                    ? "Cloudflare Worker отклонил RUTRACKER_ALIAS_TOKEN: HTTP 401."
                    : $"Публичный маршрут RuTracker вернул HTTP {probe.StatusCode}, страница: {probe.PageTitle}.";
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            anonymousTitle = ex.Message;
            publicRouteError = ex.Message;
        }

        if (AliasConfigured)
        {
            _lastError = publicRouteOk ? null : publicRouteError;
            return new RuTrackerNetworkProbeResult(
                _proxyConfigured,
                true,
                _aliasBaseUri?.GetLeftPart(UriPartial.Authority),
                _routeDescription,
                anonymousStatus,
                anonymousTitle,
                challenge,
                publicRouteOk,
                false,
                false,
                null,
                null,
                publicRouteError);
        }

        try
        {
            await EnsureAuthenticatedAsync(true, ct);
            return new RuTrackerNetworkProbeResult(
                _proxyConfigured,
                false,
                null,
                _routeDescription,
                anonymousStatus,
                anonymousTitle,
                challenge,
                publicRouteOk,
                true,
                true,
                _lastHttpStatus,
                _authenticatedUntil,
                null);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return new RuTrackerNetworkProbeResult(
                _proxyConfigured,
                false,
                null,
                _routeDescription,
                anonymousStatus,
                anonymousTitle,
                challenge,
                publicRouteOk,
                true,
                false,
                _lastHttpStatus,
                null,
                ex.Message);
        }
    }

    public async Task<string> GetAuthenticatedHtmlAsync(
        Uri uri,
        Uri? referer,
        CancellationToken ct)
    {
        ValidateTarget(uri);

        if (AliasConfigured)
        {
            var aliasResult = await SendHtmlAsync(uri, referer, ct);
            return aliasResult.Html;
        }

        await EnsureAuthenticatedAsync(false, ct);

        var result = await SendHtmlAsync(uri, referer, ct);
        if (result.NeedsAuthentication)
        {
            _authenticatedUntil = DateTimeOffset.MinValue;
            await EnsureAuthenticatedAsync(true, ct);
            result = await SendHtmlAsync(uri, referer, ct);
        }

        if (result.NeedsAuthentication)
            throw new RuTrackerAuthenticationException(
                "RuTracker вернул неавторизованную страницу после повторного входа.");

        return result.Html;
    }

    public async Task EnsureAuthenticatedAsync(bool force, CancellationToken ct)
    {
        if (AliasConfigured)
            return;

        if (!force && _authenticatedUntil > DateTimeOffset.UtcNow)
            return;

        if (string.IsNullOrWhiteSpace(Username) || string.IsNullOrWhiteSpace(Password))
            throw new RuTrackerAuthenticationException(
                "Не заданы RUTRACKER_USERNAME и RUTRACKER_PASSWORD в .env.");

        await _loginLock.WaitAsync(ct);
        try
        {
            if (!force && _authenticatedUntil > DateTimeOffset.UtcNow)
                return;

            using var request = CreateDirectRequest(
                HttpMethod.Post,
                new Uri(_baseUri, "forum/login.php"),
                new Uri(_baseUri, "forum/index.php"));
            request.Content = CreateForm([
                new("login_username", Username),
                new("login_password", Password),
                new("login", "Login")
            ]);

            using var response = await _noRedirectClient.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                ct);
            var html = await ReadHtmlAsync(response, ct);
            _lastHttpStatus = (int)response.StatusCode;

            if (IsChallenge(response.StatusCode, html))
                throw BuildChallengeException(response, html, "вход");

            var accepted = response.StatusCode == HttpStatusCode.Found ||
                           HasLoggedInMarker(html);
            if (!accepted)
            {
                throw new RuTrackerAuthenticationException(
                    $"RuTracker не подтвердил вход: HTTP {(int)response.StatusCode}, " +
                    $"страница: {ExtractPageTitle(html)}.");
            }

            using var verifyRequest = CreateDirectRequest(
                HttpMethod.Get,
                new Uri(_baseUri, "forum/index.php"),
                new Uri(_baseUri, "forum/"));
            using var verifyResponse = await _redirectClient.SendAsync(
                verifyRequest,
                HttpCompletionOption.ResponseHeadersRead,
                ct);
            var verifyHtml = await ReadHtmlAsync(verifyResponse, ct);
            _lastHttpStatus = (int)verifyResponse.StatusCode;

            if (IsChallenge(verifyResponse.StatusCode, verifyHtml))
                throw BuildChallengeException(verifyResponse, verifyHtml, "проверка сессии");
            if (!verifyResponse.IsSuccessStatusCode || !HasLoggedInMarker(verifyHtml))
                throw new RuTrackerAuthenticationException(
                    $"Cookies RuTracker не подтвердились: HTTP {(int)verifyResponse.StatusCode}, " +
                    $"страница: {ExtractPageTitle(verifyHtml)}.");

            _authenticatedUntil = DateTimeOffset.UtcNow.AddHours(SessionHours);
            _lastError = null;
        }
        catch (Exception ex)
        {
            _authenticatedUntil = DateTimeOffset.MinValue;
            _lastError = ex.Message;
            throw;
        }
        finally
        {
            _loginLock.Release();
        }
    }

    private async Task<HtmlResult> SendHtmlAsync(
        Uri canonicalUri,
        Uri? referer,
        CancellationToken ct)
    {
        using var request = CreatePageRequest(canonicalUri, referer);
        using var response = await _redirectClient.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            ct);
        var html = await ReadHtmlAsync(response, ct);
        _lastHttpStatus = (int)response.StatusCode;

        if (AliasConfigured && response.StatusCode == HttpStatusCode.Unauthorized)
        {
            var message = "Cloudflare Worker вернул HTTP 401. Проверьте RUTRACKER_ALIAS_TOKEN.";
            _lastError = message;
            throw new HttpRequestException(message, null, response.StatusCode);
        }

        if (AliasConfigured && response.StatusCode == HttpStatusCode.Forbidden &&
            html.Contains("Path not allowed", StringComparison.OrdinalIgnoreCase))
        {
            var message = $"Cloudflare Worker запретил путь {canonicalUri.AbsolutePath}.";
            _lastError = message;
            throw new HttpRequestException(message, null, response.StatusCode);
        }

        if (IsChallenge(response.StatusCode, html))
        {
            var operation = AliasConfigured
                ? "публичную страницу через Worker"
                : "страницу RuTracker";
            var exception = BuildChallengeException(response, html, operation);
            _lastError = exception.Message;
            throw exception;
        }

        if (!response.IsSuccessStatusCode)
        {
            var route = AliasConfigured ? "Worker/RuTracker" : "RuTracker";
            var message = $"{route} вернул HTTP {(int)response.StatusCode} для {canonicalUri.AbsolutePath}.";
            _lastError = message;
            throw new HttpRequestException(message, null, response.StatusCode);
        }

        _lastError = null;
        var needsAuth = !AliasConfigured && (IsLoginPage(html) || !HasLoggedInMarker(html));
        return new HtmlResult(html, needsAuth);
    }

    private async Task<PageProbe> ProbePageAsync(
        Uri canonicalUri,
        Uri? referer,
        CancellationToken ct)
    {
        using var request = CreatePageRequest(canonicalUri, referer);
        using var response = await _redirectClient.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            ct);
        var html = await ReadHtmlAsync(response, ct);
        _lastHttpStatus = (int)response.StatusCode;
        return new PageProbe(
            (int)response.StatusCode,
            ExtractPageTitle(html),
            IsChallenge(response.StatusCode, html));
    }

    private HttpRequestMessage CreatePageRequest(Uri canonicalUri, Uri? referer)
    {
        ValidateTarget(canonicalUri);

        if (!AliasConfigured)
            return CreateDirectRequest(HttpMethod.Get, canonicalUri, referer);

        if (string.IsNullOrWhiteSpace(_aliasToken))
            throw new InvalidOperationException(
                "Задан RUTRACKER_ALIAS_URL, но отсутствует RUTRACKER_ALIAS_TOKEN.");

        var request = new HttpRequestMessage(HttpMethod.Get, BuildAliasUri(canonicalUri));
        request.Headers.TryAddWithoutValidation("X-Proxy-Token", _aliasToken);
        request.Headers.TryAddWithoutValidation("Cache-Control", "no-cache");
        request.Headers.TryAddWithoutValidation("Pragma", "no-cache");
        return request;
    }

    private static HttpRequestMessage CreateDirectRequest(HttpMethod method, Uri uri, Uri? referer)
    {
        var request = new HttpRequestMessage(method, uri);
        request.Headers.Referrer = referer;
        request.Headers.TryAddWithoutValidation("Cache-Control", "no-cache");
        request.Headers.TryAddWithoutValidation("Pragma", "no-cache");
        return request;
    }

    private Uri BuildAliasUri(Uri canonicalUri)
    {
        var alias = _aliasBaseUri ?? throw new InvalidOperationException("Alias не настроен.");
        var builder = new UriBuilder(alias)
        {
            Path = canonicalUri.AbsolutePath,
            Query = canonicalUri.Query.TrimStart('?'),
            Fragment = ""
        };
        return builder.Uri;
    }

    private HttpClient CreateClient(HttpClientHandler handler)
    {
        var client = new HttpClient(handler)
        {
            BaseAddress = _baseUri,
            Timeout = TimeSpan.FromSeconds(TimeoutSeconds)
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Safari/537.36");
        client.DefaultRequestHeaders.Accept.ParseAdd(
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        client.DefaultRequestHeaders.AcceptLanguage.ParseAdd("ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        return client;
    }

    private HttpClientHandler CreateHandler(bool allowRedirect, IWebProxy? proxy) => new()
    {
        AllowAutoRedirect = allowRedirect,
        AutomaticDecompression = DecompressionMethods.GZip |
                                 DecompressionMethods.Deflate |
                                 DecompressionMethods.Brotli,
        CookieContainer = _cookies,
        UseCookies = true,
        Proxy = proxy,
        UseProxy = proxy is not null,
        CheckCertificateRevocationList = false
    };

    private static Uri? ParseAliasUrl(string? raw)
    {
        raw = raw?.Trim();
        if (string.IsNullOrWhiteSpace(raw))
            return null;

        if (!Uri.TryCreate(raw, UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps ||
            string.IsNullOrWhiteSpace(uri.Host))
            throw new InvalidOperationException(
                "RUTRACKER_ALIAS_URL должен быть абсолютным HTTPS-адресом Cloudflare Worker.");

        if (!string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment))
            throw new InvalidOperationException(
                "RUTRACKER_ALIAS_URL не должен содержать query или fragment.");

        if (uri.AbsolutePath is not ("" or "/"))
            throw new InvalidOperationException(
                "RUTRACKER_ALIAS_URL должен указывать на корень Worker без дополнительного пути.");

        return new Uri(uri.GetLeftPart(UriPartial.Authority) + "/", UriKind.Absolute);
    }

    private static IWebProxy? CreateProxy(IConfiguration configuration, out string description)
    {
        var raw = configuration["RuTrackerProxy:Url"]?.Trim();
        if (string.IsNullOrWhiteSpace(raw))
        {
            description = "direct";
            return null;
        }

        if (!Uri.TryCreate(raw, UriKind.Absolute, out var uri) ||
            uri.Scheme is not ("http" or "https" or "socks4" or "socks4a" or "socks5"))
            throw new InvalidOperationException(
                "RUTRACKER_PROXY_URL должен быть http://, https://, socks4://, socks4a:// или socks5:// адресом.");

        var username = configuration["RuTrackerProxy:Username"];
        var password = configuration["RuTrackerProxy:Password"];

        if (string.IsNullOrWhiteSpace(username) && !string.IsNullOrWhiteSpace(uri.UserInfo))
        {
            var parts = uri.UserInfo.Split(':', 2);
            username = Uri.UnescapeDataString(parts[0]);
            password = parts.Length > 1 ? Uri.UnescapeDataString(parts[1]) : "";
            var clean = new UriBuilder(uri) { UserName = "", Password = "" };
            uri = clean.Uri;
        }

        var proxy = new WebProxy(uri);
        if (!string.IsNullOrWhiteSpace(username))
            proxy.Credentials = new NetworkCredential(username, password ?? "");

        description = $"{uri.Scheme}://{uri.Host}:{uri.Port}";
        return proxy;
    }

    private static ByteArrayContent CreateForm(IEnumerable<KeyValuePair<string, string>> fields)
    {
        var body = string.Join("&", fields.Select(x =>
            $"{PercentEncode(x.Key, Encoding.ASCII)}={PercentEncode(x.Value, Cp1251)}"));
        var content = new ByteArrayContent(Encoding.ASCII.GetBytes(body));
        content.Headers.ContentType = new MediaTypeHeaderValue("application/x-www-form-urlencoded");
        return content;
    }

    private static string PercentEncode(string value, Encoding encoding)
    {
        var bytes = encoding.GetBytes(value);
        var sb = new StringBuilder(bytes.Length * 3);
        foreach (var b in bytes)
        {
            if ((b >= (byte)'a' && b <= (byte)'z') ||
                (b >= (byte)'A' && b <= (byte)'Z') ||
                (b >= (byte)'0' && b <= (byte)'9') ||
                b is (byte)'-' or (byte)'_' or (byte)'.' or (byte)'~')
                sb.Append((char)b);
            else if (b == (byte)' ')
                sb.Append('+');
            else
                sb.Append('%').Append(b.ToString("X2", CultureInfo.InvariantCulture));
        }
        return sb.ToString();
    }

    private static async Task<string> ReadHtmlAsync(HttpResponseMessage response, CancellationToken ct)
    {
        var bytes = await response.Content.ReadAsByteArrayAsync(ct);
        var charset = response.Content.Headers.ContentType?.CharSet?.Trim('"', '\'');
        if (!string.IsNullOrWhiteSpace(charset))
        {
            try
            {
                return Encoding.GetEncoding(charset).GetString(bytes);
            }
            catch (ArgumentException)
            {
                // fallback ниже
            }
        }
        return Cp1251.GetString(bytes);
    }

    private static bool HasLoggedInMarker(string html) =>
        html.Contains("id=\"logged-in-username\"", StringComparison.OrdinalIgnoreCase) ||
        html.Contains("id='logged-in-username'", StringComparison.OrdinalIgnoreCase) ||
        html.Contains("logged-in-as-uname", StringComparison.OrdinalIgnoreCase);

    private static bool IsLoginPage(string html) =>
        html.Contains("login-form-full", StringComparison.OrdinalIgnoreCase) ||
        html.Contains("name=\"login_username\"", StringComparison.OrdinalIgnoreCase);

    private static bool IsChallenge(HttpStatusCode status, string html) =>
        status == HttpStatusCode.Forbidden ||
        html.Contains("Just a moment", StringComparison.OrdinalIgnoreCase) ||
        html.Contains("cf-chl-", StringComparison.OrdinalIgnoreCase);

    private static RuTrackerAuthenticationException BuildChallengeException(
        HttpResponseMessage response,
        string html,
        string operation)
    {
        var ray = response.Headers.TryGetValues("CF-Ray", out var values)
            ? values.FirstOrDefault()
            : null;
        return new RuTrackerAuthenticationException(
            $"RuTracker/Cloudflare отклонил {operation}: HTTP {(int)response.StatusCode}, " +
            $"страница: {ExtractPageTitle(html)}, CF-Ray: {ray ?? "нет"}.");
    }

    private static string ExtractPageTitle(string html)
    {
        var start = html.IndexOf("<title", StringComparison.OrdinalIgnoreCase);
        if (start < 0) return "без title";
        start = html.IndexOf('>', start);
        if (start < 0) return "без title";
        var end = html.IndexOf("</title>", start, StringComparison.OrdinalIgnoreCase);
        if (end < 0) return "без title";
        var title = WebUtility.HtmlDecode(html[(start + 1)..end]).Trim();
        return title.Length > 120 ? title[..120] : title;
    }

    private void ValidateTarget(Uri uri)
    {
        if (!uri.IsAbsoluteUri ||
            uri.Scheme != Uri.UriSchemeHttps ||
            !(uri.Host.Equals(_baseUri.Host, StringComparison.OrdinalIgnoreCase) ||
              uri.Host.EndsWith("." + _baseUri.Host, StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("Некорректный адрес RuTracker.", nameof(uri));
    }

    public void Dispose()
    {
        _redirectClient.Dispose();
        _noRedirectClient.Dispose();
        _loginLock.Dispose();
    }

    private sealed record HtmlResult(string Html, bool NeedsAuthentication);
    private sealed record PageProbe(int StatusCode, string PageTitle, bool Challenge);
}
