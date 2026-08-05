using AudioBookRed.Api.Sources;

namespace AudioBookRed.Api.Services;

public sealed class RutorSourceDefinition : ISourceModule
{
    public const string Key = "rutor";
    public const int BooksCategoryId = 11;

    public string SourceKey => Key;
    public string DisplayName => "Rutor";
    public bool EnabledByDefault => false;

    public IReadOnlyList<int> Categories { get; } = [BooksCategoryId];

    public IReadOnlyList<string> Capabilities { get; } =
    [
        "paged-listing",
        "listing-magnet",
        "mirror-fallback",
        "bootstrap",
        "incremental",
        "page-map",
        "reconcile"
    ];

    public SourceRuntimeDefaults RuntimeDefaults { get; } = new(
        IncrementalPages: 1,
        WorkerJobLimit: 4,
        PageConcurrency: 2,
        DetailConcurrency: 1,
        RequestDelayMilliseconds: 250,
        MaximumAttempts: 8);

    public int ListingPageSize => 100;
    public int WorkerLeaseMinutes => 20;
}
