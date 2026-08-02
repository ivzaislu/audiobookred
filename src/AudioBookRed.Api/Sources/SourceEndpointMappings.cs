using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Sources;

public static class SourceEndpointMappings
{
    public static IEndpointRouteBuilder MapSourceCrawlerEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet("/api/v1/sources", (SourceRegistry registry) =>
            Results.Ok(new { sources = registry.Describe() }));

        endpoints.MapGet("/api/v1/sources/{source}/categories", (
            string source,
            SourceRegistry registry) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(new
            {
                source = crawler.SourceKey,
                categories = crawler.Categories
            });
        });

        endpoints.MapGet("/api/v1/sources/{source}/crawl/status", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.GetStatusAsync(ct));
        });

        endpoints.MapGet("/api/v1/sources/{source}/settings", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.GetSettingsAsync(ct));
        });

        endpoints.MapPut("/api/v1/sources/{source}/settings", async (
            string source,
            UpdateSourceRuntimeSettings update,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.UpdateSettingsAsync(update, ct));
        });

        endpoints.MapGet("/api/v1/sources/{source}/events", async (
            string source,
            int? limit,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.GetEventsAsync(limit ?? 20, ct));
        });

        endpoints.MapPost("/api/v1/sources/{source}/maintenance", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.RunMaintenanceAsync(ct));
        });

        endpoints.MapPost("/api/v1/sources/{source}/bootstrap/discover", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                return Results.Ok(await crawler.DiscoverBootstrapAsync(ct));
            }
            catch (InvalidOperationException ex)
            {
                return SourceGuardUnavailable(ex);
            }
        });

        endpoints.MapPost("/api/v1/sources/{source}/reconcile", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                return Results.Ok(await crawler.DiscoverReconcileAsync(ct));
            }
            catch (InvalidOperationException ex)
            {
                return SourceGuardUnavailable(ex);
            }
        });

        endpoints.MapGet("/api/v1/sources/{source}/completeness", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.GetCompletenessAsync(ct));
        });

        endpoints.MapPost("/api/v1/sources/{source}/topics/retry-failed", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            var retried = await crawler.RetryTopicFailuresAsync(ct);
            return Results.Ok(new { source = crawler.SourceKey, retried });
        });

        endpoints.MapGet("/api/v1/sources/{source}/metadata/status", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.GetMetadataStatusAsync(ct));
        });

        endpoints.MapPost("/api/v1/sources/{source}/metadata/reparse", async (
            string source,
            SourceMetadataReparseRequest request,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                return Results.Ok(
                    await crawler.EnqueueMetadataReparseAsync(request, ct));
            }
            catch (ArgumentException ex)
            {
                return Results.BadRequest(new
                {
                    error = "invalid_metadata_reparse_request",
                    message = ex.Message
                });
            }
            catch (InvalidOperationException ex)
            {
                return SourceGuardUnavailable(ex);
            }
        });

        endpoints.MapPost("/api/v1/sources/{source}/metadata/backfill", async (
            string source,
            int? limit,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                return Results.Ok(
                    await crawler.EnqueueMetadataBackfillAsync(limit, ct));
            }
            catch (ArgumentException ex)
            {
                return Results.BadRequest(new
                {
                    error = "invalid_metadata_backfill_request",
                    message = ex.Message
                });
            }
            catch (InvalidOperationException ex)
            {
                return SourceGuardUnavailable(ex);
            }
        });

        endpoints.MapPost("/api/v1/sources/{source}/bootstrap/start", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                return Results.Ok(await crawler.StartBootstrapAsync(ct));
            }
            catch (InvalidOperationException ex)
            {
                return SourceGuardUnavailable(ex);
            }
        });

        // Compatibility with the old bootstrap/tick endpoint: one short queue worker.
        endpoints.MapPost("/api/v1/sources/{source}/bootstrap/tick", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.WorkAsync(1, ct));
        });

        endpoints.MapPost("/api/v1/sources/{source}/work", async (
            string source,
            int? limit,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                return Results.Ok(await crawler.WorkAsync(limit, ct));
            }
            catch (InvalidOperationException ex)
            {
                return SourceGuardUnavailable(ex);
            }
        });

        endpoints.MapPost("/api/v1/sources/{source}/bootstrap/pause", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            await crawler.PauseBootstrapAsync(ct);
            return Results.Ok(new { source = crawler.SourceKey, paused = true });
        });

        endpoints.MapPost("/api/v1/sources/{source}/bootstrap/resume", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            await crawler.ResumeBootstrapAsync(ct);
            return Results.Ok(new { source = crawler.SourceKey, paused = false });
        });

        endpoints.MapPost("/api/v1/sources/{source}/bootstrap/reset", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                await crawler.ResetBootstrapAsync(ct);
                return Results.Ok(new { source = crawler.SourceKey, reset = true });
            }
            catch (InvalidOperationException ex)
            {
                return Results.Conflict(new
                {
                    error = "source_crawl_busy",
                    message = ex.Message
                });
            }
        });

        endpoints.MapPost("/api/v1/sources/{source}/incremental/enqueue", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                return Results.Ok(await crawler.EnqueueIncrementalAsync(ct));
            }
            catch (InvalidOperationException ex)
            {
                return SourceGuardUnavailable(ex);
            }
        });

        // Compatibility with the old incremental/run endpoint.
        endpoints.MapPost("/api/v1/sources/{source}/incremental/run", async (
            string source,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            return Results.Ok(await crawler.EnqueueIncrementalAsync(ct));
        });

        endpoints.MapPost("/api/v1/sources/{source}/jobs/retry-failed", async (
            string source,
            string? mode,
            SourceRegistry registry,
            CancellationToken ct) =>
        {
            if (!registry.TryGetCrawler(source, out var crawler))
                return UnknownSource(source, registry);

            try
            {
                var retried = await crawler.RetryFailedAsync(mode, ct);
                return Results.Ok(new
                {
                    source = crawler.SourceKey,
                    mode,
                    retried
                });
            }
            catch (ArgumentException ex)
            {
                return Results.BadRequest(new
                {
                    error = "invalid_mode",
                    message = ex.Message
                });
            }
        });

        return endpoints;
    }

    private static IResult UnknownSource(
        string source,
        SourceRegistry registry) =>
        Results.NotFound(new
        {
            error = "unknown_source",
            source,
            availableSources = registry.AvailableSources
        });

    private static IResult SourceGuardUnavailable(InvalidOperationException ex) =>
        Results.Json(
            new
            {
                error = "source_crawl_guard",
                message = ex.Message
            },
            statusCode: StatusCodes.Status503ServiceUnavailable);
}
