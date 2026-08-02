using System.Threading.RateLimiting;
using AudioBookRed.Api.Compatibility;
using Microsoft.AspNetCore.RateLimiting;
using AudioBookRed.Api.Data;
using AudioBookRed.Api.Infrastructure;
using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(options =>
{
    options.Limits.MaxRequestBodySize = 1024 * 1024;
    options.Limits.MaxRequestLineSize = 8 * 1024;
    options.Limits.MaxRequestHeadersTotalSize = 32 * 1024;
    options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(15);
});
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(
        RequestRateLimitPolicy.CreatePartition);
    options.OnRejected = RequestRateLimitPolicy.WriteRejectedResponseAsync;
});
builder.Services.AddSingleton<SeriesNameParser>();
builder.Services.AddSingleton<TitleNormalizer>();
builder.Services.AddSingleton<PersonNameParser>();
builder.Services.AddSingleton<CanonicalFacetRepository>();
builder.Services.AddSingleton<DatabaseMigrationRunner>();
builder.Services.AddSingleton<DatabaseReadinessProbe>();
builder.Services.AddSingleton<AudiobookRepository>();
builder.Services.AddSingleton<SourceCrawlRepository>();
builder.Services.AddSingleton<SourceJobRepository>();
builder.Services.AddSingleton<RuTrackerTopicRepository>();
builder.Services.AddSingleton<RuTrackerAtomRepository>();
builder.Services.AddSingleton<SourceSettingsRepository>();
builder.Services.AddSingleton<StatisticsRepository>();
builder.Services.AddSingleton<RuTrackerTransport>();
builder.Services.AddSingleton<RuTrackerClient>();
builder.Services.AddSingleton<RuTrackerHtmlParser>();
builder.Services.AddSingleton<RuTrackerAtomClient>();
builder.Services.AddSingleton<RuTrackerAtomState>();
builder.Services.AddSingleton<RuTrackerAtomImporter>();
builder.Services.AddSingleton<RuTrackerMagnetClient>();
builder.Services.AddSingleton<RuTrackerMagnetState>();
builder.Services.AddSingleton<RuTrackerMagnetEnricher>();
builder.Services.AddHostedService<RuTrackerAtomWorker>();

// Универсальная основа задач источников. Для RuTracker категории и политика
// находятся в модуле источника, а не в .env.
builder.Services.AddSingleton<RuTrackerSourceDefinition>();
builder.Services.AddSingleton<CrawlerResourceGuard>();
builder.Services.AddSingleton<RuTrackerListingClient>();
builder.Services.AddSingleton<RuTrackerDetailProcessor>();
builder.Services.AddSingleton<RuTrackerCrawler>();

var app = builder.Build();

app.UseMiddleware<SecurityHeadersMiddleware>();

// Browser UI from wwwroot (/ui/).
// Static files are served before API-key middleware; API calls still require X-Api-Key.
app.UseDefaultFiles();
app.UseStaticFiles();
app.UseRateLimiter();

var apiKey = builder.Configuration["ApiKey"]?.Trim();
if (string.IsNullOrWhiteSpace(apiKey) || apiKey.Equals("change-me", StringComparison.OrdinalIgnoreCase))
    throw new InvalidOperationException("ApiKey не задан. Укажите API_KEY в .env.");
app.Use(async (context, next) =>
{
    if (context.Request.Path.StartsWithSegments("/health"))
    {
        await next();
        return;
    }

    var isTorznab = TorznabEndpoints.IsCompatibilityPath(context.Request.Path);
    var headerKey = context.Request.Headers["X-Api-Key"].ToString();
    var queryKey = isTorznab ? context.Request.Query["apikey"].ToString() : string.Empty;
    var suppliedKey = !string.IsNullOrWhiteSpace(headerKey) ? headerKey : queryKey;

    if (!string.Equals(suppliedKey, apiKey, StringComparison.Ordinal))
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        if (isTorznab)
        {
            context.Response.ContentType = "application/xml; charset=utf-8";
            await context.Response.WriteAsync(
                TorznabXmlFormatter.CreateError("100", "Incorrect user credentials"));
        }
        else
        {
            await context.Response.WriteAsJsonAsync(new { error = "invalid_api_key" });
        }
        return;
    }

    await next();
});

