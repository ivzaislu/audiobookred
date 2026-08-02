using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Sources;

public sealed record SourceRuntimeDefaults(
    int IncrementalPages,
    int WorkerJobLimit,
    int PageConcurrency,
    int DetailConcurrency,
    int RequestDelayMilliseconds,
    int MaximumAttempts);

public interface ISourceModule
{
    string SourceKey { get; }
    string DisplayName { get; }
    IReadOnlyList<int> Categories { get; }
    SourceRuntimeDefaults RuntimeDefaults { get; }
    IReadOnlyList<string> Capabilities { get; }
}

public interface ISourceCrawler
{
    string SourceKey { get; }
    IReadOnlyList<int> Categories { get; }

    Task<SourceBootstrapDiscoveryResult> StartBootstrapAsync(CancellationToken ct);
    Task<SourceBootstrapDiscoveryResult> DiscoverBootstrapAsync(CancellationToken ct);
    Task<SourceBootstrapDiscoveryResult> DiscoverReconcileAsync(CancellationToken ct);
    Task<SourceRunEnqueueResult> EnqueueIncrementalAsync(CancellationToken ct);
    Task<SourceWorkerResult> WorkAsync(int? requestedLimit, CancellationToken ct);
    Task PauseBootstrapAsync(CancellationToken ct);
    Task ResumeBootstrapAsync(CancellationToken ct);
    Task ResetBootstrapAsync(CancellationToken ct);
    Task<int> RetryFailedAsync(string? mode, CancellationToken ct);
    Task<int> RetryTopicFailuresAsync(CancellationToken ct);
    Task<object> GetCompletenessAsync(CancellationToken ct);
    Task<SourceRuntimeSettings> GetSettingsAsync(CancellationToken ct);
    Task<SourceRuntimeSettings> UpdateSettingsAsync(
        UpdateSourceRuntimeSettings update,
        CancellationToken ct);
    Task<IReadOnlyList<SourceJobEvent>> GetEventsAsync(int limit, CancellationToken ct);
    Task<object> RunMaintenanceAsync(CancellationToken ct);
    Task<object> GetStatusAsync(CancellationToken ct);
}

public sealed record SourceModuleDescriptor(
    string Source,
    string DisplayName,
    IReadOnlyList<int> Categories,
    SourceRuntimeDefaults Defaults,
    IReadOnlyList<string> Capabilities);
