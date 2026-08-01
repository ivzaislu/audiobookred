using System.Globalization;
using System.Net;
using AngleSharp.Dom;
using AngleSharp.Html.Parser;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerClient(
    IConfiguration configuration,
    RuTrackerTransport transport)
{
    private string BaseUrl => configuration["RuTracker:BaseUrl"] ?? "https://rutracker.org";
    private string Username => configuration["RuTracker:Username"] ?? "";
    private string Password => configuration["RuTracker:Password"] ?? "";
    public int DefaultForumId => configuration.GetValue<int?>("RuTracker:DefaultForumId") ?? 2388;

    public RuTrackerStatus GetStatus() => new(
        !string.IsNullOrWhiteSpace(Username) && !string.IsNullOrWhiteSpace(Password),
        BaseUrl,
        DefaultForumId,
        false);

    public async Task<IReadOnlyList<RuTrackerSearchItem>> SearchAsync(
        string? query,
        int forumId,
        int page,
        int maxResults,
        CancellationToken ct)
    {
        if (forumId <= 0 && string.IsNullOrWhiteSpace(query))
            throw new ArgumentException("Нужен forumId или поисковый запрос.");
        if (page < 1)
            throw new ArgumentOutOfRangeException(nameof(page), "Страница должна быть >= 1.");

        // Как в JacRed: tracker.php через GET, сортировка по сидам по убыванию.
        var parameters = new List<string>();
        if (!string.IsNullOrWhiteSpace(query))
            parameters.Add("nm=" + Uri.EscapeDataString(query.Trim()));
        if (forumId > 0)
            parameters.Add("f%5B%5D=" + forumId.ToString(CultureInfo.InvariantCulture));
        parameters.Add("o=10");
        parameters.Add("s=2");
        if (page > 1)
            parameters.Add("start=" + ((page - 1) * 50).ToString(CultureInfo.InvariantCulture));

        var url = new Uri(transport.BaseUri, "forum/tracker.php?" + string.Join("&", parameters));
        var html = await transport.GetAuthenticatedHtmlAsync(url, url, ct);

        var parser = new HtmlParser();
        var document = await parser.ParseDocumentAsync(html, ct);
        var items = new List<RuTrackerSearchItem>();

        foreach (var row in document.QuerySelectorAll("#tor-tbl tbody tr, tr.hl-tr"))
        {
            if (items.Count >= Math.Clamp(maxResults, 1, 50))
                break;

            var link = row.QuerySelector("a.tLink, a.med.tLink, a[data-topic_id]");
            if (link is null)
                continue;

            var topicIdText = link.GetAttribute("data-topic_id");
            if (!long.TryParse(topicIdText, NumberStyles.Integer, CultureInfo.InvariantCulture, out var topicId))
                continue;

            var title = CleanText(link.TextContent);
            if (string.IsNullOrWhiteSpace(title))
                continue;

            var category = CleanText(row.QuerySelector(".f-name a, td.f-name-col a")?.TextContent ?? "");
            var size = ParseLongAttribute(row.QuerySelector("td.tor-size[data-ts_text], td.tor-size"), "data-ts_text");
            var seeds = ParseInt(row.QuerySelector("td.seedmed b, b.seedmed")?.TextContent);
            var leeches = ParseInt(row.QuerySelector("td.leechmed b, b.leechmed")?.TextContent);
            var topicUrl = new Uri(transport.BaseUri, $"forum/viewtopic.php?t={topicId}").ToString();

            items.Add(new RuTrackerSearchItem(topicId, title, category, topicUrl, size, seeds, leeches));
        }

        return items;
    }

    private static string CleanText(string value) =>
        string.Join(' ', WebUtility.HtmlDecode(value)
            .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));

    private static int ParseInt(string? value) =>
        int.TryParse(CleanText(value ?? ""), NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : 0;

    private static long ParseLongAttribute(IElement? element, string attribute)
    {
        var value = element?.GetAttribute(attribute);
        return long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : 0;
    }
}

public sealed class RuTrackerAuthenticationException(string message) : Exception(message);