var repository = app.Services.GetRequiredService<AudiobookRepository>();
await repository.InitializeAsync(CancellationToken.None);
var crawlRepository = app.Services.GetRequiredService<SourceCrawlRepository>();
await crawlRepository.InitializeAsync(CancellationToken.None);
var jobRepository = app.Services.GetRequiredService<SourceJobRepository>();
await jobRepository.InitializeAsync(CancellationToken.None);
var topicRepository = app.Services.GetRequiredService<RuTrackerTopicRepository>();
await topicRepository.InitializeAsync(CancellationToken.None);
var atomRepository = app.Services.GetRequiredService<RuTrackerAtomRepository>();
await atomRepository.InitializeAsync(CancellationToken.None);
var sourceSettingsRepository = app.Services.GetRequiredService<SourceSettingsRepository>();
await sourceSettingsRepository.InitializeAsync(CancellationToken.None);
var statisticsRepository = app.Services.GetRequiredService<StatisticsRepository>();
await statisticsRepository.InitializeAsync(CancellationToken.None);

app.MapGet("/health", () => Results.Ok(new
{
    status = "ok",
    service = "audiobookred",
    version = ApplicationVersion.Value
}));

app.MapGet("/health/live", () => Results.Ok(new
{
    status = "ok",
    service = "audiobookred",
    version = ApplicationVersion.Value
}));

app.MapGet("/health/ready", async (
    DatabaseReadinessProbe readinessProbe,
    CancellationToken ct) =>
{
    var readiness = await readinessProbe.CheckAsync(ct);
    return Results.Json(
        new
        {
            status = readiness.Ready ? "ok" : "not_ready",
            service = "audiobookred",
            version = ApplicationVersion.Value,
            database = readiness.Ready ? "ok" : "unavailable"
        },
        statusCode: readiness.Ready
            ? StatusCodes.Status200OK
            : StatusCodes.Status503ServiceUnavailable);
});

app.MapGet("/api/v1/system/readiness", async (
    DatabaseReadinessProbe readinessProbe,
    CancellationToken ct) =>
{
    var readiness = await readinessProbe.CheckAsync(ct);
    return Results.Json(
        new
        {
            status = readiness.Ready ? "ok" : "not_ready",
            service = "audiobookred",
            version = ApplicationVersion.Value,
            database = readiness.Ready ? "ok" : "unavailable",
            missingMigrations = readiness.MissingMigrations,
            durationMilliseconds = readiness.DurationMilliseconds,
            error = readiness.Error
        },
        statusCode: readiness.Ready
            ? StatusCodes.Status200OK
            : StatusCodes.Status503ServiceUnavailable);
});

app.MapAudioBookRedTorznab();

app.MapPost("/api/v1/parse-title", (ParseTitleRequest request, TitleNormalizer parser) =>
    Results.Ok(parser.Parse(request.RawTitle)));

app.MapPost("/api/v1/releases", async (
    CreateAudiobookRelease request,
    AudiobookRepository repo,
    CancellationToken ct) =>
{
    var id = await repo.UpsertAsync(request, ct);
    return id is null
        ? Results.UnprocessableEntity(new
        {
            error = "magnet_required",
            message = "Новая запись без magnet-ссылки не сохраняется."
        })
        : Results.Created($"/api/v1/releases/{id}", new { id });
});

app.MapGet("/api/v1/releases", async (
    string? q,
    string? author,
    string? narrator,
    string? series,
    string? source,
    string? audioFormat,
    string? quality,
    int? year,
    string? magnet,
    string? sort,
    int limit,
    AudiobookRepository repo,
    CancellationToken ct) =>
{
    var request = new AudiobookSearchRequest(
        q, author, narrator, series, source, audioFormat, quality, year, magnet, sort, limit == 0 ? 50 : limit);
    return Results.Ok(await repo.SearchAsync(request, ct));
});

app.MapGet("/api/v1/search", async (
    string? q,
    string? author,
    string? narrator,
    string? series,
    string? source,
    string? audioFormat,
    string? quality,
    int? year,
    string? magnet,
    string? sort,
    int limit,
    AudiobookRepository repo,
    CancellationToken ct) =>
{
    var request = new AudiobookSearchRequest(
        q, author, narrator, series, source, audioFormat, quality, year, magnet, sort, limit == 0 ? 100 : limit);
    return Results.Ok(await repo.SearchFacetedAsync(request, ct));
});

app.MapGet("/api/v1/stats", async (
    StatisticsRepository stats,
    CancellationToken ct) =>
    Results.Ok(await stats.GetAsync(ct)));

app.MapPost("/api/v1/stats/refresh", async (
    StatisticsRepository stats,
    CancellationToken ct) =>
    Results.Ok(await stats.RefreshAsync(ct)));

