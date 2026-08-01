namespace AudioBookRed.Api.Models;

public sealed class SourceRuntimeSettings
{
    public string Source { get; set; } = "";
    public bool Enabled { get; set; }
    public int IncrementalPages { get; set; }
    public int WorkerJobLimit { get; set; }
    public int PageConcurrency { get; set; }
    public int DetailConcurrency { get; set; }
    public int RequestDelayMilliseconds { get; set; }
    public int MaximumAttempts { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
}

public sealed record UpdateSourceRuntimeSettings(
    bool? Enabled,
    int? IncrementalPages,
    int? WorkerJobLimit,
    int? PageConcurrency,
    int? DetailConcurrency,
    int? RequestDelayMilliseconds,
    int? MaximumAttempts);
