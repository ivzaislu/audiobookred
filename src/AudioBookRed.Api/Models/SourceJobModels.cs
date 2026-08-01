namespace AudioBookRed.Api.Models;

public sealed class SourceCrawlRun
{
    public long Id { get; set; }
    public string Source { get; set; } = "";
    public string Mode { get; set; } = "";
    public string RunKey { get; set; } = "";
    public string Status { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset? StartedAt { get; set; }
    public DateTimeOffset? CompletedAt { get; set; }
    public string? LastError { get; set; }
}

public sealed class SourceCrawlJob
{
    public long Id { get; set; }
    public long RunId { get; set; }
    public string Source { get; set; } = "";
    public string Mode { get; set; } = "";
    public int CategoryId { get; set; }
    public int Page { get; set; }
    public string Status { get; set; } = "";
    public int Attempts { get; set; }
    public DateTimeOffset? NextAttemptAt { get; set; }
    public DateTimeOffset? LeaseUntil { get; set; }
    public string? LastError { get; set; }
}

public sealed class SourceJobEvent
{
    public long Id { get; set; }
    public string Source { get; set; } = "";
    public long? RunId { get; set; }
    public long? JobId { get; set; }
    public string EventType { get; set; } = "";
    public string? Mode { get; set; }
    public int? CategoryId { get; set; }
    public int? Page { get; set; }
    public string? Message { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
}

public sealed record SourceQueueSummary(
    int Pending,
    int Running,
    int Retry,
    int Completed,
    int Failed)
{
    public int Waiting => Pending + Retry;
    public int Outstanding => Pending + Running + Retry;
}

public sealed record SourceRunEnqueueResult(
    string Source,
    string Mode,
    long RunId,
    string RunKey,
    string Status,
    int JobsAdded,
    SourceQueueSummary Queue,
    string Message);

public sealed record SourceBootstrapDiscoveryResult(
    string Source,
    long RunId,
    int CategoriesDiscovered,
    int CategoriesFailed,
    int PagesDiscovered,
    int JobsAdded,
    SourceQueueSummary Queue,
    IReadOnlyList<string> Errors,
    string Message);

public sealed record SourceJobResult(
    long JobId,
    string Mode,
    int CategoryId,
    int Page,
    string Status,
    int Received,
    int Inserted,
    int Changed,
    DetailDrainSummary Details,
    string? Error);

public sealed record SourceWorkerResult(
    string Source,
    int Claimed,
    int Completed,
    int Retried,
    int Failed,
    TimeSpan Elapsed,
    SourceQueueSummary Queue,
    RuTrackerTopicQueueSummary TopicQueue,
    DetailDrainSummary TopicDrain,
    IReadOnlyList<SourceJobResult> Jobs);

public sealed record RuTrackerQueuedCrawlStatus(
    string Source,
    IReadOnlyList<int> Categories,
    bool BootstrapPaused,
    bool BootstrapCompleted,
    int CategoriesCompleted,
    int CategoriesTotal,
    DateTimeOffset? BootstrapStartedAt,
    DateTimeOffset? BootstrapCompletedAt,
    DateTimeOffset? LastIncrementalStartedAt,
    DateTimeOffset? LastIncrementalCompletedAt,
    string? LastError,
    SourceRuntimeSettings Settings,
    SourceQueueSummary BootstrapQueue,
    SourceQueueSummary IncrementalQueue,
    SourceQueueSummary ReconcileQueue,
    RuTrackerTopicQueueSummary TopicQueue,
    RuTrackerCompletenessStatus Completeness,
    IReadOnlyList<SourceCrawlRun> RecentRuns,
    IReadOnlyList<SourceJobEvent> RecentEvents,
    IReadOnlyList<SourceCategoryCrawlState> CategoryStates);
