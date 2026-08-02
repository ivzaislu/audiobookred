using System.Globalization;
using System.Net;
using System.Text.RegularExpressions;
using AngleSharp.Dom;
using AngleSharp.Html.Parser;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed partial class RuTrackerListingClient(
    RuTrackerTransport transport,
    RuTrackerSourceDefinition definition)
{
    public async Task<RuTrackerListingPage> FetchPageAsync(
        int categoryId,
        int page,
        CancellationToken ct)
    {
        if (!definition.Categories.Contains(categoryId))
            throw new ArgumentOutOfRangeException(nameof(categoryId), "Неизвестная категория RuTracker.");
        if (page < 1)
            throw new ArgumentOutOfRangeException(nameof(page), "Страница должна быть >= 1.");

        var start = (page - 1) * definition.ListingPageSize;
        var url = new Uri(
            transport.BaseUri,
            $"forum/viewforum.php?f={categoryId}&start={start}");
        var html = await transport.GetAuthenticatedHtmlAsync(url, url, ct);

        var parser = new HtmlParser();
        var document = await parser.ParseDocumentAsync(html, ct);
        var pageCategory = CleanText(
            document.QuerySelector("h1.maintitle a, h1.maintitle")?.TextContent ?? string.Empty);
        var listingTable = document.QuerySelector("table.vf-table");
        if (listingTable is null)
        {
            var title = CleanText(document.Title ?? string.Empty);
            throw new RuTrackerListingTableMissingException(categoryId, page, title);
        }

        var items = new List<RuTrackerSearchItem>();
        var seen = new HashSet<long>();

        foreach (var row in document.QuerySelectorAll(
                     "table.vf-table tr[data-topic_id], tr.hl-tr[data-topic_id]"))
        {
            var link = row.QuerySelector(
                "a.tLink, a.med.tLink, a[data-topic_id], a.tt-text, a[id^='tt-']");
            if (link is null)
                continue;

            var topicId = ParseTopicId(row, link);
            if (topicId <= 0 || !seen.Add(topicId))
                continue;

            var title = CleanText(link.TextContent);
            if (string.IsNullOrWhiteSpace(title))
                continue;

            var category = CleanText(
                row.QuerySelector(".f-name a, td.f-name-col a")?.TextContent ?? pageCategory);
            var size = ParseSizeBytes(row);
            var seeds = ParseInt(row.QuerySelector("td.seedmed b, b.seedmed, span.seedmed b")?.TextContent);
            var leeches = ParseInt(row.QuerySelector("td.leechmed b, b.leechmed, span.leechmed b")?.TextContent);
            var topicUrl = new Uri(transport.BaseUri, $"forum/viewtopic.php?t={topicId}").ToString();

            items.Add(new RuTrackerSearchItem(
                topicId,
                title,
                category,
                topicUrl,
                size,
                seeds,
                leeches));
        }

        var starts = document.QuerySelectorAll("a[href*='viewforum.php'][href*='start=']")
            .Select(a => ParseStart(a.GetAttribute("href") ?? string.Empty))
            .Where(value => value >= 0)
            .ToArray();
        var linkTotalPages = starts.Length == 0
            ? page
            : starts.Max() / definition.ListingPageSize + 1;
        var textMatch = PageCount().Match(CleanText(document.Body?.TextContent ?? string.Empty));
        var textTotalPages = textMatch.Success && int.TryParse(
            textMatch.Groups[1].Value,
            NumberStyles.Integer,
            CultureInfo.InvariantCulture,
            out var parsedTotal)
            ? parsedTotal
            : page;
        var totalPages = Math.Max(page, Math.Max(linkTotalPages, textTotalPages));
        var hasNextPage = page < totalPages;

        // Для выбранных аудиокатегорий пустая страница внутри объявленного
        // диапазона почти всегда означает Cloudflare/Worker-заглушку или
        // изменившуюся разметку. Не считаем её концом категории: queue worker
        // повторит страницу отдельно.
        if (items.Count == 0)
        {
            throw new InvalidDataException(
                $"Каталог RuTracker категории {categoryId}, страница {page}, не содержит ни одной темы при totalPages={totalPages}.");
        }

        return new RuTrackerListingPage(categoryId, page, totalPages, hasNextPage, items);
    }

    private static int ParseStart(string href)
    {
        var match = StartParameter().Match(WebUtility.HtmlDecode(href));
        return match.Success && int.TryParse(
            match.Groups[1].Value,
            NumberStyles.Integer,
            CultureInfo.InvariantCulture,
            out var parsed)
            ? parsed
            : -1;
    }

    private static long ParseTopicId(IElement row, IElement link)
    {
        foreach (var value in new[]
                 {
                     row.GetAttribute("data-topic_id"),
                     link.GetAttribute("data-topic_id")
                 })
        {
            if (long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed))
                return parsed;
        }

        var href = link.GetAttribute("href") ?? string.Empty;
        var match = TopicId().Match(href);
        return match.Success && long.TryParse(
            match.Groups[1].Value,
            NumberStyles.Integer,
            CultureInfo.InvariantCulture,
            out var topicId)
            ? topicId
            : 0;
    }

    private static long ParseSizeBytes(IElement row)
    {
        var exact = row.QuerySelector("td.tor-size[data-ts_text]")?.GetAttribute("data-ts_text");
        if (long.TryParse(exact, NumberStyles.Integer, CultureInfo.InvariantCulture, out var exactBytes))
            return exactBytes;

        var text = CleanText(
            row.QuerySelector("a.f-dl.dl-stub, td.tor-size, td[data-ts_text]")?.TextContent ?? string.Empty);
        var match = Size().Match(text.Replace('\u00A0', ' '));
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

    [GeneratedRegex(@"(?:[?&]t=)(\d+)", RegexOptions.IgnoreCase)]
    private static partial Regex TopicId();

    [GeneratedRegex(@"[?&]start=(\d+)", RegexOptions.IgnoreCase)]
    private static partial Regex StartParameter();

    [GeneratedRegex(@"Страница\s+\d+\s+из\s+(\d+)", RegexOptions.IgnoreCase)]
    private static partial Regex PageCount();

    [GeneratedRegex(@"(?<value>\d+(?:[.,]\d+)?)\s*(?<unit>B|KB|KIB|MB|MIB|GB|GIB|TB|TIB|Б|КБ|МБ|ГБ|ТБ)\b", RegexOptions.IgnoreCase)]
    private static partial Regex Size();
}
