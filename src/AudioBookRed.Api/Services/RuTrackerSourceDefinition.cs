namespace AudioBookRed.Api.Services;

public sealed class RuTrackerSourceDefinition
{
    public const string Key = "rutracker";

    public IReadOnlyList<int> Categories { get; } =
    [
        574, 1036, 400, 2388, 2387, 661, 2348, 2127,
        2137, 499, 490, 467, 402, 399, 695, 2152,
        530, 2342, 2325, 2165, 716, 403, 1350
    ];

    public int ListingPageSize => 50;

    // Значения по умолчанию записываются в source_runtime_settings при первом запуске.
    // После этого их можно менять через API/CLI без пересборки контейнера.
    public int DefaultIncrementalPages => 3;
    public int DefaultWorkerJobLimit => 3;
    public int DefaultPageConcurrency => 3;
    public int DefaultDetailConcurrency => 3;
    public int DefaultRequestDelayMilliseconds => 150;
    public int DefaultMaximumAttempts => 8;

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
