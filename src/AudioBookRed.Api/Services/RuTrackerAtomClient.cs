using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed partial class RuTrackerAtomClient : IDisposable
{
    private readonly IConfiguration _configuration;
    private readonly HttpClient _http;
    private readonly ConcurrentDictionary<int, string> _etags = new();
    private readonly ConcurrentDictionary<int, DateTimeOffset> _lastModified = new();

    public RuTrackerAtomClient(IConfiguration configuration)
    {
        _configuration = configuration;
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.GZip |
                                     DecompressionMethods.Deflate |
                                     DecompressionMethods.Brotli
        };

        _http = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(45)
        };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("AudioBookRed/0.17.5 (+metadata-only)");
        _http.DefaultRequestHeaders.Accept.ParseAdd("application/atom+xml,application/xml;q=0.9,*/*;q=0.5");
    }

    public bool Enabled => _configuration.GetValue<bool?>("RuTrackerAtom:Enabled") ?? false;
    public int IntervalMinutes => Math.Clamp(
        _configuration.GetValue<int?>("RuTrackerAtom:IntervalMinutes") ?? 15,
        1,
        1440);
    public int MaxEntries => Math.Clamp(
        _configuration.GetValue<int?>("RuTrackerAtom:MaxEntries") ?? 50,
        1,
        100);

    public IReadOnlyList<int> ForumIds
    {
        get
        {
            var raw = _configuration["RuTrackerAtom:ForumIds"] ?? "2388";
            var ids = raw
                .Split([',', ';', ' ', '\t', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Select(value => int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var id) ? id : 0)
                .Where(id => id > 0)
                .Distinct()
                .ToArray();
            return ids.Length == 0 ? [2388] : ids;
        }
    }

    private string FeedBaseUrl =>
        (_configuration["RuTrackerAtom:BaseUrl"] ?? "https://feed.rutracker.cc").TrimEnd('/');

    public async Task<RuTrackerAtomFetchResult> FetchAsync(int forumId, int maxEntries, CancellationToken ct)
    {
        if (forumId <= 0)
            throw new ArgumentOutOfRangeException(nameof(forumId), "forumId должен быть положительным.");

        maxEntries = Math.Clamp(maxEntries, 1, 100);
        var uri = new Uri($"{FeedBaseUrl}/atom/f/{forumId}.atom", UriKind.Absolute);
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);

        if (_etags.TryGetValue(forumId, out var etag))
            request.Headers.TryAddWithoutValidation("If-None-Match", etag);
        if (_lastModified.TryGetValue(forumId, out var modified))
            request.Headers.IfModifiedSince = modified;

        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        if (response.StatusCode == HttpStatusCode.NotModified)
            return new RuTrackerAtomFetchResult(forumId, true, null, []);

        response.EnsureSuccessStatusCode();

        var xml = await response.Content.ReadAsStringAsync(ct);
        XDocument document;
        try
        {
            document = XDocument.Parse(xml, LoadOptions.None);
        }
        catch (Exception ex)
        {
            throw new InvalidDataException("RuTracker Atom вернул некорректный XML.", ex);
        }

        if (response.Headers.ETag is not null)
            _etags[forumId] = response.Headers.ETag.ToString();
        if (response.Content.Headers.LastModified is { } lastModified)
            _lastModified[forumId] = lastModified;

        XNamespace atom = "http://www.w3.org/2005/Atom";
        var feedUpdatedAt = ParseDate(document.Root?.Element(atom + "updated")?.Value);
        var entries = new List<RuTrackerAtomEntry>();

        foreach (var element in document.Root?.Elements(atom + "entry") ?? [])
        {
            if (entries.Count >= maxEntries)
                break;

            var idText = element.Element(atom + "id")?.Value ?? "";
            var link = element.Elements(atom + "link")
                .Select(node => node.Attribute("href")?.Value)
                .FirstOrDefault(value => !string.IsNullOrWhiteSpace(value)) ?? "";
            var topicId = ExtractTopicId(idText, link);
            if (topicId is null)
                continue;

            var rawTitle = WebUtility.HtmlDecode(element.Element(atom + "title")?.Value ?? "").Trim();
            if (string.IsNullOrWhiteSpace(rawTitle))
                continue;

            var (title, sizeBytes) = ExtractSize(rawTitle);
            var publisher = WebUtility.HtmlDecode(element.Element(atom + "author")?.Element(atom + "name")?.Value ?? "").Trim();
            var updatedAt = ParseDate(element.Element(atom + "updated")?.Value);
            var topicUrl = string.IsNullOrWhiteSpace(link)
                ? $"https://rutracker.org/forum/viewtopic.php?t={topicId.Value}"
                : link;

            entries.Add(new RuTrackerAtomEntry(
                topicId.Value,
                title,
                topicUrl,
                sizeBytes,
                updatedAt,
                string.IsNullOrWhiteSpace(publisher) ? null : publisher,
                forumId));
        }

        return new RuTrackerAtomFetchResult(forumId, false, feedUpdatedAt, entries);
    }

    private static long? ExtractTopicId(string id, string link)
    {
        var match = TopicId().Match(id + " " + link);
        return match.Success && long.TryParse(match.Groups[1].Value, out var topicId)
            ? topicId
            : null;
    }

    private static (string Title, long? SizeBytes) ExtractSize(string rawTitle)
    {
        var match = SizeSuffix().Match(rawTitle);
        if (!match.Success)
            return (rawTitle, null);

        if (!decimal.TryParse(
                match.Groups[1].Value.Replace(',', '.'),
                NumberStyles.Number,
                CultureInfo.InvariantCulture,
                out var value))
            return (rawTitle, null);

        var unit = match.Groups[2].Value.ToUpperInvariant();
        var multiplier = unit switch
        {
            "KB" or "КБ" => 1024m,
            "MB" or "МБ" => 1024m * 1024m,
            "GB" or "ГБ" => 1024m * 1024m * 1024m,
            "TB" or "ТБ" => 1024m * 1024m * 1024m * 1024m,
            _ => 1m
        };

        var bytes = decimal.ToInt64(decimal.Round(value * multiplier, 0, MidpointRounding.AwayFromZero));
        var title = rawTitle[..match.Index].Trim();
        return (title, bytes);
    }

    private static DateTimeOffset? ParseDate(string? value) =>
        DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var parsed)
            ? parsed
            : null;

    public void Dispose() => _http.Dispose();

    [GeneratedRegex(@"(?:/t/|[?&]t=)(\d+)", RegexOptions.IgnoreCase)]
    private static partial Regex TopicId();

    [GeneratedRegex(@"\s*\[(\d+(?:[.,]\d+)?)\s*(KB|MB|GB|TB|КБ|МБ|ГБ|ТБ)\]\s*$", RegexOptions.IgnoreCase)]
    private static partial Regex SizeSuffix();
}
