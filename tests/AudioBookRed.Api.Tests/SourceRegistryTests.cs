using AudioBookRed.Api.Models;
using AudioBookRed.Api.Sources;
using Xunit;

namespace AudioBookRed.Api.Tests;

public sealed class SourceRegistryTests
{
    [Fact]
    public void Resolves_registered_source_case_insensitively()
    {
        var module = new FakeModule("demo");
        var crawler = new FakeCrawler("demo");
        var registry = new SourceRegistry([module], [crawler]);

        Assert.True(registry.TryGetModule(" DEMO ", out var resolvedModule));
        Assert.True(registry.TryGetCrawler("DEMO", out var resolvedCrawler));
        Assert.Same(module, resolvedModule);
        Assert.Same(crawler, resolvedCrawler);

        var descriptor = Assert.Single(registry.Describe());
        Assert.Equal("demo", descriptor.Source);
        Assert.Equal("Demo", descriptor.DisplayName);
    }

    [Fact]
    public void Rejects_duplicate_module_keys()
    {
        var error = Assert.Throws<InvalidOperationException>(() =>
            new SourceRegistry(
                [new FakeModule("demo"), new FakeModule("DEMO")],
                [new FakeCrawler("demo")]));

        Assert.Contains("Duplicate source module", error.Message);
    }

    [Fact]
    public void Rejects_module_without_crawler()
    {
        var error = Assert.Throws<InvalidOperationException>(() =>
            new SourceRegistry(
                [new FakeModule("demo")],
                Array.Empty<ISourceCrawler>()));

        Assert.Contains("No source crawler registrations", error.Message);
    }

    [Fact]
    public void Rejects_invalid_source_key()
    {
        var error = Assert.Throws<InvalidOperationException>(() =>
            new SourceRegistry(
                [new FakeModule("not valid")],
                [new FakeCrawler("not valid")]));

        Assert.Contains("Invalid source key", error.Message);
    }

    private sealed class FakeModule(string sourceKey) : ISourceModule
    {
        public string SourceKey { get; } = sourceKey;
        public string DisplayName => "Demo";
        public IReadOnlyList<int> Categories { get; } = [1, 2];
        public SourceRuntimeDefaults RuntimeDefaults { get; } =
            new(2, 3, 3, 3, 150, 8);
        public IReadOnlyList<string> Capabilities { get; } = ["incremental"];
    }

    private sealed class FakeCrawler(string sourceKey) : ISourceCrawler
    {
        public string SourceKey { get; } = sourceKey;
        public IReadOnlyList<int> Categories { get; } = [1, 2];

        public Task<SourceBootstrapDiscoveryResult> StartBootstrapAsync(
            CancellationToken ct) => throw new NotSupportedException();

        public Task<SourceBootstrapDiscoveryResult> DiscoverBootstrapAsync(
            CancellationToken ct) => throw new NotSupportedException();

        public Task<SourceBootstrapDiscoveryResult> DiscoverReconcileAsync(
            CancellationToken ct) => throw new NotSupportedException();

        public Task<SourceRunEnqueueResult> EnqueueIncrementalAsync(
            CancellationToken ct) => throw new NotSupportedException();

        public Task<SourceWorkerResult> WorkAsync(
            int? requestedLimit,
            CancellationToken ct) => throw new NotSupportedException();

        public Task PauseBootstrapAsync(CancellationToken ct) =>
            throw new NotSupportedException();

        public Task ResumeBootstrapAsync(CancellationToken ct) =>
            throw new NotSupportedException();

        public Task ResetBootstrapAsync(CancellationToken ct) =>
            throw new NotSupportedException();

        public Task<int> RetryFailedAsync(
            string? mode,
            CancellationToken ct) => throw new NotSupportedException();

        public Task<int> RetryTopicFailuresAsync(CancellationToken ct) =>
            throw new NotSupportedException();

        public Task<object> GetCompletenessAsync(CancellationToken ct) =>
            throw new NotSupportedException();

        public Task<SourceRuntimeSettings> GetSettingsAsync(
            CancellationToken ct) => throw new NotSupportedException();

        public Task<SourceRuntimeSettings> UpdateSettingsAsync(
            UpdateSourceRuntimeSettings update,
            CancellationToken ct) => throw new NotSupportedException();

        public Task<IReadOnlyList<SourceJobEvent>> GetEventsAsync(
            int limit,
            CancellationToken ct) => throw new NotSupportedException();

        public Task<object> RunMaintenanceAsync(CancellationToken ct) =>
            throw new NotSupportedException();

        public Task<object> GetStatusAsync(CancellationToken ct) =>
            throw new NotSupportedException();
    }
}
