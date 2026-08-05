using System.Collections.Concurrent;
using System.Diagnostics;
using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;
using AudioBookRed.Api.Sources;
using Npgsql;

namespace AudioBookRed.Api.Services;

public sealed class RutorCrawler(
    RutorSourceDefinition definition,
    RutorListingClient listingClient,
    RutorTransport transport,
    SourceCrawlRepository crawlRepository,
    SourceJobRepository jobRepository,
    SourceSettingsRepository settingsRepository,
    AudiobookRepository audiobookRepository,
    StatisticsRepository statisticsRepository,
    CrawlerResourceGuard resourceGuard,
    ILogger<RutorCrawler> logger) : ISourceCrawler
{
    private readonly SemaphoreSlim _adminLock = new(1, 1);

    public string SourceKey => RutorSourceDefinition.Key;
    public IReadOnlyList<int> Categories => definition.Categories;

    public Task<SourceBootstrapDiscoveryResult> StartBootstrapAsync(CancellationToken ct) =>
        DiscoverBootstrapAsync(ct);

    public async Task<SourceBootstrapDiscoveryResult> DiscoverBootstrapAsync(CancellationToken ct)
    {
        var (pages, errors) = await DiscoverPagesAsync("bootstrap", ct);
        var (run, jobsAdded) = await jobRepository.CreateOrResumeBootstrapAsync(
            SourceKey,
            pages,
            ct);
        var queue = await jobRepository.GetQueueSummaryAsync(SourceKey, "bootstrap", ct);
        var pageCount = pages.Values.Sum();

        return new SourceBootstrapDiscoveryResult(
            SourceKey,
            run.Id,
            pages.Count,
            errors.Count,
            pageCount,
            jobsAdded,
            queue,
            errors,
            errors.Count == 0
                ? $"Rutor обнаружен: {pageCount} страниц; добавлено заданий {jobsAdded}."
                : "Rutor discovery завершён с ошибкой; повторите операцию после проверки зеркал.");
    }

    public async Task<SourcePageMapResult> UpdatePageMapAsync(CancellationToken ct)
    {
        var stopwatch = Stopwatch.StartNew();
        logger.LogInformation("Rutor page-map started");
        var (pages, errors) = await DiscoverPagesAsync("page-map", ct);
        await crawlRepository.UpdateDiscoveredPageMapAsync(SourceKey, pages, ct);
        stopwatch.Stop();

        var pageCount = pages.Values.Sum();
        logger.LogInformation(
            "Rutor page-map completed: pages {PageCount}, failed {Failed}, elapsedMs {ElapsedMs}",
            pageCount,
            errors.Count,
            stopwatch.ElapsedMilliseconds);

        return new SourcePageMapResult(
            SourceKey,
            pages.Count,
            errors.Count,
            pageCount,
            errors,
            errors.Count == 0
                ? $"Карта Rutor обновлена: {pageCount} страниц."
                : "Карта Rutor не обновлена полностью.");
    }

    public async Task<SourceBootstrapDiscoveryResult> DiscoverReconcileAsync(CancellationToken ct)
    {
        var stopwatch = Stopwatch.StartNew();
        logger.LogInformation("Rutor reconcile started: stage page-map");
        var (pages, errors) = await DiscoverPagesAsync("reconcile", ct);
        var pageCount = pages.Values.Sum();
        var runKey = $"reconcile-{DateTimeOffset.UtcNow:yyyyMMdd-HHmmss}";
        var (run, jobsAdded) = await jobRepository.CreateReconcileRunAsync(
            SourceKey,
            pages,
            runKey,
            ct);
        var queue = await jobRepository.GetQueueSummaryAsync(SourceKey, "reconcile", ct);
        stopwatch.Stop();

        logger.LogInformation(
            "Rutor reconcile enqueue completed: runId {RunId}, pages {PageCount}, jobsAdded {JobsAdded}, elapsedMs {ElapsedMs}",
            run.Id,
            pageCount,
            jobsAdded,
            stopwatch.ElapsedMilliseconds);

        return new SourceBootstrapDiscoveryResult(
            SourceKey,
            run.Id,
            pages.Count,
            errors.Count,
            pageCount,
            jobsAdded,
            queue,
            errors,
            $"Rutor reconcile поставлен в очередь: {pageCount} страниц.");
    }

    public async Task<SourceRunEnqueueResult> EnqueueIncrementalAsync(CancellationToken ct)
    {
        resourceGuard.EnsureEnoughDiskSpace();
        var settings = await GetEnabledSettingsAsync(ct);
        await crawlRepository.EnsureSourceAsync(SourceKey, Categories, ct);

        var runKey = $"{DateTimeOffset.UtcNow:yyyyMMdd-HH}";
        logger.LogInformation(
            "Rutor incremental enqueue started: runKey {RunKey}, pages {Pages}",
            runKey,
            settings.IncrementalPages);

        var (run, jobsAdded) = await jobRepository.CreateIncrementalRunAsync(
            SourceKey,
            Categories,
            settings.IncrementalPages,
            runKey,
            ct);
        var queue = await jobRepository.GetQueueSummaryAsync(SourceKey, "incremental", ct);

        logger.LogInformation(
            "Rutor incremental enqueue completed: runId {RunId}, jobsAdded {JobsAdded}, pending {Pending}, retry {Retry}",
            run.Id,
            jobsAdded,
            queue.Pending,
            queue.Retry);

        return new SourceRunEnqueueResult(
            SourceKey,
            "incremental",
            run.Id,
            run.RunKey,
            run.Status,
            jobsAdded,
            queue,
            jobsAdded > 0
                ? $"Rutor incremental поставлен в очередь: {settings.IncrementalPages} страниц."
                : "Rutor incremental для текущего часа уже поставлен в очередь.");
    }

    public async Task<SourceWorkerResult> WorkAsync(int? requestedLimit, CancellationToken ct)
    {
        var stopwatch = Stopwatch.StartNew();
        resourceGuard.EnsureEnoughDiskSpace();
        var settings = await GetEnabledSettingsAsync(ct);
        await crawlRepository.EnsureSourceAsync(SourceKey, Categories, ct);

        var limit = Math.Clamp(requestedLimit ?? settings.WorkerJobLimit, 1, 16);
        var jobs = await jobRepository.ClaimJobsAsync(
            SourceKey,
            limit,
            definition.WorkerLeaseMinutes,
            ct);
        var concurrency = Math.Clamp(settings.PageConcurrency, 1, 4);
        var results = new ConcurrentBag<SourceJobResult>();

        if (jobs.Count > 0)
        {
            logger.LogInformation(
                "Rutor worker batch started: jobs {Jobs}, concurrency {Concurrency}, modes {Modes}",
                jobs.Count,
                concurrency,
                string.Join(",", jobs.Select(job => job.Mode).Distinct().OrderBy(value => value)));

            await Parallel.ForEachAsync(
                jobs,
                new ParallelOptions
                {
                    MaxDegreeOfParallelism = concurrency,
                    CancellationToken = ct
                },
                async (job, token) => results.Add(await ProcessJobAsync(job, settings, token)));
        }

        await jobRepository.PruneAsync(SourceKey, ct);
        stopwatch.Stop();
        var ordered = results.OrderBy(result => result.JobId).ToArray();

        if (ordered.Length > 0)
        {
            logger.LogInformation(
                "Rutor worker batch completed: jobs {Jobs}, completed {Completed}, retry {Retry}, failed {Failed}, received {Received}, inserted {Inserted}, changed {Changed}, elapsedMs {ElapsedMs}, pagesPerMinute {PagesPerMinute:F2}",
                ordered.Length,
                ordered.Count(result => result.Status == "completed"),
                ordered.Count(result => result.Status == "retry"),
                ordered.Count(result => result.Status == "failed"),
                ordered.Sum(result => result.Received),
                ordered.Sum(result => result.Inserted),
                ordered.Sum(result => result.Changed),
                stopwatch.ElapsedMilliseconds,
                RatePerMinute(ordered.Length, stopwatch.Elapsed));
        }

        return new SourceWorkerResult(
            SourceKey,
            jobs.Count,
            ordered.Count(result => result.Status == "completed"),
            ordered.Count(result => result.Status == "retry"),
            ordered.Count(result => result.Status == "failed"),
            stopwatch.Elapsed,
            await jobRepository.GetQueueSummaryAsync(SourceKey, null, ct),
            EmptyTopicQueue(),
            EmptyDetails(),
            ordered);
    }

    private async Task<SourceJobResult> ProcessJobAsync(
        SourceCrawlJob job,
        SourceRuntimeSettings settings,
        CancellationToken ct)
    {
        if (job.Page > 1)
        {
            var boundary = await TryCompleteKnownOutOfRangeAsync(job, ct);
            if (boundary is not null)
                return boundary;
        }

        try
        {
            resourceGuard.EnsureEnoughDiskSpace();
            if (settings.RequestDelayMilliseconds > 0)
                await Task.Delay(settings.RequestDelayMilliseconds, ct);

            var listing = await listingClient.FetchPageAsync(job.CategoryId, job.Page, ct);
            var imported = await ImportListingsAsync(listing.Items, job.CategoryId, ct);
            await jobRepository.CompleteJobAsync(
                job,
                listing,
                imported,
                settings.IncrementalPages,
                ct);

            logger.LogInformation(
                "Rutor {Mode} job {JobId}: page {Page}, sourceRows {SourceRows}, audiobooks {Received}, inserted {Inserted}, changed {Changed}",
                job.Mode,
                job.Id,
                job.Page,
                listing.SourceRows,
                listing.Items.Count,
                imported.Inserted,
                imported.Changed);

            return new SourceJobResult(
                job.Id,
                job.Mode,
                job.CategoryId,
                job.Page,
                "completed",
                listing.Items.Count,
                imported.Inserted,
                imported.Changed,
                EmptyDetails(),
                null);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            if (job.Page > 1)
            {
                var boundary = await TryCompleteKnownOutOfRangeAsync(job, ct);
                if (boundary is not null)
                    return boundary;
            }

            logger.LogWarning(
                ex,
                "Rutor {Mode} job {JobId} failed: page {Page}",
                job.Mode,
                job.Id,
                job.Page);
            var status = await jobRepository.FailJobAsync(
                job,
                ex.Message,
                settings.MaximumAttempts,
                ct);
            return new SourceJobResult(
                job.Id,
                job.Mode,
                job.CategoryId,
                job.Page,
                status,
                0,
                0,
                0,
                EmptyDetails(),
                ex.Message);
        }
    }

    private async Task<ListingImportSummary> ImportListingsAsync(
        IReadOnlyList<RutorListingItem> items,
        int categoryId,
        CancellationToken ct)
    {
        if (items.Count == 0)
            return new ListingImportSummary(0, 0, EmptyDetails());

        var existingStates = await crawlRepository.GetExistingListingStatesAsync(
            SourceKey,
            items.Select(item => item.TopicId).ToArray(),
            ct);
        var inserted = 0;
        var changed = 0;

        foreach (var item in items)
        {
            resourceGuard.EnsureEnoughDiskSpace();
            var listingFingerprint = ListingFingerprint.ForListing(item);
            var detailFingerprint = ListingFingerprint.ForDetails(item);

            if (existingStates.TryGetValue(item.TopicId, out var existing)
                && existing.HasMagnet)
            {
                var titleChanged = !string.Equals(
                    existing.RawTitle,
                    item.Title,
                    StringComparison.Ordinal);
                var update = await crawlRepository.UpdateExistingListingAsync(
                    SourceKey,
                    item,
                    categoryId,
                    listingFingerprint,
                    existing.DetailFingerprint is null ? detailFingerprint : null,
                    ct);
                if (update?.Changed == true)
                {
                    changed++;
                    if (titleChanged)
                        await audiobookRepository.RefreshPeopleAsync(update.Id, ct);
                }
                continue;
            }

            try
            {
                var result = await crawlRepository.UpsertListingWithTopicMetadataAsync(
                    SourceKey,
                    item,
                    categoryId,
                    item.InfoHash,
                    item.MagnetUri,
                    null,
                    listingFingerprint,
                    detailFingerprint,
                    ct);
                if (result.Inserted)
                    inserted++;
                else if (result.Changed)
                    changed++;

                if (result.Inserted || result.Changed)
                    await audiobookRepository.RefreshPeopleAsync(result.Id, ct);
            }
            catch (PostgresException ex) when (ex.SqlState == PostgresErrorCodes.UniqueViolation)
            {
                logger.LogInformation(
                    "Rutor torrent {TorrentId}: duplicate infohash {InfoHash}",
                    item.TopicId,
                    item.InfoHash);
            }
        }

        return new ListingImportSummary(inserted, changed, EmptyDetails());
    }

    private async Task<SourceJobResult?> TryCompleteKnownOutOfRangeAsync(
        SourceCrawlJob job,
        CancellationToken ct)
    {
        var knownLastPage = await jobRepository.GetKnownLastPageAsync(
            job.Source,
            job.CategoryId,
            ct);
        if (!CatalogPageWindow.IsOutOfRange(job.Page, knownLastPage))
            return null;

        var lastPage = knownLastPage.GetValueOrDefault();
        await jobRepository.CompleteOutOfRangeJobAsync(job, lastPage, ct);
        return new SourceJobResult(
            job.Id,
            job.Mode,
            job.CategoryId,
            job.Page,
            "completed",
            0,
            0,
            0,
            EmptyDetails(),
            null);
    }

    public async Task PauseBootstrapAsync(CancellationToken ct)
    {
        await crawlRepository.EnsureSourceAsync(SourceKey, Categories, ct);
        await crawlRepository.SetBootstrapPausedAsync(SourceKey, true, ct);
        await jobRepository.AddEventAsync(
            SourceKey,
            "paused",
            "Rutor bootstrap приостановлен.",
            "bootstrap",
            ct);
    }

    public async Task ResumeBootstrapAsync(CancellationToken ct)
    {
        await crawlRepository.EnsureSourceAsync(SourceKey, Categories, ct);
        await crawlRepository.SetBootstrapPausedAsync(SourceKey, false, ct);
        await jobRepository.AddEventAsync(
            SourceKey,
            "resumed",
            "Rutor bootstrap продолжен.",
            "bootstrap",
            ct);
    }

    public async Task ResetBootstrapAsync(CancellationToken ct)
    {
        if (!await _adminLock.WaitAsync(0, ct))
            throw new InvalidOperationException("Операция управления Rutor уже выполняется.");

        try
        {
            await crawlRepository.EnsureSourceAsync(SourceKey, Categories, ct);
            if (await jobRepository.HasRunningJobsAsync(SourceKey, ct))
                throw new InvalidOperationException("Нельзя сбросить bootstrap во время работы worker.");

            await jobRepository.ResetBootstrapAsync(SourceKey, ct);
            await crawlRepository.ResetBootstrapAsync(SourceKey, ct);
            await jobRepository.AddEventAsync(
                SourceKey,
                "reset",
                "Rutor bootstrap сброшен.",
                "bootstrap",
                ct);
        }
        finally
        {
            _adminLock.Release();
        }
    }

    public async Task<int> RetryFailedAsync(string? mode, CancellationToken ct)
    {
        var normalized = string.IsNullOrWhiteSpace(mode) ? null : mode.Trim().ToLowerInvariant();
        if (normalized is not null and not "bootstrap" and not "incremental" and not "reconcile")
            throw new ArgumentException("mode должен быть bootstrap, incremental или reconcile.", nameof(mode));
        return await jobRepository.RetryFailedAsync(SourceKey, normalized, ct);
    }

    public Task<int> RetryTopicFailuresAsync(CancellationToken ct) => Task.FromResult(0);

    public Task<SourceMetadataReparseResult> EnqueueMetadataReparseAsync(
        SourceMetadataReparseRequest request,
        CancellationToken ct)
    {
        var ids = SourceMetadataReparsePolicy.NormalizeTopicIds(request.TopicIds);
        return Task.FromResult(new SourceMetadataReparseResult(
            SourceKey,
            "unsupported",
            0,
            ids.Length,
            0,
            0,
            0,
            0,
            ids.Length,
            0,
            ids));
    }

    public Task<SourceMetadataReparseResult> EnqueueMetadataBackfillAsync(
        int? requestedLimit,
        CancellationToken ct)
    {
        var limit = SourceMetadataReparsePolicy.NormalizeBatchLimit(requestedLimit);
        return Task.FromResult(new SourceMetadataReparseResult(
            SourceKey,
            "unsupported",
            0,
            limit,
            0,
            0,
            0,
            0,
            0,
            0,
            Array.Empty<long>()));
    }

    public async Task<SourceMetadataStatus> GetMetadataStatusAsync(CancellationToken ct)
    {
        var total = await crawlRepository.GetReleaseCountAsync(SourceKey, ct);
        return new SourceMetadataStatus(
            SourceKey,
            0,
            total,
            0,
            total,
            0,
            0,
            null,
            null,
            DateTimeOffset.UtcNow);
    }

    public async Task<object> GetCompletenessAsync(CancellationToken ct)
    {
        var imported = await crawlRepository.GetReleaseCountAsync(SourceKey, ct);
        return new
        {
            source = SourceKey,
            discovered = imported,
            imported,
            listingOnly = true,
            generatedAt = DateTimeOffset.UtcNow
        };
    }

    public Task<SourceRuntimeSettings> GetSettingsAsync(CancellationToken ct) =>
        settingsRepository.GetAsync(SourceKey, ct);

    public async Task<SourceRuntimeSettings> UpdateSettingsAsync(
        UpdateSourceRuntimeSettings update,
        CancellationToken ct)
    {
        var settings = await settingsRepository.UpdateAsync(SourceKey, update, ct);
        await jobRepository.AddEventAsync(
            SourceKey,
            "settings",
            "Runtime-настройки Rutor изменены.",
            null,
            ct);
        return settings;
    }

    public Task<IReadOnlyList<SourceJobEvent>> GetEventsAsync(int limit, CancellationToken ct) =>
        jobRepository.GetRecentEventsAsync(SourceKey, limit, ct);

    public async Task<object> RunMaintenanceAsync(CancellationToken ct)
    {
        await jobRepository.PruneAsync(SourceKey, ct);
        var stats = await statisticsRepository.RefreshAsync(ct);
        return new
        {
            source = SourceKey,
            pruned = true,
            statisticsRefreshedAt = stats.RefreshedAt
        };
    }

    public async Task<object> GetStatusAsync(CancellationToken ct)
    {
        await crawlRepository.EnsureSourceAsync(SourceKey, Categories, ct);
        var (control, states) = await crawlRepository.GetStatusAsync(SourceKey, ct);
        var settings = await settingsRepository.GetAsync(SourceKey, ct);
        var bootstrapQueue = await jobRepository.GetQueueSummaryAsync(SourceKey, "bootstrap", ct);
        var incrementalQueue = await jobRepository.GetQueueSummaryAsync(SourceKey, "incremental", ct);
        var reconcileQueue = await jobRepository.GetQueueSummaryAsync(SourceKey, "reconcile", ct);
        var recentRuns = await jobRepository.GetRecentRunsAsync(SourceKey, 10, ct);
        var recentEvents = await jobRepository.GetRecentEventsAsync(SourceKey, 20, ct);
        var releases = await crawlRepository.GetReleaseCountAsync(SourceKey, ct);

        return new
        {
            source = SourceKey,
            categories = Categories,
            listingParserVersion = RutorHtmlParser.CurrentParserVersion,
            mirrors = transport.BaseUris
                .Select(uri => uri.GetLeftPart(UriPartial.Authority))
                .ToArray(),
            control,
            settings,
            releases,
            bootstrapQueue,
            incrementalQueue,
            reconcileQueue,
            recentRuns,
            recentEvents,
            categoryStates = states
        };
    }

    private async Task<(IReadOnlyDictionary<int, int> Pages, IReadOnlyList<string> Errors)>
        DiscoverPagesAsync(string operation, CancellationToken ct)
    {
        resourceGuard.EnsureEnoughDiskSpace();
        _ = await GetEnabledSettingsAsync(ct);
        await crawlRepository.EnsureSourceAsync(SourceKey, Categories, ct);

        logger.LogInformation("Rutor {Operation} discovery started", operation);
        try
        {
            var firstPage = await listingClient.FetchPageAsync(
                RutorSourceDefinition.BooksCategoryId,
                1,
                ct);
            var pages = Math.Max(1, firstPage.TotalPages);
            logger.LogInformation(
                "Rutor {Operation} discovery completed: pages {Pages}, firstPageRows {Rows}, firstPageAudiobooks {Audiobooks}",
                operation,
                pages,
                firstPage.SourceRows,
                firstPage.Items.Count);
            return (
                new Dictionary<int, int>
                {
                    [RutorSourceDefinition.BooksCategoryId] = pages
                },
                Array.Empty<string>());
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            logger.LogWarning(ex, "Rutor {Operation} discovery failed", operation);
            throw new InvalidOperationException(
                $"Rutor {operation} discovery failed: {ex.Message}",
                ex);
        }
    }

    private async Task<SourceRuntimeSettings> GetEnabledSettingsAsync(CancellationToken ct)
    {
        var settings = await settingsRepository.GetAsync(SourceKey, ct);
        if (!settings.Enabled)
            throw new InvalidOperationException("Источник rutor отключён в runtime-настройках.");
        return settings;
    }

    private static RuTrackerTopicQueueSummary EmptyTopicQueue() =>
        new(0, 0, 0, 0, 0, 0, 0);

    private static DetailDrainSummary EmptyDetails() => new(0, 0, 0, 0, 0);

    private static double RatePerMinute(long count, TimeSpan elapsed) =>
        elapsed.TotalMinutes <= 0 ? 0 : count / elapsed.TotalMinutes;
}
