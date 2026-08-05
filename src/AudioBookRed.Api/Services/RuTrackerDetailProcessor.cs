using System.Net;
using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;
using Npgsql;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerDetailProcessor(
    RuTrackerMagnetClient client,
    SourceCrawlRepository crawlRepository,
    RuTrackerTopicRepository topicRepository,
    AudiobookRepository audiobookRepository,
    SourceSettingsRepository settingsRepository,
    RuTrackerSourceDefinition definition,
    CrawlerResourceGuard resourceGuard,
    ILogger<RuTrackerDetailProcessor> logger)
{
    public async Task<ListingImportSummary> ImportListingsAsync(
        IReadOnlyList<RuTrackerSearchItem> items,
        int categoryId,
        int page,
        CancellationToken ct)
    {
        if (items.Count == 0)
            return new ListingImportSummary(0, 0, Empty());

        _ = await GetEnabledSettingsAsync(ct);
        var existingStates = await crawlRepository.GetExistingListingStatesAsync(
            items.Select(item => item.TopicId).ToArray(),
            ct);

        var changed = 0;

        foreach (var item in items)
        {
            resourceGuard.EnsureEnoughDiskSpace();
            var listingFingerprint = ListingFingerprint.ForListing(item);
            var detailFingerprint = ListingFingerprint.ForDetails(item);
            var needsDetails = true;

            if (existingStates.TryGetValue(item.TopicId, out var existing))
            {
                var legacyDetailsMatch = existing.DetailFingerprint is null
                    && string.Equals(existing.RawTitle, item.Title, StringComparison.Ordinal)
                    && existing.SizeBytes == item.SizeBytes;
                var detailsUnchanged = string.Equals(
                        existing.DetailFingerprint,
                        detailFingerprint,
                        StringComparison.Ordinal)
                    || legacyDetailsMatch;

                var update = await crawlRepository.UpdateExistingListingAsync(
                    item,
                    categoryId,
                    listingFingerprint,
                    legacyDetailsMatch ? detailFingerprint : null,
                    ct);
                if (update?.Changed == true)
                    changed++;

                needsDetails = !existing.HasMagnet || !detailsUnchanged;
            }

            await topicRepository.RegisterDiscoveredAsync(
                item,
                categoryId,
                page,
                listingFingerprint,
                detailFingerprint,
                needsDetails,
                ct);
        }

        // Listing jobs never open viewtopic.php. Topic pages are fetched only
        // by DrainPendingTopicsAsync, which owns retry, lease and concurrency.
        return new ListingImportSummary(0, changed, Empty());
    }

    public async Task<ListingImportSummary> DrainPendingTopicsAsync(
        int requestedLimit,
        CancellationToken ct)
    {
        var settings = await GetEnabledSettingsAsync(ct);
        var jobs = await topicRepository.ClaimPendingAsync(
            RuTrackerSourceDefinition.Key,
            Math.Clamp(requestedLimit, 1, 500),
            definition.WorkerLeaseMinutes,
            ct);
        if (jobs.Count == 0)
            return new ListingImportSummary(0, 0, Empty());

        var inserted = 0;
        var changed = 0;
        var enriched = 0;
        var missing = 0;
        var failed = 0;

        await Parallel.ForEachAsync(
            jobs,
            new ParallelOptions
            {
                MaxDegreeOfParallelism = settings.DetailConcurrency,
                CancellationToken = ct
            },
            async (job, token) =>
            {
                var outcome = await ProcessClaimedTopicAsync(job, settings, token);
                if (outcome.Enriched)
                    Interlocked.Increment(ref enriched);
                if (outcome.Missing)
                    Interlocked.Increment(ref missing);
                if (outcome.Failed)
                    Interlocked.Increment(ref failed);
                if (outcome.Inserted)
                    Interlocked.Increment(ref inserted);
                else if (outcome.Changed)
                    Interlocked.Increment(ref changed);
            });

        var batches = (jobs.Count + settings.DetailConcurrency - 1) / settings.DetailConcurrency;
        return new ListingImportSummary(
            inserted,
            changed,
            new DetailDrainSummary(batches, jobs.Count, enriched, missing, failed));
    }

    private async Task<TopicOutcome> ProcessClaimedTopicAsync(
        RuTrackerTopicJob job,
        SourceRuntimeSettings settings,
        CancellationToken ct)
    {
        resourceGuard.EnsureEnoughDiskSpace();
        await DelayBeforeTopicAsync(settings.RequestDelayMilliseconds, ct);

        var item = new RuTrackerSearchItem(
            job.TopicId,
            job.Title,
            string.Empty,
            job.TopicUrl,
            job.SizeBytes,
            job.Seeders,
            job.Leechers);

        RuTrackerMagnetValue? magnet;
        try
        {
            magnet = await FetchWithRetryAsync(item, ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            var status = await topicRepository.MarkFailureAsync(
                job,
                ex.Message,
                settings.MaximumAttempts,
                ct);
            logger.LogWarning(
                ex,
                "RuTracker topic {TopicId} переведён в {Status}",
                job.TopicId,
                status);
            return TopicOutcome.Failure;
        }

        if (magnet is null)
        {
            await topicRepository.MarkMissingMagnetAsync(job, ct);
            logger.LogInformation(
                "RuTracker topic {TopicId}: magnet не найден, повтор через 7 дней",
                job.TopicId);
            return TopicOutcome.MissingMagnet;
        }

        try
        {
            var result = await crawlRepository.UpsertListingWithTopicMetadataAsync(
                item,
                job.CategoryId,
                magnet.InfoHash,
                magnet.MagnetUri,
                magnet.Metadata,
                job.ListingFingerprint,
                job.DetailFingerprint,
                ct);

            await topicRepository.MarkImportedAsync(job, result, magnet.InfoHash, ct);
            await audiobookRepository.RefreshPeopleAsync(result.Id, ct);
            return new TopicOutcome(result.Inserted, result.Changed, true, false, false);
        }
        catch (PostgresException ex) when (ex.SqlState == PostgresErrorCodes.UniqueViolation)
        {
            await topicRepository.MarkDuplicateAsync(
                job,
                magnet.InfoHash,
                "Infohash уже принадлежит другой теме этого источника.",
                ct);
            logger.LogInformation(
                "RuTracker topic {TopicId}: duplicate infohash {InfoHash}",
                job.TopicId,
                magnet.InfoHash);
            return TopicOutcome.Duplicate;
        }
        catch (Exception ex)
        {
            var status = await topicRepository.MarkFailureAsync(
                job,
                ex.Message,
                settings.MaximumAttempts,
                ct);
            logger.LogWarning(
                ex,
                "RuTracker topic {TopicId} не сохранён, статус {Status}",
                job.TopicId,
                status);
            return TopicOutcome.Failure;
        }
    }

    private async Task<SourceRuntimeSettings> GetEnabledSettingsAsync(CancellationToken ct)
    {
        var settings = await settingsRepository.GetAsync(RuTrackerSourceDefinition.Key, ct);
        if (!settings.Enabled)
            throw new InvalidOperationException("Источник rutracker отключён в runtime-настройках.");
        return settings;
    }

    private static async Task DelayBeforeTopicAsync(int baseDelayMilliseconds, CancellationToken ct)
    {
        if (baseDelayMilliseconds <= 0)
            return;

        var spread = Math.Max(10, baseDelayMilliseconds / 5);
        var delay = Math.Max(0, baseDelayMilliseconds + Random.Shared.Next(-spread, spread + 1));
        if (delay > 0)
            await Task.Delay(delay, ct);
    }

    private async Task<RuTrackerMagnetValue?> FetchWithRetryAsync(
        RuTrackerSearchItem item,
        CancellationToken ct)
    {
        HttpRequestException? lastError = null;
        for (var attempt = 1; attempt <= definition.DetailRequestAttempts; attempt++)
        {
            try
            {
                return await client.FetchAsync(item.TopicUrl, item.Title, ct);
            }
            catch (HttpRequestException ex) when (IsTransient(ex) && attempt < definition.DetailRequestAttempts)
            {
                lastError = ex;
                var delay = definition.GetDetailRetryDelay(attempt) +
                    Random.Shared.Next(0, definition.DetailRetryJitterMilliseconds + 1);
                await Task.Delay(delay, ct);
            }
        }

        throw lastError ?? new HttpRequestException("Не удалось получить страницу темы RuTracker.");
    }

    private static bool IsTransient(HttpRequestException ex) =>
        ex.StatusCode is null ||
        ex.StatusCode == HttpStatusCode.RequestTimeout ||
        ex.StatusCode == HttpStatusCode.TooManyRequests ||
        (int?)ex.StatusCode >= 500;

    private static DetailDrainSummary Empty() => new(0, 0, 0, 0, 0);


    private sealed record TopicOutcome(
        bool Inserted,
        bool Changed,
        bool Enriched,
        bool Missing,
        bool Failed)
    {
        public static TopicOutcome Failure { get; } = new(false, false, false, false, true);
        public static TopicOutcome MissingMagnet { get; } = new(false, false, false, true, false);
        public static TopicOutcome Duplicate { get; } = new(false, false, false, false, false);
    }
}
