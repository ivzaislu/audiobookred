namespace AudioBookRed.Api.Models;

public sealed class SourceCategoryCrawlState
{
    public string Source { get; set; } = "";
    public int CategoryId { get; set; }
    public int CategoryOrder { get; set; }
    public int BootstrapNextPage { get; set; }
    public int? BootstrapLastPage { get; set; }
    public bool BootstrapCompleted { get; set; }
    public DateTimeOffset? LastBootstrapPageAt { get; set; }
    public DateTimeOffset? LastIncrementalAt { get; set; }
    public long PagesScanned { get; set; }
    public long ReleasesSeen { get; set; }
    public long ReleasesInserted { get; set; }
    public long ReleasesChanged { get; set; }
    public string? LastError { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
}

public sealed class SourceCrawlControl
{
    public string Source { get; set; } = "";
    public bool BootstrapPaused { get; set; }
    public DateTimeOffset? BootstrapStartedAt { get; set; }
    public DateTimeOffset? BootstrapCompletedAt { get; set; }
    public DateTimeOffset? LastIncrementalStartedAt { get; set; }
    public DateTimeOffset? LastIncrementalCompletedAt { get; set; }
    public string? LastError { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
}

public sealed class CrawlUpsertResult
{
    public long Id { get; set; }
    public bool Inserted { get; set; }
    public bool Changed { get; set; }
}

public interface ISourceListingItem
{
    long TopicId { get; }
    string Title { get; }
    string Category { get; }
    string TopicUrl { get; }
    long SizeBytes { get; }
    int Seeders { get; }
    int Leechers { get; }
}

public interface ISourceListingPage
{
    int CategoryId { get; }
    int Page { get; }
    int TotalPages { get; }
    bool HasNextPage { get; }
    int ItemCount { get; }
}

public sealed record RuTrackerListingPage(
    int CategoryId,
    int Page,
    int TotalPages,
    bool HasNextPage,
    IReadOnlyList<RuTrackerSearchItem> Items) : ISourceListingPage
{
    public int ItemCount => Items.Count;
}

public sealed class ExistingListingState
{
    public long TopicId { get; set; }
    public string? ListingFingerprint { get; set; }
    public string? DetailFingerprint { get; set; }
    public string? InfoHash { get; set; }
    public string? RawTitle { get; set; }
    public long? SizeBytes { get; set; }
    public bool HasMagnet { get; set; }
}

public sealed record DetailDrainSummary(
    int Batches,
    int Candidates,
    int Enriched,
    int Missing,
    int Failed);

public sealed record ListingImportSummary(
    int Inserted,
    int Changed,
    DetailDrainSummary Details);

public sealed record RuTrackerBootstrapTickResult(
    string Source,
    bool Completed,
    bool Paused,
    int? CategoryId,
    int? Page,
    int Received,
    int Inserted,
    int Changed,
    bool CategoryCompleted,
    DetailDrainSummary Details,
    int EligibleDetailsRemaining,
    string? Message);

public sealed record RuTrackerIncrementalResult(
    string Source,
    int Categories,
    int Pages,
    int Received,
    int Inserted,
    int Changed,
    DetailDrainSummary Details,
    TimeSpan Elapsed,
    IReadOnlyList<string> Errors);

public sealed record RuTrackerCrawlStatus(
    string Source,
    IReadOnlyList<int> Categories,
    bool BootstrapPaused,
    bool BootstrapCompleted,
    int CategoriesCompleted,
    int CategoriesTotal,
    int EligibleDetailsPending,
    DateTimeOffset? BootstrapStartedAt,
    DateTimeOffset? BootstrapCompletedAt,
    DateTimeOffset? LastIncrementalStartedAt,
    DateTimeOffset? LastIncrementalCompletedAt,
    string? LastError,
    IReadOnlyList<SourceCategoryCrawlState> CategoryStates);
