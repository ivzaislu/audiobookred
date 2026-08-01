using System.Text;
using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Compatibility;

public static class TorznabEndpoints
{
    private static readonly string[] Routes =
    [
        "/torznab/api",
        "/api/v2.0/indexers/{indexer}/results/torznab/api",
        "/api/v1/indexer/{indexer}/newznab"
    ];

    public static void MapAudioBookRedTorznab(this WebApplication app)
    {
        foreach (var route in Routes)
        {
            app.MapMethods(
                route,
                [HttpMethods.Get, HttpMethods.Head],
                async (HttpContext context, AudiobookRepository repository, CancellationToken ct) =>
                    await HandleAsync(context, repository, ct));
        }
    }

    public static bool IsCompatibilityPath(PathString path) =>
        path.StartsWithSegments("/torznab/api", StringComparison.OrdinalIgnoreCase) ||
        path.StartsWithSegments("/api/v2.0/indexers", StringComparison.OrdinalIgnoreCase) ||
        path.StartsWithSegments("/api/v1/indexer", StringComparison.OrdinalIgnoreCase);

    private static async Task<IResult> HandleAsync(
        HttpContext context,
        AudiobookRepository repository,
        CancellationToken ct)
    {
        var indexer = context.Request.RouteValues["indexer"]?.ToString();
        if (!string.IsNullOrWhiteSpace(indexer) &&
            !indexer.Equals("audiobookred", StringComparison.OrdinalIgnoreCase) &&
            !indexer.Equals("all", StringComparison.OrdinalIgnoreCase))
        {
            return Results.NotFound();
        }

        var origin = $"{context.Request.Scheme}://{context.Request.Host}{context.Request.PathBase}".TrimEnd('/');
        var apiUrl = origin + context.Request.Path;
        var action = context.Request.Query["t"].ToString().Trim().ToLowerInvariant();
        if (string.IsNullOrEmpty(action))
            action = "search";

        switch (action)
        {
            case "caps":
                return Xml(TorznabXmlFormatter.CreateCapabilities(apiUrl));
            case "indexers":
                return Xml(TorznabXmlFormatter.CreateIndexerList());
            case "search":
            case "booksearch":
            case "musicsearch":
            case "audiosearch":
                break;
            default:
                return Xml(
                    TorznabXmlFormatter.CreateError("201", $"Unsupported search type: {action}"),
                    StatusCodes.Status400BadRequest);
        }

        var query = NormalizeQuery(FirstNonEmpty(
            context.Request.Query["q"].ToString(),
            context.Request.Query["query"].ToString(),
            context.Request.Query["title"].ToString(),
            context.Request.Query["album"].ToString()));
        var author = FirstNonEmpty(
            context.Request.Query["author"].ToString(),
            context.Request.Query["artist"].ToString());
        var source = FirstNonEmpty(
            context.Request.Query["source"].ToString(),
            context.Request.Query["tracker"].ToString());
        var year = ParseYear(context.Request.Query["year"].ToString());
        var limit = ParseLimit(context.Request.Query["limit"].ToString());
        var offset = ParseBoundedInt(context.Request.Query["offset"].ToString(), 0, 0, 1_000_000);
        var sort = ParseSort(context.Request.Query["sort"].ToString());

        var request = new AudiobookSearchRequest(
            query,
            author,
            null,
            null,
            source,
            null,
            null,
            year,
            "yes",
            sort,
            limit);

        var page = await repository.SearchPageAsync(request, offset, ct);
        var feed = TorznabXmlFormatter.CreateSearchFeed(
            page.Items,
            origin,
            apiUrl,
            offset,
            page.Total);
        return Rss(feed);
    }

    private static string? FirstNonEmpty(params string?[] values) =>
        values.Select(value => value?.Trim()).FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));

    private static string? NormalizeQuery(string? value) =>
        string.IsNullOrWhiteSpace(value) || value == "*" ? null : value;

    private static int? ParseYear(string value) =>
        int.TryParse(value, out var parsed) && parsed is >= 1900 and <= 2200 ? parsed : null;

    private static int ParseLimit(string value) =>
        int.TryParse(value, out var parsed) && parsed > 0 ? Math.Clamp(parsed, 1, 250) : 100;

    private static int ParseBoundedInt(string value, int defaultValue, int min, int max) =>
        int.TryParse(value, out var parsed) ? Math.Clamp(parsed, min, max) : defaultValue;

    private static string ParseSort(string value) => value.Trim().ToLowerInvariant() switch
    {
        "time" or "date" or "publishdate" or "updated" => "updatedAt",
        "size" => "sizeBytes",
        "title" => "title",
        _ => "seeders"
    };

    private static IResult Xml(string content, int statusCode = StatusCodes.Status200OK) =>
        Results.Text(content, "application/xml", Encoding.UTF8, statusCode);

    private static IResult Rss(string content) =>
        Results.Text(content, "application/rss+xml", Encoding.UTF8, StatusCodes.Status200OK);
}
