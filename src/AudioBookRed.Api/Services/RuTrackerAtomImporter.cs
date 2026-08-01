using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerAtomImporter(
    RuTrackerAtomClient client,
    RuTrackerAtomState state,
    RuTrackerDetailProcessor detailProcessor,
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

        state.MarkStarted(forumId);
        try
        {
            var feed = await client.FetchAsync(forumId, maxEntries, ct);
            if (feed.NotModified)
            {
                var notModified = new RuTrackerAtomImportResult(
                    forumId,
                    maxEntries,
                    0,
                    0,
                    0,
                    true,
                    feed.FeedUpdatedAt,
                    []);
                state.MarkFinished(notModified);
                return notModified;
            }

            var listings = feed.Entries
                .Select(entry => new RuTrackerSearchItem(
                    entry.TopicId,
                    entry.Title,
                    entry.Publisher ?? $"forum-{forumId}",
                    entry.TopicUrl,
                    entry.SizeBytes ?? 0,
                    0,
                    0))
                .ToArray();

            var summary = await detailProcessor.ImportListingsAsync(
                listings,
                forumId,
                page: 1,
                ct);

            var imported = summary.Inserted + summary.Changed;
            var failed = summary.Details.Failed + summary.Details.Missing;
            var errors = new List<string>();

            if (summary.Details.Missing > 0)
                errors.Add($"magnet не найден: {summary.Details.Missing}");
            if (summary.Details.Failed > 0)
                errors.Add($"ошибки обработки: {summary.Details.Failed}");

            var result = new RuTrackerAtomImportResult(
                forumId,
                maxEntries,
                feed.Entries.Count,
                imported,
                failed,
                false,
                feed.FeedUpdatedAt,
                errors);

            state.MarkFinished(result);
            logger.LogInformation(
                "RuTracker Atom forum {ForumId}: received={Received}, inserted={Inserted}, changed={Changed}, missing={Missing}, failed={Failed}",
                forumId,
                feed.Entries.Count,
                summary.Inserted,
                summary.Changed,
                summary.Details.Missing,
                summary.Details.Failed);
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
        finally
        {
            _runLock.Release();
        }
    }
}
