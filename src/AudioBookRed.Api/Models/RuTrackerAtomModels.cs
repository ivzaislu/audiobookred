namespace AudioBookRed.Api.Models;

public sealed record RuTrackerAtomImportRequest(
    int? ForumId = null,
    int? MaxEntries = null);

public sealed record RuTrackerAtomEntry(
    long TopicId,
    string Title,
    string TopicUrl,
    long? SizeBytes,
    DateTimeOffset? UpdatedAt,
    string? Publisher,
    int ForumId);

public sealed record RuTrackerAtomFetchResult(
    int ForumId,
    bool NotModified,
    DateTimeOffset? FeedUpdatedAt,
    IReadOnlyList<RuTrackerAtomEntry> Entries);

public enum RuTrackerAtomObservationKind
{
    New,
    Changed,
    Skipped
}

public sealed record RuTrackerAtomObservation(
    RuTrackerAtomObservationKind Kind,
    long TopicId,
    string Fingerprint);

public sealed record RuTrackerAtomQueueRegistration(
    bool Handled,
    bool Enqueued);

public sealed class RuTrackerAtomFingerprintState
{
    public string Fingerprint { get; set; } = string.Empty;
    public string? HandledFingerprint { get; set; }
}

public sealed class RuTrackerAtomPendingRow
{
    public long TopicId { get; set; }
    public string Title { get; set; } = string.Empty;
    public string TopicUrl { get; set; } = string.Empty;
    public long? SizeBytes { get; set; }
    public DateTimeOffset? UpdatedAt { get; set; }
    public int ForumId { get; set; }
}

public sealed class RuTrackerAtomQueueState
{
    public string Status { get; set; } = string.Empty;
    public string Title { get; set; } = string.Empty;
    public string TopicUrl { get; set; } = string.Empty;
    public long SizeBytes { get; set; }
    public int Seeders { get; set; }
    public int Leechers { get; set; }
    public string DetailFingerprint { get; set; } = string.Empty;
}

public sealed record RuTrackerAtomImportResult(
    int ForumId,
    int Requested,
    int Received,
    int New,
    int Changed,
    int Skipped,
    int Enqueued,
    int Failed,
    bool NotModified,
    DateTimeOffset? FeedUpdatedAt,
    IReadOnlyList<string> Errors)
{
    // Совместимость с клиентами 0.19.0: imported теперь означает число
    // реально поставленных в source_topic_jobs тем, а не прямых detail-импортов.
    public int Imported => Enqueued;
}

public sealed record RuTrackerAtomStatus(
    bool Enabled,
    int IntervalMinutes,
    int MaxEntries,
    IReadOnlyList<int> ForumIds,
    bool Running,
    DateTimeOffset? LastStartedAt,
    DateTimeOffset? LastFinishedAt,
    DateTimeOffset? LastSuccessAt,
    int? LastForumId,
    int CurrentForumIndex,
    int TotalForums,
    long? LastCycleDurationMilliseconds,
    int LastReceived,
    int LastNew,
    int LastChanged,
    int LastSkipped,
    int LastEnqueued,
    int LastImported,
    int LastFailed,
    bool LastNotModified,
    string? LastError);
