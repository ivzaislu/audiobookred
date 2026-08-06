using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;
using Npgsql;

namespace AudioBookRed.Api.Services;

public sealed class RutorDetailProcessor(
    RutorTransport transport,
    RutorDetailParser parser,
    SourceCrawlRepository crawlRepository,
    RuTrackerTopicRepository topicRepository,
    AudiobookRepository audiobookRepository,
    SourceSettingsRepository settingsRepository,
    RutorSourceDefinition definition,
    CrawlerResourceGuard resourceGuard,
    ILogger<RutorDetailProcessor> logger)
{
    public async Task<ListingImportSummary> DrainPendingTopicsAsync(
        int requestedLimit,
        CancellationToken ct)
    {
        var settings = await GetEnabledSettingsAsync(ct);
        var jobs = await topicRepository.ClaimPendingAsync(
            RutorSourceDefinition.Key,
            Math.Clamp(requestedLimit, 1, 500),
            definition.WorkerLeaseMinutes,
            ct);
        if (jobs.Count == 0)
            return new ListingImportSummary(0, 0, Empty());

        var inserted = 0;
        var changed = 0;
        var enriched = 0;
        var failed = 0;

        await Parallel.ForEachAsync(
            jobs,
            new ParallelOptions
            {
                MaxDegreeOfParallelism = Math.Clamp(settings.DetailConcurrency, 1, 8),
                CancellationToken = ct
            },
            async (job, token) =>
            {
                var outcome = await ProcessClaimedTopicAsync(job, settings, token);
                if (outcome.Enriched)
                    Interlocked.Increment(ref enriched);
                if (outcome.Failed)
                    Interlocked.Increment(ref failed);
                if (outcome.Inserted)
                    Interlocked.Increment(ref inserted);
                else if (outcome.Changed)
                    Interlocked.Increment(ref changed);
            });

        var concurrency = Math.Clamp(settings.DetailConcurrency, 1, 8);
        var batches = (jobs.Count + concurrency - 1) / concurrency;
        return new ListingImportSummary(
            inserted,
            changed,
            new DetailDrainSummary(batches, jobs.Count, enriched, 0, failed));
    }

    private async Task<TopicOutcome> ProcessClaimedTopicAsync(
        RuTrackerTopicJob job,
        SourceRuntimeSettings settings,
        CancellationToken ct)
    {
        resourceGuard.EnsureEnoughDiskSpace();
        await DelayBeforeTopicAsync(settings.RequestDelayMilliseconds, ct);

        RutorHtmlResponse response;
        RutorDetailValue detail;
        try
        {
            response = await transport.GetHtmlAsync($"torrent/{job.TopicId}", ct);
            detail = parser.Parse(response.Html, job.Title);
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
                "Rutor detail {TopicId} переведён в {Status}",
                job.TopicId,
                status);
            return TopicOutcome.Failure;
        }

        var item = new RutorListingItem(
            job.TopicId,
            job.Title,
            "Аудиокниги",
            response.Uri.ToString(),
            job.SizeBytes,
            job.Seeders,
            job.Leechers,
            detail.InfoHash,
            detail.MagnetUri);

        try
        {
            var result = await crawlRepository.UpsertListingWithTopicMetadataAsync(
                RutorSourceDefinition.Key,
                item,
                job.CategoryId,
                detail.InfoHash,
                detail.MagnetUri,
                detail.Metadata,
                job.ListingFingerprint,
                job.DetailFingerprint,
                ct);

            await topicRepository.MarkImportedAsync(job, result, detail.InfoHash, ct);
            await audiobookRepository.RefreshPeopleAsync(result.Id, ct);
            return new TopicOutcome(result.Inserted, result.Changed, true, false);
        }
        catch (PostgresException ex) when (ex.SqlState == PostgresErrorCodes.UniqueViolation)
        {
            await topicRepository.MarkDuplicateAsync(
                job,
                detail.InfoHash,
                "Infohash уже принадлежит другой раздаче Rutor.",
                ct);
            logger.LogInformation(
                "Rutor detail {TopicId}: duplicate infohash {InfoHash}",
                job.TopicId,
                detail.InfoHash);
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
                "Rutor detail {TopicId} не сохранён, статус {Status}",
                job.TopicId,
                status);
            return TopicOutcome.Failure;
        }
    }

    private async Task<SourceRuntimeSettings> GetEnabledSettingsAsync(CancellationToken ct)
    {
        var settings = await settingsRepository.GetAsync(RutorSourceDefinition.Key, ct);
        if (!settings.Enabled)
            throw new InvalidOperationException("Источник rutor отключён в runtime-настройках.");
        return settings;
    }

    private static async Task DelayBeforeTopicAsync(
        int baseDelayMilliseconds,
        CancellationToken ct)
    {
        if (baseDelayMilliseconds <= 0)
            return;

        var spread = Math.Max(10, baseDelayMilliseconds / 5);
        var delay = Math.Max(
            0,
            baseDelayMilliseconds + Random.Shared.Next(-spread, spread + 1));
        if (delay > 0)
            await Task.Delay(delay, ct);
    }

    private static DetailDrainSummary Empty() => new(0, 0, 0, 0, 0);

    private sealed record TopicOutcome(
        bool Inserted,
        bool Changed,
        bool Enriched,
        bool Failed)
    {
        public static TopicOutcome Failure { get; } = new(false, false, false, true);
        public static TopicOutcome Duplicate { get; } = new(false, false, false, false);
    }
}
