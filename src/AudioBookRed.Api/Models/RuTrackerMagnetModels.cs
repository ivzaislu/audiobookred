namespace AudioBookRed.Api.Models;

public sealed record RuTrackerMagnetRunRequest(int? Limit = null);

public sealed class RuTrackerMagnetCandidate
{
    public long Id { get; set; }
    public string SourceId { get; set; } = "";
    public string SourceUrl { get; set; } = "";
    public string Title { get; set; } = "";
    public int Attempts { get; set; }
}

public sealed record RuTrackerMagnetValue(
    string MagnetUri,
    string InfoHash);

public sealed record RuTrackerMagnetRunResult(
    int Requested,
    int Candidates,
    int Enriched,
    int Missing,
    int Failed,
    IReadOnlyList<string> Errors);

public sealed record RuTrackerMagnetStatus(
    bool Enabled,
    int IntervalMinutes,
    int BatchSize,
    int DelayMilliseconds,
    int MaxAttempts,
    int RetryMinutes,
    bool Running,
    DateTimeOffset? LastStartedAt,
    DateTimeOffset? LastFinishedAt,
    DateTimeOffset? LastSuccessAt,
    int LastCandidates,
    int LastEnriched,
    int LastMissing,
    int LastFailed,
    string? LastError);
