using System.Collections.Concurrent;
using System.Diagnostics;
using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;
using AudioBookRed.Api.Sources;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerCrawler(
    RuTrackerSourceDefinition definition,
    RuTrackerListingClient listingClient,
    RuTrackerDetailProcessor detailProcessor,
    SourceCrawlRepository crawlRepository,
    SourceJobRepository jobRepository,
    RuTrackerTopicRepository topicRepository,
    SourceSettingsRepository settingsRepository,
    StatisticsRepository statisticsRepository,
    CrawlerResourceGuard resourceGuard,
    ILogger<RuTrackerCrawler> logger) : ISourceCrawler
{
    private readonly SemaphoreSlim _adminLock = new(1, 1);

    public string SourceKey => RuTrackerSourceDefinition.Key;

    public IReadOnlyList<int> Categories => definition.Categories;

    public Task<SourceBootstrapDiscoveryResult> StartBootstrapAsync(CancellationToken ct) =>
        DiscoverBootstrapAsync(ct);

    public async Task<SourceBootstrapDiscoveryResult> DiscoverBootstrapAsync(CancellationToken ct)
    {
        var (ordered, errors) = await DiscoverPagesAsync(ct);
        var (run, jobsAdded) = await jobRepository.CreateOrResumeBootstrapAsync(
            RuTrackerSourceDefinition.Key,
            ordered,
            ct);
        var queue = await jobRepository.GetQueueSummaryAsync(
            RuTrackerSourceDefinition.Key,
            "bootstrap",
            ct);
        var pageCount = ordered.Values.Sum();
        var message = errors.Count == 0
            ? $"Каталог обнаружен: {ordered.Count} категорий, {pageCount} страниц; в очередь добавлено {jobsAdded}."
            : $"Обнаружено {ordered.Count} из {definition.Categories.Count} категорий; ошибки будут повторены следующим discover.";

        return new SourceBootstrapDiscoveryResult(
            RuTrackerSourceDefinition.Key,
            run.Id,
            ordered.Count,
            errors.Count,
            pageCount,
            jobsAdded,
            queue,
            errors,
            message);
    }

    public async Task<SourcePageMapResult> UpdatePageMapAsync(CancellationToken ct)
    {
        var (ordered, errors) = await DiscoverPagesAsync(ct);
        await crawlRepository.UpdateDiscoveredPageMapAsync(
            RuTrackerSourceDefinition.Key,
            ordered,
            ct);

        var pageCount = ordered.Values.Sum();
        return new SourcePageMapResult(
            RuTrackerSourceDefinition.Key,
            ordered.Count,
            errors.Count,
            pageCount,
            errors,
            errors.Count == 0
                ? $"Карта страниц обновлена: {ordered.Count} категорий, {pageCount} страниц."
                : $"Карта страниц обновлена частично: {ordered.Count} категорий, ошибок {errors.Count}.");
    }

    public async Task<SourceBootstrapDiscoveryResult> DiscoverReconcileAsync(CancellationToken ct)
    {
        var (ordered, errors) = await DiscoverPagesAsync(ct);
        var runKey = $"reconcile-{DateTimeOffset.UtcNow:yyyyMMdd-HHmmss}";
        var (run, jobsAdded) = await jobRepository.CreateReconcileRunAsync(
            RuTrackerSourceDefinition.Key,
            ordered,
            runKey,
            ct);
        var queue = await jobRepository.GetQueueSummaryAsync(
            RuTrackerSourceDefinition.Key,
            "reconcile",
            ct);
        var pageCount = ordered.Values.Sum();

        return new SourceBootstrapDiscoveryResult(
            RuTrackerSourceDefinition.Key,
            run.Id,
            ordered.Count,
            errors.Count,
            pageCount,
            jobsAdded,
            queue,
            errors,
            $"Reconcile поставлен в очередь: {ordered.Count} категорий, {pageCount} страниц. Детали будут загружены только для отсутствующих или незавершённых topic_id.");
    }

    public async Task<SourceRunEnqueueResult> EnqueueIncrementalAsync(CancellationToken ct)
    {
        resourceGuard.EnsureEnoughDiskSpace();
        var settings = await GetEnabledSettingsAsync(ct);
        await crawlRepository.EnsureSourceAsync(
            RuTrackerSourceDefinition.Key,
            definition.Categories,
            ct);

        var now = DateTimeOffset.UtcNow;
        var runKey = $"{now:yyyyMMdd-HH}";
        var (run, jobsAdded) = await jobRepository.CreateIncrementalRunAsync(
            RuTrackerSourceDefinition.Key,
            definition.Categories,
            settings.IncrementalPages,
            runKey,
            ct);
        var queue = await jobRepository.GetQueueSummaryAsync(
            RuTrackerSourceDefinition.Key,
            "incremental",
            ct);

        return new SourceRunEnqueueResult(
            RuTrackerSourceDefinition.Key,
            "incremental",
            run.Id,
            run.RunKey,
            run.Status,
            jobsAdded,
            queue,
            jobsAdded > 0
                ? $"Incremental поставлен в очередь: {definition.Categories.Count} категорий, до {settings.IncrementalPages} страниц на категорию."
                : "Incremental для текущего часового окна уже был поставлен в очередь.");
    }

    public async Task<SourceWorkerResult> WorkAsync(int? requestedLimit, CancellationToken ct)
    {
        var stopwatch = Stopwatch.StartNew();
        resourceGuard.EnsureEnoughDiskSpace();
        var settings = await GetEnabledSettingsAsync(ct);
        await crawlRepository.EnsureSourceAsync(
            RuTrackerSourceDefinition.Key,
            definition.Categories,
            ct);

        var limit = Math.Clamp(requestedLimit ?? settings.WorkerJobLimit, 1, 16);
        var jobs = await jobRepository.ClaimJobsAsync(
            RuTrackerSourceDefinition.Key,
            limit,
            definition.WorkerLeaseMinutes,
            ct);

        var results = new ConcurrentBag<SourceJobResult>();
        if (jobs.Count > 0)
        {
            await Parallel.ForEachAsync(
                jobs,
                new ParallelOptions
                {
                    MaxDegreeOfParallelism = settings.PageConcurrency,
                    CancellationToken = ct
                },
                async (job, token) => results.Add(await ProcessJobAsync(job, settings, token)));
        }

        // Topic jobs живут отдельно от page jobs. Даже когда страниц в очереди
        // больше нет, минутный worker продолжает добирать отдельные темы,
        // упавшие по HTTP/Cloudflare или оставшиеся после перезапуска контейнера.
        var topicDrainLimit = jobs.Count == 0 ? limit * 50 : limit * 10;
        var topicDrain = await detailProcessor.DrainPendingTopicsAsync(topicDrainLimit, ct);

        await jobRepository.PruneAsync(RuTrackerSourceDefinition.Key, ct);
        stopwatch.Stop();
        var ordered = results.OrderBy(result => result.JobId).ToArray();
        return new SourceWorkerResult(
            RuTrackerSourceDefinition.Key,
            jobs.Count,
            ordered.Count(result => result.Status == "completed"),
            ordered.Count(result => result.Status == "retry"),
            ordered.Count(result => result.Status == "failed"),
            stopwatch.Elapsed,
            await jobRepository.GetQueueSummaryAsync(
                RuTrackerSourceDefinition.Key,
                null,
                ct),
            await topicRepository.GetSummaryAsync(RuTrackerSourceDefinition.Key, ct),
            topicDrain.Details,
            ordered);
    }

    private async Task<SourceJobResult> ProcessJobAsync(
        SourceCrawlJob job,
        SourceRuntimeSettings settings,
        CancellationToken ct)
    {
        if (job.Page > 1)
        {
            var completedBoundaryJob = await TryCompleteKnownOutOfRangeAsync(job, ct);
            if (completedBoundaryJob is not null)
                return completedBoundaryJob;
        }

        try
        {
            resourceGuard.EnsureEnoughDiskSpace();
            var listing = await listingClient.FetchPageAsync(job.CategoryId, job.Page, ct);
            var imported = await detailProcessor.ImportListingsAsync(
                listing.Items,
                job.CategoryId,
                job.Page,
                ct);

            await jobRepository.CompleteJobAsync(
                job,
                listing,
                imported,
                settings.IncrementalPages,
                ct);
            logger.LogInformation(
                "RuTracker {Mode} job {JobId}: category {CategoryId}, page {Page}, received {Received}, inserted {Inserted}, topic failed {Failed}",
                job.Mode,
                job.Id,
                job.CategoryId,
                job.Page,
                listing.Items.Count,
                imported.Inserted,
                imported.Details.Failed);

            return new SourceJobResult(
                job.Id,
                job.Mode,
                job.CategoryId,
                job.Page,
                "completed",
                listing.Items.Count,
                imported.Inserted,
                imported.Changed,
                imported.Details,
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
                var completedBoundaryJob = await TryCompleteKnownOutOfRangeAsync(job, ct);
                if (completedBoundaryJob is not null)
                    return completedBoundaryJob;
            }

            return await RecordJobFailureAsync(job, settings, ex, ct);
        }
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
        logger.LogInformation(
            "RuTracker {Mode} job {JobId}: category {CategoryId}, page {Page} is outside known catalog boundary {LastPage}",
            job.Mode,
            job.Id,
            job.CategoryId,
            job.Page,
            lastPage);

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

    private async Task<SourceJobResult> RecordJobFailureAsync(
        SourceCrawlJob job,
        SourceRuntimeSettings settings,
        Exception exception,
        CancellationToken ct)
    {
        logger.LogWarning(
            exception,
            "RuTracker {Mode} job {JobId} failed: category {CategoryId}, page {Page}",
            job.Mode,
            job.Id,
            job.CategoryId,
            job.Page);
        var status = await jobRepository.FailJobAsync(
            job,
            exception.Message,
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
            exception.Message);
    }

    public async Task PauseBootstrapAsync(CancellationToken ct)
    {
        await crawlRepository.EnsureSourceAsync(
            RuTrackerSourceDefinition.Key,
            definition.Categories,
            ct);
        await crawlRepository.SetBootstrapPausedAsync(RuTrackerSourceDefinition.Key, true, ct);
        await jobRepository.AddEventAsync(
            RuTrackerSourceDefinition.Key,
            "paused",
            "Bootstrap приостановлен.",
            "bootstrap",
            ct);
    }

    public async Task ResumeBootstrapAsync(CancellationToken ct)
    {
        await crawlRepository.EnsureSourceAsync(
            RuTrackerSourceDefinition.Key,
            definition.Categories,
            ct);
        await crawlRepository.SetBootstrapPausedAsync(RuTrackerSourceDefinition.Key, false, ct);
        await jobRepository.AddEventAsync(
            RuTrackerSourceDefinition.Key,
            "resumed",
            "Bootstrap продолжен.",
            "bootstrap",
            ct);
    }

    public async Task ResetBootstrapAsync(CancellationToken ct)
    {
        if (!await _adminLock.WaitAsync(0, ct))
            throw new InvalidOperationException("Операция управления RuTracker уже выполняется.");

        try
        {
            await crawlRepository.EnsureSourceAsync(
                RuTrackerSourceDefinition.Key,
                definition.Categories,
                ct);
            if (await jobRepository.HasRunningJobsAsync(RuTrackerSourceDefinition.Key, ct))
                throw new InvalidOperationException("Нельзя сбросить bootstrap, пока worker обрабатывает страницу.");

            await jobRepository.ResetBootstrapAsync(RuTrackerSourceDefinition.Key, ct);
            await crawlRepository.ResetBootstrapAsync(RuTrackerSourceDefinition.Key, ct);
            await jobRepository.AddEventAsync(
                RuTrackerSourceDefinition.Key,
                "reset",
                "Прогресс bootstrap сброшен.",
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

        var retried = await jobRepository.RetryFailedAsync(
            RuTrackerSourceDefinition.Key,
            normalized,
            ct);
        if (retried > 0)
        {
            await jobRepository.AddEventAsync(
                RuTrackerSourceDefinition.Key,
                "retried",
                $"Возвращено page jobs в очередь: {retried}.",
                normalized,
                ct);
        }
        return retried;
    }

    public Task<int> RetryTopicFailuresAsync(CancellationToken ct) =>
        topicRepository.RetryFailedAsync(RuTrackerSourceDefinition.Key, ct);

    public async Task<SourceMetadataReparseResult> EnqueueMetadataReparseAsync(
        SourceMetadataReparseRequest request,
        CancellationToken ct)
    {
        resourceGuard.EnsureEnoughDiskSpace();
        _ = await GetEnabledSettingsAsync(ct);
        var topicIds = SourceMetadataReparsePolicy.NormalizeTopicIds(
            request.TopicIds);

        var result = await topicRepository.EnqueueMetadataReparseAsync(
            RuTrackerSourceDefinition.Key,
            topicIds,
            RuTrackerTopicMetadataParser.CurrentParserVersion,
            request.Force,
            ct);

        if (result.Queued > 0)
        {
            await jobRepository.AddEventAsync(
                RuTrackerSourceDefinition.Key,
                "metadata_reparse_enqueued",
                $"Точечный reparse: запрошено {result.Requested}, " +
                $"поставлено {result.Queued}, актуальных {result.AlreadyCurrent}, " +
                $"занято {result.Busy}, не найдено {result.Missing}.",
                null,
                ct);
        }

        return result;
    }

    public async Task<SourceMetadataReparseResult> EnqueueMetadataBackfillAsync(
        int? requestedLimit,
        CancellationToken ct)
    {
        resourceGuard.EnsureEnoughDiskSpace();
        _ = await GetEnabledSettingsAsync(ct);
        var limit = SourceMetadataReparsePolicy.NormalizeBatchLimit(
            requestedLimit);

        var result = await topicRepository.EnqueueMetadataBackfillAsync(
            RuTrackerSourceDefinition.Key,
            limit,
            RuTrackerTopicMetadataParser.CurrentParserVersion,
            ct);

        if (result.Queued > 0)
        {
            await jobRepository.AddEventAsync(
                RuTrackerSourceDefinition.Key,
                "metadata_backfill_enqueued",
                $"Metadata backfill: предел {limit}, поставлено {result.Queued}.",
                null,
                ct);
        }

        return result;
    }

    public Task<SourceMetadataStatus> GetMetadataStatusAsync(
        CancellationToken ct) =>
        topicRepository.GetMetadataStatusAsync(
            RuTrackerSourceDefinition.Key,
            RuTrackerTopicMetadataParser.CurrentParserVersion,
            ct);

    public Task<RuTrackerCompletenessStatus> GetCompletenessAsync(CancellationToken ct) =>
        topicRepository.GetCompletenessAsync(RuTrackerSourceDefinition.Key, ct);

    public Task<SourceRuntimeSettings> GetSettingsAsync(CancellationToken ct) =>
        settingsRepository.GetAsync(RuTrackerSourceDefinition.Key, ct);

    public async Task<SourceRuntimeSettings> UpdateSettingsAsync(
        UpdateSourceRuntimeSettings update,
        CancellationToken ct)
    {
        var settings = await settingsRepository.UpdateAsync(RuTrackerSourceDefinition.Key, update, ct);
        await jobRepository.AddEventAsync(
            RuTrackerSourceDefinition.Key,
            "settings",
            "Runtime-настройки источника изменены.",
            null,
            ct);
        return settings;
    }

    public Task<IReadOnlyList<SourceJobEvent>> GetEventsAsync(int limit, CancellationToken ct) =>
        jobRepository.GetRecentEventsAsync(RuTrackerSourceDefinition.Key, limit, ct);

    public async Task<object> RunMaintenanceAsync(CancellationToken ct)
    {
        await jobRepository.PruneAsync(RuTrackerSourceDefinition.Key, ct);
        var stats = await statisticsRepository.RefreshAsync(ct);
        var completeness = await topicRepository.GetCompletenessAsync(RuTrackerSourceDefinition.Key, ct);
        return new
        {
            source = RuTrackerSourceDefinition.Key,
            pruned = true,
            statisticsRefreshedAt = stats.RefreshedAt,
            completeness
        };
    }

    public async Task<RuTrackerQueuedCrawlStatus> GetStatusAsync(CancellationToken ct)
    {
        await crawlRepository.EnsureSourceAsync(
            RuTrackerSourceDefinition.Key,
            definition.Categories,
            ct);
        var (control, states) = await crawlRepository.GetStatusAsync(
            RuTrackerSourceDefinition.Key,
            ct);
        var settings = await settingsRepository.GetAsync(RuTrackerSourceDefinition.Key, ct);
        var bootstrapQueue = await jobRepository.GetQueueSummaryAsync(
            RuTrackerSourceDefinition.Key,
            "bootstrap",
            ct);
        var incrementalQueue = await jobRepository.GetQueueSummaryAsync(
            RuTrackerSourceDefinition.Key,
            "incremental",
            ct);
        var reconcileQueue = await jobRepository.GetQueueSummaryAsync(
            RuTrackerSourceDefinition.Key,
            "reconcile",
            ct);
        var topicQueue = await topicRepository.GetSummaryAsync(RuTrackerSourceDefinition.Key, ct);
        var completeness = await topicRepository.GetCompletenessAsync(RuTrackerSourceDefinition.Key, ct);
        var recentRuns = await jobRepository.GetRecentRunsAsync(
            RuTrackerSourceDefinition.Key,
            10,
            ct);
        var recentEvents = await jobRepository.GetRecentEventsAsync(
            RuTrackerSourceDefinition.Key,
            20,
            ct);
        var completed = states.Count(state => state.BootstrapCompleted);

        return new RuTrackerQueuedCrawlStatus(
            RuTrackerSourceDefinition.Key,
            definition.Categories,
            control.BootstrapPaused,
            completed == definition.Categories.Count,
            completed,
            definition.Categories.Count,
            control.BootstrapStartedAt,
            control.BootstrapCompletedAt,
            control.LastIncrementalStartedAt,
            control.LastIncrementalCompletedAt,
            control.LastError,
            settings,
            bootstrapQueue,
            incrementalQueue,
            reconcileQueue,
            topicQueue,
            completeness,
            recentRuns,
            recentEvents,
            states);
    }

    private async Task<(IReadOnlyDictionary<int, int> Pages, IReadOnlyList<string> Errors)> DiscoverPagesAsync(
        CancellationToken ct)
    {
        resourceGuard.EnsureEnoughDiskSpace();
        var settings = await GetEnabledSettingsAsync(ct);
        await crawlRepository.EnsureSourceAsync(
            RuTrackerSourceDefinition.Key,
            definition.Categories,
            ct);

        var discovered = new ConcurrentDictionary<int, int>();
        var errors = new ConcurrentBag<string>();
        await Parallel.ForEachAsync(
            definition.Categories,
            new ParallelOptions
            {
                MaxDegreeOfParallelism = Math.Clamp(settings.PageConcurrency, 1, 6),
                CancellationToken = ct
            },
            async (categoryId, token) =>
            {
                try
                {
                    var firstPage = await listingClient.FetchPageAsync(categoryId, 1, token);
                    discovered[categoryId] = Math.Max(1, firstPage.TotalPages);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    var message = $"Категория {categoryId}: {ex.Message}";
                    errors.Add(message);
                    await crawlRepository.RecordCategoryErrorAsync(
                        RuTrackerSourceDefinition.Key,
                        categoryId,
                        ex.Message,
                        token);
                }
            });

        if (discovered.IsEmpty)
            throw new InvalidOperationException("Не удалось определить страницы ни одной категории RuTracker.");

        var ordered = definition.Categories
            .Where(discovered.ContainsKey)
            .ToDictionary(category => category, category => discovered[category]);
        return (ordered, errors.OrderBy(value => value).Take(30).ToArray());
    }

    private async Task<SourceRuntimeSettings> GetEnabledSettingsAsync(CancellationToken ct)
    {
        var settings = await settingsRepository.GetAsync(RuTrackerSourceDefinition.Key, ct);
        if (!settings.Enabled)
            throw new InvalidOperationException("Источник rutracker отключён в runtime-настройках.");
        return settings;
    }

    async Task<object> ISourceCrawler.GetCompletenessAsync(
        CancellationToken ct) =>
        await GetCompletenessAsync(ct);

    async Task<object> ISourceCrawler.GetStatusAsync(CancellationToken ct) =>
        await GetStatusAsync(ct);

    private static DetailDrainSummary EmptyDetails() => new(0, 0, 0, 0, 0);
}
