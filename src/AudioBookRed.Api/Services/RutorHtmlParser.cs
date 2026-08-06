using System.Globalization;
using System.Net;
using System.Text.RegularExpressions;
using AngleSharp.Dom;
using AngleSharp.Html.Parser;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed partial class RutorHtmlParser
{
    public const int CurrentParserVersion = 2;

    public async Task<RutorListingPage> ParseListingAsync(
        string html,
        Uri pageUri,
        int categoryId,
        int page,
        CancellationToken ct)
    {
        if (categoryId != RutorSourceDefinition.BooksCategoryId)
            throw new ArgumentOutOfRangeException(nameof(categoryId));
        if (page < 1)
            throw new ArgumentOutOfRangeException(nameof(page));

        var parser = new HtmlParser();
        var document = await parser.ParseDocumentAsync(html, ct);
        var rows = document.QuerySelectorAll("#index tr.gai, #index tr.tum");
        if (rows.Length == 0)
        {
            throw new InvalidDataException(
                $"Rutor page {page} не содержит таблицу #index с torrent-строками.");
        }

        var items = new List<RutorListingItem>();
        var seen = new HashSet<long>();

        foreach (var row in rows)
        {
            var detailLink = row.QuerySelector("a[href^='/torrent/']");
            if (detailLink is null)
                continue;

            var topicId = ParseTopicId(detailLink.GetAttribute("href"));
            if (topicId <= 0 || !seen.Add(topicId))
                continue;

            var title = NormalizeAudioTokens(CleanText(detailLink.TextContent));
            if (!IsAudiobookTitle(title))
                continue;

            var magnet = WebUtility.HtmlDecode(
                row.QuerySelector("a[href^='magnet:?']")?.GetAttribute("href") ?? string.Empty);
            var infoHash = ParseInfoHash(magnet);
            if (string.IsNullOrWhiteSpace(magnet) || string.IsNullOrWhiteSpace(infoHash))
                continue;

            var cells = row.Children.Where(element => element.LocalName == "td").ToArray();
            var size = cells.Length >= 2 ? ParseSizeBytes(cells[^2].TextContent) : 0;
            var peers = cells.Length >= 1 ? cells[^1] : null;
            var seeders = ParseInt(peers?.QuerySelector("span.green")?.TextContent);
            var leechers = ParseInt(peers?.QuerySelector("span.red")?.TextContent);
            var href = detailLink.GetAttribute("href") ?? $"/torrent/{topicId}";
            var topicUrl = new Uri(pageUri, href).ToString();

            items.Add(new RutorListingItem(
                topicId,
                title,
                "Аудиокниги",
                topicUrl,
                size,
                seeders,
                leechers,
                infoHash,
                magnet));
        }

        var visiblePages = document.QuerySelectorAll("a[href*='/browse/'][href*='/11/0/0']")
            .Select(link => ParseBrowsePage(link.GetAttribute("href")))
            .Where(value => value >= 0)
            .ToArray();
        var totalPages = Math.Max(
            page,
            visiblePages.Length == 0 ? page : visiblePages.Max() + 1);

        return new RutorListingPage(
            categoryId,
            page,
            totalPages,
            page < totalPages,
            rows.Length,
            items);
    }

    public static string NormalizeAudioTokens(string value) =>
        string.IsNullOrWhiteSpace(value)
            ? value
            : CyrillicMp3().Replace(value, "MP3");

    public static bool IsAudiobookTitle(string title)
    {
        if (string.IsNullOrWhiteSpace(title))
            return false;

        var normalized = NormalizeAudioTokens(title);
        return AudioFormat().IsMatch(normalized)
            || normalized.Contains("аудиокниг", StringComparison.OrdinalIgnoreCase)
            || normalized.Contains("радиоспектак", StringComparison.OrdinalIgnoreCase)
            || normalized.Contains("аудиоспектак", StringComparison.OrdinalIgnoreCase)
            || normalized.Contains("аудиопостанов", StringComparison.OrdinalIgnoreCase);
    }

    private static long ParseTopicId(string? href)
    {
        var match = TorrentId().Match(href ?? string.Empty);
        return match.Success && long.TryParse(
            match.Groups[1].Value,
            NumberStyles.Integer,
            CultureInfo.InvariantCulture,
            out var parsed)
            ? parsed
            : 0;
    }

    private static int ParseBrowsePage(string? href)
    {
        var match = BrowsePage().Match(href ?? string.Empty);
        return match.Success && int.TryParse(
            match.Groups[1].Value,
            NumberStyles.Integer,
            CultureInfo.InvariantCulture,
            out var parsed)
            ? parsed
            : -1;
    }

    private static string ParseInfoHash(string magnet)
    {
        var match = InfoHash().Match(magnet);
        return match.Success ? match.Groups[1].Value.ToLowerInvariant() : string.Empty;
    }

    private static long ParseSizeBytes(string? text)
    {
        var match = Size().Match(CleanText(text ?? string.Empty));
        if (!match.Success || !decimal.TryParse(
                match.Groups["value"].Value.Replace(',', '.'),
                NumberStyles.Number,
                CultureInfo.InvariantCulture,
                out var value))
            return 0;

        var multiplier = match.Groups["unit"].Value.ToUpperInvariant() switch
        {
            "KB" or "KIB" or "КБ" => 1024m,
            "MB" or "MIB" or "МБ" => 1024m * 1024m,
            "GB" or "GIB" or "ГБ" => 1024m * 1024m * 1024m,
            "TB" or "TIB" or "ТБ" => 1024m * 1024m * 1024m * 1024m,
            _ => 1m
        };

        var bytes = decimal.Round(value * multiplier, 0, MidpointRounding.AwayFromZero);
        return bytes > long.MaxValue ? long.MaxValue : (long)bytes;
    }

    private static int ParseInt(string? value)
    {
        var cleaned = CleanText(value ?? string.Empty)
            .Replace(",", string.Empty, StringComparison.Ordinal)
            .Replace(" ", string.Empty, StringComparison.Ordinal);
        return int.TryParse(cleaned, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : 0;
    }

    private static string CleanText(string value) =>
        string.Join(' ', WebUtility.HtmlDecode(value)
            .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));

    [GeneratedRegex(@"/torrent/(\d+)(?:/|$)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex TorrentId();

    [GeneratedRegex(@"/browse/(\d+)/11/0/0", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex BrowsePage();

    [GeneratedRegex(@"(?:[?&]xt=urn:btih:)([a-f0-9]{40})(?:&|$)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex InfoHash();

    [GeneratedRegex(@"(?<![\p{L}\p{N}])(?:MP3|МР3|M4B|M4A|OPUS|OGG|AAC|WMA|FLAC|APE)(?![\p{L}\p{N}])", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex AudioFormat();

    [GeneratedRegex(@"(?<![\p{L}\p{N}])[MМ][PР]3(?![\p{L}\p{N}])", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex CyrillicMp3();

    [GeneratedRegex(@"(?<value>\d+(?:[.,]\d+)?)\s*(?<unit>B|KB|KIB|MB|MIB|GB|GIB|TB|TIB|Б|КБ|МБ|ГБ|ТБ)\b", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex Size();
}
