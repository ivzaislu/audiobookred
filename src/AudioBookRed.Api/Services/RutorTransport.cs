using System.Net;
using System.Net.Http.Headers;
using System.Text;
using AudioBookRed.Api.Infrastructure;

namespace AudioBookRed.Api.Services;

public sealed record RutorHtmlResponse(Uri Uri, string Html);

public sealed class RutorTransport : IDisposable
{
    private static readonly Encoding Cp1251;
    private readonly HttpClient _client;
    private readonly IReadOnlyList<Uri> _baseUris;
    private int _preferredIndex;

    static RutorTransport()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        Cp1251 = Encoding.GetEncoding(1251);
    }

    public RutorTransport(IConfiguration configuration)
    {
        _baseUris = ParseBaseUris(configuration["Rutor:BaseUrls"]);
        var timeoutSeconds = Math.Clamp(
            configuration.GetValue<int?>("Rutor:TimeoutSeconds") ?? 45,
            10,
            180);

        var handler = new SocketsHttpHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            AllowAutoRedirect = true,
            ConnectTimeout = TimeSpan.FromSeconds(Math.Min(timeoutSeconds, 30)),
            PooledConnectionLifetime = TimeSpan.FromMinutes(10)
        };

        _client = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(timeoutSeconds)
        };
        _client.DefaultRequestHeaders.UserAgent.ParseAdd(
            $"AudioBookRed/{ApplicationVersion.Value} RutorCrawler (+https://github.com/ivzaislu/audiobookred)");
        _client.DefaultRequestHeaders.Accept.Add(
            new MediaTypeWithQualityHeaderValue("text/html"));
        _client.DefaultRequestHeaders.AcceptLanguage.ParseAdd("ru-RU,ru;q=0.9,en;q=0.6");
    }

    public IReadOnlyList<Uri> BaseUris => _baseUris;

    public async Task<RutorHtmlResponse> GetHtmlAsync(
        string relativePath,
        CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(relativePath))
            throw new ArgumentException("Путь Rutor не задан.", nameof(relativePath));

        var errors = new List<string>();
        var start = Math.Clamp(Volatile.Read(ref _preferredIndex), 0, _baseUris.Count - 1);

        for (var offset = 0; offset < _baseUris.Count; offset++)
        {
            var index = (start + offset) % _baseUris.Count;
            var baseUri = _baseUris[index];
            var uri = new Uri(baseUri, relativePath.TrimStart('/'));

            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, uri);
                request.Headers.Referrer = baseUri;
                using var response = await _client.SendAsync(
                    request,
                    HttpCompletionOption.ResponseHeadersRead,
                    ct);
                var bytes = await response.Content.ReadAsByteArrayAsync(ct);
                var html = Decode(bytes, response.Content.Headers.ContentType?.CharSet);

                if (!response.IsSuccessStatusCode)
                {
                    errors.Add($"{baseUri.Host}: HTTP {(int)response.StatusCode}");
                    continue;
                }

                if (html.Contains("Just a moment", StringComparison.OrdinalIgnoreCase)
                    || html.Contains("captcha", StringComparison.OrdinalIgnoreCase))
                {
                    errors.Add($"{baseUri.Host}: challenge page");
                    continue;
                }

                Volatile.Write(ref _preferredIndex, index);
                return new RutorHtmlResponse(response.RequestMessage?.RequestUri ?? uri, html);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                errors.Add($"{baseUri.Host}: {ex.Message}");
            }
        }

        throw new HttpRequestException(
            "Все зеркала Rutor недоступны: " + string.Join("; ", errors));
    }

    private static IReadOnlyList<Uri> ParseBaseUris(string? configured)
    {
        string[] values = string.IsNullOrWhiteSpace(configured)
            ? ["https://rutor.info", "https://rutor.is", "https://tracker.rutor.is"]
            : configured.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

        var result = new List<Uri>();
        foreach (var value in values)
        {
            if (!Uri.TryCreate(value.TrimEnd('/') + "/", UriKind.Absolute, out var uri)
                || uri.Scheme is not ("http" or "https"))
            {
                throw new InvalidOperationException($"Некорректный Rutor:BaseUrls: '{value}'.");
            }

            if (result.All(existing => existing.Host != uri.Host))
                result.Add(uri);
        }

        if (result.Count == 0)
            throw new InvalidOperationException("Rutor:BaseUrls не содержит допустимых зеркал.");

        return result;
    }

    private static string Decode(byte[] bytes, string? charset)
    {
        if (!string.IsNullOrWhiteSpace(charset))
        {
            try
            {
                return Encoding.GetEncoding(charset.Trim('"')).GetString(bytes);
            }
            catch (ArgumentException)
            {
                // Fall through to strict UTF-8 and CP1251.
            }
        }

        try
        {
            return new UTF8Encoding(false, true).GetString(bytes);
        }
        catch (DecoderFallbackException)
        {
            return Cp1251.GetString(bytes);
        }
    }

    public void Dispose() => _client.Dispose();
}
