using AudioBookRed.Api.Sources;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerSourceDefinition : ISourceModule
{
    public const string Key = "rutracker";

    public string SourceKey => Key;
    public string DisplayName => "RuTracker";

    public IReadOnlyList<int> Categories { get; } =
    [
        574, 1036, 400, 2388, 2387, 661, 2348, 2127,
        2137, 499, 490, 467, 402, 399, 695, 2152,
        530, 2342, 2325, 2165, 716, 403, 1350
    ];

    public IReadOnlyList<string> Capabilities { get; } =
    [
        "paged-listing",
        "topic-details",
        "magnet",
        "bootstrap",
        "incremental",
        "reconcile"
    ];

    public SourceRuntimeDefaults RuntimeDefaults { get; } = new(
        IncrementalPages: 2,
        WorkerJobLimit: 3,
        PageConcurrency: 3,
        DetailConcurrency: 3,
        RequestDelayMilliseconds: 150,
        MaximumAttempts: 8);

    public int ListingPageSize => 50;

    // Compatibility properties for RuTracker-specific services.
    public int DefaultIncrementalPages => RuntimeDefaults.IncrementalPages;
    public int DefaultWorkerJobLimit => RuntimeDefaults.WorkerJobLimit;
    public int DefaultPageConcurrency => RuntimeDefaults.PageConcurrency;
    public int DefaultDetailConcurrency => RuntimeDefaults.DetailConcurrency;
    public int DefaultRequestDelayMilliseconds =>
        RuntimeDefaults.RequestDelayMilliseconds;
    public int DefaultMaximumAttempts => RuntimeDefaults.MaximumAttempts;

    public int WorkerLeaseMinutes => 20;
    public int DetailRequestAttempts => 3;
    public int DetailRetryJitterMilliseconds => 350;

    public int TransportFailureMinimumForRetry => 4;
    public double TransportFailureRatioForRetry => 0.30;

    public long MinimumFreeBytes => 350L * 1024L * 1024L;
    public double MinimumFreeRatio => 0.0;

    public int GetDetailRetryDelay(int attempt) => attempt switch
    {
        <= 1 => 500,
        2 => 1500,
        _ => 4000
    };
}