app.MapGet("/api/v1/sources/rutracker/status", (RuTrackerClient client) =>
    Results.Ok(client.GetStatus()));

app.MapGet("/api/v1/sources/rutracker/network/status", (RuTrackerTransport transport) =>
    Results.Ok(transport.GetStatus()));

app.MapPost("/api/v1/sources/rutracker/network/probe", async (
    RuTrackerTransport transport,
    CancellationToken ct) => Results.Ok(await transport.ProbeAsync(ct)));

app.MapGet("/api/v1/sources/rutracker/categories", (RuTrackerCrawler crawler) =>
    Results.Ok(new { source = RuTrackerSourceDefinition.Key, categories = crawler.Categories }));

app.MapGet("/api/v1/sources/rutracker/crawl/status", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) => Results.Ok(await crawler.GetStatusAsync(ct)));

app.MapGet("/api/v1/sources/rutracker/settings", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) => Results.Ok(await crawler.GetSettingsAsync(ct)));

app.MapPut("/api/v1/sources/rutracker/settings", async (
    UpdateSourceRuntimeSettings update,
    RuTrackerCrawler crawler,
    CancellationToken ct) => Results.Ok(await crawler.UpdateSettingsAsync(update, ct)));

app.MapGet("/api/v1/sources/rutracker/events", async (
    int? limit,
    RuTrackerCrawler crawler,
    CancellationToken ct) => Results.Ok(await crawler.GetEventsAsync(limit ?? 20, ct)));

app.MapPost("/api/v1/sources/rutracker/maintenance", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) => Results.Ok(await crawler.RunMaintenanceAsync(ct)));

app.MapPost("/api/v1/sources/rutracker/bootstrap/discover", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    try
    {
        return Results.Ok(await crawler.DiscoverBootstrapAsync(ct));
    }
    catch (InvalidOperationException ex)
    {
        return Results.Json(
            new { error = "source_crawl_guard", message = ex.Message },
            statusCode: StatusCodes.Status503ServiceUnavailable);
    }
});

app.MapPost("/api/v1/sources/rutracker/reconcile", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    try
    {
        return Results.Ok(await crawler.DiscoverReconcileAsync(ct));
    }
    catch (InvalidOperationException ex)
    {
        return Results.Json(
            new { error = "source_crawl_guard", message = ex.Message },
            statusCode: StatusCodes.Status503ServiceUnavailable);
    }
});

app.MapGet("/api/v1/sources/rutracker/completeness", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) => Results.Ok(await crawler.GetCompletenessAsync(ct)));

app.MapPost("/api/v1/sources/rutracker/topics/retry-failed", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    var retried = await crawler.RetryTopicFailuresAsync(ct);
    return Results.Ok(new { source = RuTrackerSourceDefinition.Key, retried });
});

app.MapPost("/api/v1/sources/rutracker/bootstrap/start", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    try
    {
        return Results.Ok(await crawler.StartBootstrapAsync(ct));
    }
    catch (InvalidOperationException ex)
    {
        return Results.Json(
            new { error = "source_crawl_guard", message = ex.Message },
            statusCode: StatusCodes.Status503ServiceUnavailable);
    }
});

// Совместимость со старым bootstrap/tick: теперь это один короткий queue worker.
app.MapPost("/api/v1/sources/rutracker/bootstrap/tick", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) => Results.Ok(await crawler.WorkAsync(1, ct)));

app.MapPost("/api/v1/sources/rutracker/work", async (
    int? limit,
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    try
    {
        return Results.Ok(await crawler.WorkAsync(limit, ct));
    }
    catch (InvalidOperationException ex)
    {
        return Results.Json(
            new { error = "source_crawl_guard", message = ex.Message },
            statusCode: StatusCodes.Status503ServiceUnavailable);
    }
});

app.MapPost("/api/v1/sources/rutracker/bootstrap/pause", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    await crawler.PauseBootstrapAsync(ct);
    return Results.Ok(new { source = RuTrackerSourceDefinition.Key, paused = true });
});

app.MapPost("/api/v1/sources/rutracker/bootstrap/resume", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    await crawler.ResumeBootstrapAsync(ct);
    return Results.Ok(new { source = RuTrackerSourceDefinition.Key, paused = false });
});

app.MapPost("/api/v1/sources/rutracker/bootstrap/reset", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    try
    {
        await crawler.ResetBootstrapAsync(ct);
        return Results.Ok(new { source = RuTrackerSourceDefinition.Key, reset = true });
    }
    catch (InvalidOperationException ex)
    {
        return Results.Conflict(new { error = "source_crawl_busy", message = ex.Message });
    }
});

