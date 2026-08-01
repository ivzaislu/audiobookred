namespace AudioBookRed.Api.Models;

public sealed record RuTrackerImportRequest(
    string? Query = null,
    int? ForumId = null,
    int Page = 1,
    int MaxResults = 50);

public sealed record RuTrackerSearchItem(
    long TopicId,
    string Title,
    string Category,
    string TopicUrl,
    long SizeBytes,
    int Seeders,
    int Leechers);

public sealed record RuTrackerImportResult(
    int Requested,
    int Received,
    int Imported,
    int Failed,
    int Page,
    int ForumId,
    string? Query,
    IReadOnlyList<string> Errors);

public sealed record RuTrackerStatus(
    bool Configured,
    string BaseUrl,
    int DefaultForumId,
    bool MetadataOnly);
