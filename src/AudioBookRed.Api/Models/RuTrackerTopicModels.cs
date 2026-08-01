namespace AudioBookRed.Api.Models;

public sealed class RuTrackerTopicJob
{
    public long Id { get; set; }
    public string Source { get; set; } = "";
    public long TopicId { get; set; }
    public int CategoryId { get; set; }
    public int LastPage { get; set; }
    public string Title { get; set; } = "";
    public string TopicUrl { get; set; } = "";
    public long SizeBytes { get; set; }
    public int Seeders { get; set; }
    public int Leechers { get; set; }
    public string ListingFingerprint { get; set; } = "";
    public string DetailFingerprint { get; set; } = "";
    public string Status { get; set; } = "";
    public int Attempts { get; set; }
    public DateTimeOffset? NextAttemptAt { get; set; }
    public DateTimeOffset? LeaseUntil { get; set; }
    public string? InfoHash { get; set; }
    public long? ReleaseId { get; set; }
    public string? LastError { get; set; }
}

public sealed record RuTrackerTopicQueueSummary(
    int Pending,
    int Running,
    int Retry,
    int Imported,
    int MissingMagnet,
    int DuplicateInfoHash,
    int Failed)
{
    public int Waiting => Pending + Retry;
    public int Outstanding => Pending + Running + Retry;
    public int Discovered => Pending + Running + Retry + Imported + MissingMagnet + DuplicateInfoHash + Failed;
    public int Resolved => Imported + MissingMagnet + DuplicateInfoHash;
}

public sealed record RuTrackerCompletenessStatus(
    string Source,
    int DiscoveredTopics,
    int Imported,
    int MissingMagnet,
    int DuplicateInfoHash,
    int Waiting,
    int Running,
    int Failed,
    int Occurrences,
    decimal CompletionPercent,
    DateTimeOffset GeneratedAt);
