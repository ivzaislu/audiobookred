namespace AudioBookRed.Api.Services;

public sealed class RuTrackerAtomWorker(
    RuTrackerAtomClient client,
    RuTrackerAtomImporter importer,
    ILogger<RuTrackerAtomWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(10), stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        while (!stoppingToken.IsCancellationRequested)
        {
            if (client.Enabled)
            {
                foreach (var forumId in client.ForumIds)
                {
                    try
                    {
                        var result = await importer.ImportAsync(forumId, client.MaxEntries, stoppingToken);
                        logger.LogInformation(
                            "RuTracker Atom forum {ForumId}: received={Received}, imported={Imported}, failed={Failed}, notModified={NotModified}",
                            forumId,
                            result.Received,
                            result.Imported,
                            result.Failed,
                            result.NotModified);
                    }
                    catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
                    {
                        return;
                    }
                    catch (Exception ex)
                    {
                        logger.LogError(ex, "Ошибка фонового импорта RuTracker Atom forum {ForumId}", forumId);
                    }
                }
            }

            var delay = client.Enabled
                ? TimeSpan.FromMinutes(client.IntervalMinutes)
                : TimeSpan.FromMinutes(1);
            try
            {
                await Task.Delay(delay, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }
}
