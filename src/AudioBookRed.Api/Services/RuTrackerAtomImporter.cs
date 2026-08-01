using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerAtomImporter(
    RuTrackerAtomClient client,
    RuTrackerAtomState state,
    RuTrackerAtomRepository atomRepository,
    RuTrackerTopicRepository topicRepository,
    ILogger<RuTrackerAtomImporter> logger)
{
    private readonly SemaphoreSlim _runLock = new(1, 1);

    public async Task<RuTrackerAtomImportResult> ImportAsync(
        int forumId,
        int maxEntries,
        CancellationToken ct)
    {
        if (!await _runLock.WaitAsync(0, ct))
            throw new InvalidOperationException("Импорт RuTracker Atom уже выполняется.");

        try
        {
            return await ImportForumCoreAsync(forumId, maxEntries, ct);
        }
        finally
        {
            _runLock.Release();
        }
    }

    public async Task<IReadOnlyList<RuTrackerAtomImportResult>> ImportCycleAsync(
        IReadOnlyList<int> forumIds,
        int maxEntries,
        CancellationToken ct)
    {
        if (!await _runLock.WaitAsync(0, ct))
            throw new InvalidOperationException("Импорт RuTracker Atom уже выполняется.");

        state.MarkCycleStarted(forumIds.Count);
        var results = new List<RuTrackerAtomImportResult>(forumIds.Count);
        try
        {
            for (var index = 0; index < forumIds.Count; index++)
            {
                var forumId = forumIds[index];
                state.MarkForumPosition(index + 1, forumIds.Count, forumId);
                try
                {
                    results.Add(await ImportForumCoreAsync(forumId, maxEntries, ct));
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    // ImportForumCoreAsync уже записал ошибку в state.
                    logger.LogError(
                        ex,
                        "Ошибка фонового импорта RuTracker Atom forum {ForumId}",
                        forumId);
                }
            }

            state.MarkCycleFinished();
            return results;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            state.MarkCycleCancelled();
            throw;
        }
        finally
        {
            _runLock.Release();
        }
    }

    private async Task<RuTrackerAtomImportResult> ImportForumCoreAsync(
        int forumId,
        int maxEntries,
        CancellationToken ct)
    {
        state.MarkStarted(forumId);
        try
        {
            var feed = await client.FetchAsync(forumId, maxEntries, ct);

            // Необработанные записи читаются из PostgreSQL даже после HTTP 304.
            // Иначе ошибка регистрации задания после успешного fetch могла бы
            // навсегда потеряться из-за сохранённого feed ETag.
            var unhandled = await atomRepository.GetUnhandledAsync(forumId, maxEntries, ct);
            var entries = new Dictionary<long, RuTrackerAtomEntry>();
            foreach (var entry in feed.Entries)
                entries[entry.TopicId] = entry;
            foreach (var entry in unhandled)
                entries.TryAdd(entry.TopicId, entry);

            if (entries.Count == 0)
            {
                var empty = new RuTrackerAtomImportResult(
                    forumId,
                    maxEntries,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    feed.NotModified,
                    feed.FeedUpdatedAt,
                    []);
                state.MarkFinished(empty);
                return empty;
            }

            var added = 0;
            var changed = 0;
            var skipped = 0;
            var enqueued = 0;
            var failed = 0;
            var errors = new List<string>();

            foreach (var entry in entries.Values)
            {
                ct.ThrowIfCancellationRequested();
                try
                {
                    var fingerprint = ListingFingerprint.ForAtom(entry);
                    var observation = await atomRepository.ObserveAsync(entry, fingerprint, ct);
                    switch (observation.Kind)
                    {
                        case RuTrackerAtomObservationKind.New:
                            added++;
                            break;
                        case RuTrackerAtomObservationKind.Changed:
                            changed++;
                            break;
                        default:
                            skipped++;
                            continue;
                    }

                    var item = new RuTrackerSearchItem(
                        entry.TopicId,
                        entry.Title,
                        entry.Publisher ?? $"forum-{forumId}",
                        entry.TopicUrl,
                        entry.SizeBytes ?? 0,
                        0,
                        0);

                    var registration = await topicRepository.RegisterAtomDiscoveredAsync(
                        item,
                        forumId,
                        page: 1,
                        refreshExisting: observation.Kind == RuTrackerAtomObservationKind.Changed,
                        ct: ct);
                    if (registration.Enqueued)
                        enqueued++;

                    // Running job нельзя безопасно переписать: текущий worker уже
                    // держит старый snapshot. Оставляем fingerprint необработанным,
                    // и следующая попытка возьмёт его из PostgreSQL даже при HTTP 304.
                    if (registration.Handled)
                    {
                        await atomRepository.MarkHandledAsync(
                            entry.TopicId,
                            observation.Fingerprint,
                            registration.Enqueued,
                            ct);
                    }
                    else
                    {
                        logger.LogDebug(
                            "Atom topic {TopicId} отложен до завершения running job",
                            entry.TopicId);
                    }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    failed++;
                    if (errors.Count < 10)
                        errors.Add($"topic {entry.TopicId}: {ex.Message}");
                    logger.LogWarning(ex, "Ошибка обработки Atom topic {TopicId}", entry.TopicId);
                }
            }

            var result = new RuTrackerAtomImportResult(
                forumId,
                maxEntries,
                entries.Count,
                added,
                changed,
                skipped,
                enqueued,
                failed,
                feed.NotModified,
                feed.FeedUpdatedAt,
                errors);

            state.MarkFinished(result);
            logger.LogInformation(
                "RuTracker Atom forum {ForumId}: received={Received}, new={New}, changed={Changed}, skipped={Skipped}, enqueued={Enqueued}, failed={Failed}, notModified={NotModified}",
                forumId,
                result.Received,
                result.New,
                result.Changed,
                result.Skipped,
                result.Enqueued,
                result.Failed,
                result.NotModified);
            return result;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            state.MarkCancelled(forumId);
            throw;
        }
        catch (Exception ex)
        {
            state.MarkFailed(forumId, ex);
            throw;
        }
    }
}
