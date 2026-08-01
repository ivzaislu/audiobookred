namespace AudioBookRed.Api.Models;

public sealed record AudiobookSearchRequest(
    string? Query,
    string? Author,
    string? Narrator,
    string? Series,
    string? Source,
    string? AudioFormat,
    string? Quality,
    int? Year,
    string? Magnet,
    string? Sort,
    int Limit);

public sealed record FacetOption(
    string Value,
    string Label,
    long Count,
    bool MatchesQuery = false);

public sealed record AudiobookSearchFacets(
    IReadOnlyList<FacetOption> Authors,
    IReadOnlyList<FacetOption> Narrators,
    IReadOnlyList<FacetOption> Series,
    IReadOnlyList<FacetOption> Sources,
    IReadOnlyList<FacetOption> Formats,
    IReadOnlyList<FacetOption> Qualities,
    IReadOnlyList<FacetOption> Years);

public sealed record AudiobookSearchResponse(
    long Total,
    IReadOnlyList<AudiobookRelease> Items,
    AudiobookSearchFacets Facets);
