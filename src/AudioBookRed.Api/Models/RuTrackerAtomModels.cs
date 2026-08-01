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

public sealed record RuTrackerAtomImportResult(
    int ForumId,
    int Requested,
    int Received,
    int Imported,
    int Failed,
    bool NotModified,
    DateTimeOffset? FeedUpdatedAt,
    IReadOnlyList<string> Errors);

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
    int LastReceived,
    int LastImported,
    int LastFailed,
    bool LastNotModified,
    string? LastError);