app.MapPost("/api/v1/sources/rutracker/incremental/enqueue", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    try
    {
        return Results.Ok(await crawler.EnqueueIncrementalAsync(ct));
    }
    catch (InvalidOperationException ex)
    {
        return Results.Json(
            new { error = "source_crawl_guard", message = ex.Message },
            statusCode: StatusCodes.Status503ServiceUnavailable);
    }
});

// Старый cron endpoint теперь только ставит короткие задачи в очередь.
app.MapPost("/api/v1/sources/rutracker/incremental/run", async (
    RuTrackerCrawler crawler,
    CancellationToken ct) => Results.Ok(await crawler.EnqueueIncrementalAsync(ct)));

app.MapPost("/api/v1/sources/rutracker/jobs/retry-failed", async (
    string? mode,
    RuTrackerCrawler crawler,
    CancellationToken ct) =>
{
    try
    {
        var retried = await crawler.RetryFailedAsync(mode, ct);
        return Results.Ok(new { source = RuTrackerSourceDefinition.Key, mode, retried });
    }
    catch (ArgumentException ex)
    {
        return Results.BadRequest(new { error = "invalid_mode", message = ex.Message });
    }
});

app.MapPost("/api/v1/sources/rutracker/import", () =>
    Results.Json(
        new
        {
            error = "legacy_endpoint_removed",
            replacement = "/api/v1/sources/rutracker/incremental/enqueue",
            message = "Прямой metadata-only import отключён. Используйте очередь crawler."
        },
        statusCode: StatusCodes.Status410Gone));

app.MapPost("/api/v1/sources/rutracker/import-html", () =>
    Results.Json(
        new
        {
            error = "legacy_endpoint_removed",
            replacement = "/api/v1/sources/rutracker/bootstrap/discover",
            message = "HTML import отключён: он не создавал полноценные записи без magnet."
        },
        statusCode: StatusCodes.Status410Gone));

app.MapGet("/api/v1/sources/rutracker/atom/status", (
    RuTrackerAtomClient client,
    RuTrackerAtomState state) => Results.Ok(state.Snapshot(client)));

app.MapPost("/api/v1/sources/rutracker/atom/import", async (
    RuTrackerAtomImportRequest request,
    RuTrackerAtomClient client,
    RuTrackerAtomImporter importer,
    CancellationToken ct) =>
{
    var forumId = request.ForumId ?? client.ForumIds.First();
    var maxEntries = Math.Clamp(request.MaxEntries ?? client.MaxEntries, 1, 100);
    try
    {
        return Results.Ok(await importer.ImportAsync(forumId, maxEntries, ct));
    }
    catch (InvalidOperationException ex)
    {
        return Results.Conflict(new { error = "rutracker_atom_busy", message = ex.Message });
    }
    catch (HttpRequestException ex)
    {
        return Results.Json(
            new { error = "rutracker_atom_http_error", message = ex.Message },
            statusCode: StatusCodes.Status502BadGateway);
    }
    catch (InvalidDataException ex)
    {
        return Results.Json(
            new { error = "rutracker_atom_xml_error", message = ex.Message },
            statusCode: StatusCodes.Status502BadGateway);
    }
    catch (ArgumentException ex)
    {
        return Results.BadRequest(new { error = "invalid_request", message = ex.Message });
    }
});

app.MapGet("/api/v1/sources/rutracker/magnets/status", () =>
    Results.Json(
        new
        {
            error = "legacy_endpoint_removed",
            replacement = "/api/v1/sources/rutracker/crawl/status",
            message = "Legacy Magnet worker не используется. Magnet получает основной topic pipeline."
        },
        statusCode: StatusCodes.Status410Gone));

app.MapPost("/api/v1/sources/rutracker/magnets/import", () =>
    Results.Json(
        new
        {
            error = "legacy_endpoint_removed",
            replacement = "/api/v1/sources/rutracker/work",
            message = "Legacy Magnet import отключён. Используйте основной worker очереди."
        },
        statusCode: StatusCodes.Status410Gone));

app.MapPost("/api/v1/sources/rutracker/magnets/reset-failures", () =>
    Results.Json(
        new
        {
            error = "legacy_endpoint_removed",
            replacement = "/api/v1/sources/rutracker/topics/retry-failed",
            message = "Повтор ошибок magnet выполняется через очередь тем."
        },
        statusCode: StatusCodes.Status410Gone));

app.Run();

public sealed record ParseTitleRequest(string RawTitle);
