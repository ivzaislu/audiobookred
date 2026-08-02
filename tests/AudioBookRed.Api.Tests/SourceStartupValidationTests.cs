using AudioBookRed.Api.Models;
using AudioBookRed.Api.Sources;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AudioBookRed.Api.Tests;

public sealed class SourceStartupValidationTests
{
    [Fact]
    public void Resolves_valid_registry_during_startup_validation()
    {
        var services = new ServiceCollection();
        services.AddSingleton<ISourceModule>(new StubModule("demo"));
        services.AddSingleton<ISourceCrawler>(new StubCrawler("demo"));
        services.AddSingleton<SourceRegistry>();

        using var provider = services.BuildServiceProvider();
        var registry = provider.ValidateSourceRegistry();

        Assert.True(registry.TryGetModule("demo", out _));
        Assert.True(registry.TryGetCrawler("demo", out _));
    }

    [Fact]
    public void Stops_startup_when_module_has_no_crawler()
    {
        var services = new ServiceCollection();
        services.AddSingleton<ISourceModule>(new StubModule("demo"));
        services.AddSingleton<SourceRegistry>();

        using var provider = services.BuildServiceProvider();
        var error = Assert.Throws<InvalidOperationException>(
            () => provider.ValidateSourceRegistry());

        Assert.Contains("No source crawler registrations", error.Message);
    }

    private sealed class StubModule(string sourceKey) : ISourceModule
    {
        public string SourceKey { get; } = sourceKey;
        public string DisplayName => "Demo";
        public IReadOnlyList<int> Categories { get; } = [1];
        public SourceRuntimeDefaults RuntimeDefaults { get; } =
            new(2, 3, 3, 3, 150, 8);
        public IReadOnlyList<string> Capabilities { get; } = ["incremental"];
    }

    private sealed class StubCrawler(string sourceKey) : ISourceCrawler
    {
        public string SourceKey { get; } = sourceKey;
        public IReadOnlyList<int> Categories { get; } = [1];

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
