using System.Threading.RateLimiting;
using AudioBookRed.Api.Compatibility;
using Microsoft.AspNetCore.RateLimiting;
using AudioBookRed.Api.Data;
using AudioBookRed.Api.Infrastructure;
using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;
using AudioBookRed.Api.Sources;

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
builder.Services.AddSingleton<SourceSettingsRepository>();
builder.Services.AddSingleton<StatisticsRepository>();
builder.Services.AddSingleton<RuTrackerTransport>();
builder.Services.AddSingleton<RuTrackerClient>();
builder.Services.AddSingleton<RuTrackerHtmlParser>();
builder.Services.AddSingleton<RuTrackerTopicMetadataParser>();
builder.Services.AddSingleton<RuTrackerMagnetClient>();
builder.Services.AddSingleton<RuTrackerMagnetState>();
builder.Services.AddSingleton<RuTrackerMagnetEnricher>();

// Общая граница источников. Конкретный адаптер регистрирует module и crawler
// под одним стабильным source key.
builder.Services.AddSingleton<RuTrackerSourceDefinition>();
builder.Services.AddSingleton<ISourceModule>(services =>
    services.GetRequiredService<RuTrackerSourceDefinition>());
builder.Services.AddSingleton<CrawlerResourceGuard>();
builder.Services.AddSingleton<RuTrackerListingClient>();
builder.Services.AddSingleton<RuTrackerDetailProcessor>();
builder.Services.AddSingleton<RuTrackerCrawler>();
builder.Services.AddSingleton<ISourceCrawler>(services =>
    services.GetRequiredService<RuTrackerCrawler>());

// Rutor exposes magnet/infohash in its paged Books listing and enriches
// audiobook metadata from detail pages through the shared topic queue.
builder.Services.AddSingleton<RutorSourceDefinition>();
builder.Services.AddSingleton<ISourceModule>(services =>
    services.GetRequiredService<RutorSourceDefinition>());
builder.Services.AddSingleton<RutorTransport>();
builder.Services.AddSingleton<RutorHtmlParser>();
builder.Services.AddSingleton<RutorDetailParser>();
builder.Services.AddSingleton<RutorListingClient>();
builder.Services.AddSingleton<RutorDetailProcessor>();
builder.Services.AddSingleton<RutorCrawler>();
builder.Services.AddSingleton<ISourceCrawler>(services =>
    services.GetRequiredService<RutorCrawler>());

builder.Services.AddSingleton<SourceRegistry>();

var app = builder.Build();

// Validate module/crawler registrations before the process starts listening.
_ = app.Services.ValidateSourceRegistry();

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

app.MapSourceCrawlerEndpoints();

// RuTracker-specific compatibility and auxiliary endpoints remain explicit.
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
