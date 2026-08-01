using System.Globalization;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using AngleSharp.Dom;
using AngleSharp.Html.Parser;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerHtmlParser
{
    private static readonly Encoding Cp1251;
    private static readonly Regex TopicIdRegex = new(@"(?:[?&]t=)(\d+)", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex SizeRegex = new(
        @"(?<value>\d+(?:[.,]\d+)?)\s*(?<unit>B|KB|KIB|MB|MIB|GB|GIB|TB|TIB|\u0411|\u041a\u0411|\u041c\u0411|\u0413\u0411|\u0422\u0411)\b",
        RegexOptions.Compiled | RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private readonly IConfiguration _configuration;

    static RuTrackerHtmlParser()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        Cp1251 = Encoding.GetEncoding(1251);
    }

    public RuTrackerHtmlParser(IConfiguration configuration)
    {
        _configuration = configuration;
    }

    private string BaseUrl => (_configuration["RuTracker:BaseUrl"] ?? "https://rutracker.org").TrimEnd('/');

    public async Task<IReadOnlyList<RuTrackerSearchItem>> ParseAsync(
        Stream body,
        string? contentType,
        int maxResults,
        CancellationToken ct)
    {
        using var ms = new MemoryStream();
        await body.CopyToAsync(ms, ct);
        var bytes = ms.ToArray();
        if (bytes.Length == 0)
            throw new ArgumentException("HTML-файл пуст.");

        var html = Decode(bytes, contentType);
        if (html.Contains("Just a moment", StringComparison.OrdinalIgnoreCase) ||
            html.Contains("cf-mitigated", StringComparison.OrdinalIgnoreCase) ||
            html.Contains("/cdn-cgi/challenge-platform/", StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("Загружена страница Cloudflare Challenge, а не страница RuTracker.");
        }

        var parser = new HtmlParser();
        var document = await parser.ParseDocumentAsync(html, ct);
        var pageCategory = CleanText(
            document.QuerySelector("h1.maintitle a, h1.maintitle")?.TextContent ?? string.Empty);

        // Поддерживаются обе разметки RuTracker:
        // 1) tracker.php: #tor-tbl tbody tr
        // 2) viewforum.php: table.vf-table tr[data-topic_id]
        var rows = document.QuerySelectorAll(
            "#tor-tbl tbody tr, table.vf-table tr[data-topic_id], tr.hl-tr[data-topic_id]");

        var limit = Math.Clamp(maxResults, 1, 200);
        var items = new List<RuTrackerSearchItem>(Math.Min(rows.Length, limit));
        var seenTopicIds = new HashSet<long>();

        foreach (var row in rows)
        {
            if (items.Count >= limit)
                break;

            var link = row.QuerySelector(
                "a.tLink, a.med.tLink, a[data-topic_id], a.tt-text, a[id^='tt-']");
            if (link is null)
                continue;

            var topicId = ParseTopicId(row, link);
            if (topicId <= 0 || !seenTopicIds.Add(topicId))
                continue;

            var title = CleanText(link.TextContent);
            if (string.IsNullOrWhiteSpace(title))
                continue;

            var category = CleanText(
                row.QuerySelector(".f-name a, td.f-name-col a")?.TextContent ?? pageCategory);
            var size = ParseSizeBytes(row);
            var seeds = ParseInt(row.QuerySelector("td.seedmed b, b.seedmed, span.seedmed b")?.TextContent);
            var leeches = ParseInt(row.QuerySelector("td.leechmed b, b.leechmed, span.leechmed b")?.TextContent);
            var topicUrl = $"{BaseUrl}/forum/viewtopic.php?t={topicId}";

            items.Add(new RuTrackerSearchItem(
                topicId,
                title,
                category,
                topicUrl,
                size,
                seeds,
                leeches));
        }

        if (items.Count == 0)
        {
            throw new ArgumentException(
                "В HTML не найдены темы RuTracker. Ожидалась таблица tracker.php (#tor-tbl) " +
                "или страница раздела viewforum.php (.vf-table). Сохраните страницу целиком как HTML.");
        }

        return items;
    }

    private static string Decode(byte[] bytes, string? contentType)
    {
        if (!string.IsNullOrWhiteSpace(contentType) &&
            contentType.Contains("windows-1251", StringComparison.OrdinalIgnoreCase))
            return Cp1251.GetString(bytes);

        try
        {
            return new UTF8Encoding(false, true).GetString(bytes);
        }
        catch (DecoderFallbackException)
        {
            return Cp1251.GetString(bytes);
        }
    }

    private static long ParseTopicId(IElement row, IElement link)
    {
        var rowId = row.GetAttribute("data-topic_id");
        if (long.TryParse(rowId, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed))
            return parsed;

        var linkDataId = link.GetAttribute("data-topic_id");
        if (long.TryParse(linkDataId, NumberStyles.Integer, CultureInfo.InvariantCulture, out parsed))
            return parsed;

        var href = link.GetAttribute("href") ?? string.Empty;
        var match = TopicIdRegex.Match(href);
        return match.Success &&
               long.TryParse(match.Groups[1].Value, NumberStyles.Integer, CultureInfo.InvariantCulture, out parsed)
            ? parsed
            : 0;
    }

    private static long ParseSizeBytes(IElement row)
    {
        var exactElement = row.QuerySelector("td.tor-size[data-ts_text]");
        var exactValue = exactElement?.GetAttribute("data-ts_text");
        if (long.TryParse(exactValue, NumberStyles.Integer, CultureInfo.InvariantCulture, out var exact))
            return exact;

        var text = CleanText(
            row.QuerySelector("a.f-dl.dl-stub, td.tor-size, td[data-ts_text]")?.TextContent ?? string.Empty);
        if (string.IsNullOrWhiteSpace(text))
            return 0;

        var match = SizeRegex.Match(text.Replace('\u00A0', ' '));
        if (!match.Success ||
            !decimal.TryParse(
                match.Groups["value"].Value.Replace(',', '.'),
                NumberStyles.Number,
                CultureInfo.InvariantCulture,
                out var value))
            return 0;

        var unit = match.Groups["unit"].Value.ToUpperInvariant();
        var multiplier = unit switch
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

    private static string CleanText(string value) =>
        string.Join(' ', WebUtility.HtmlDecode(value)
            .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));

    private static int ParseInt(string? value)
    {
        var cleaned = CleanText(value ?? string.Empty)
            .Replace(",", string.Empty, StringComparison.Ordinal)
            .Replace(" ", string.Empty, StringComparison.Ordinal);

        return int.TryParse(cleaned, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : 0;
    }
}
