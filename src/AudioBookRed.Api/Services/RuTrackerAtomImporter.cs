using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerAtomImporter(
    RuTrackerAtomClient client,
    RuTrackerAtomState state,
    AudiobookRepository repository,
    ILogger<RuTrackerAtomImporter> logger)
{
    public async Task<RuTrackerAtomImportResult> ImportAsync(int forumId, int maxEntries, CancellationToken ct)
    {
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

            var imported = 0;
            var failed = 0;
            var errors = new List<string>();

            foreach (var entry in feed.Entries)
            {
                try
                {
                    var id = await repository.UpsertAsync(new CreateAudiobookRelease(
                        entry.Title,
                        "rutracker",
                        entry.TopicId.ToString(),
                        entry.TopicUrl,
                        null,
                        null,
                        entry.SizeBytes,
                        null,
                        null), ct);

                    if (id is null)
                    {
                        failed++;
                        if (errors.Count < 10)
                            errors.Add($"topic {entry.TopicId}: magnet отсутствует, запись пропущена");
                        continue;
                    }

                    imported++;
                }
                catch (Exception ex)
                {
                    failed++;
                    logger.LogWarning(ex, "Не удалось импортировать RuTracker topic {TopicId}", entry.TopicId);
                    if (errors.Count < 10)
                        errors.Add($"topic {entry.TopicId}: {ex.Message}");
                }
            }

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
            return result;
        }
        catch (Exception ex)
        {
            state.MarkFailed(forumId, ex);
            throw;
        }
    }
}
