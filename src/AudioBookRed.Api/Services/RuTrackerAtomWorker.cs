namespace AudioBookRed.Api.Services;

public sealed class RuTrackerAtomWorker(
    RuTrackerAtomClient client,
    RuTrackerAtomImporter importer,
    RuTrackerAtomState state,
    ILogger<RuTrackerAtomWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        logger.LogInformation(
            "RuTracker Atom worker started: enabled={Enabled}, intervalMinutes={IntervalMinutes}, forums={Forums}",
            client.Enabled,
            client.IntervalMinutes,
            string.Join(",", client.ForumIds));

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
                try
                {
                    await importer.ImportCycleAsync(
                        client.ForumIds,
                        client.MaxEntries,
                        stoppingToken);
                    var snapshot = state.Snapshot(client);
                    logger.LogInformation(
                        "RuTracker Atom cycle finished: forums={Forums}, received={Received}, new={New}, changed={Changed}, skipped={Skipped}, enqueued={Enqueued}, failed={Failed}, durationMs={DurationMs}",
                        snapshot.TotalForums,
                        snapshot.LastReceived,
                        snapshot.LastNew,
                        snapshot.LastChanged,
                        snapshot.LastSkipped,
                        snapshot.LastEnqueued,
                        snapshot.LastFailed,
                        snapshot.LastCycleDurationMilliseconds);
                }
                catch (InvalidOperationException ex)
                    when (ex.Message.Contains("уже выполняется", StringComparison.Ordinal))
                {
                    logger.LogDebug("Фоновый Atom cycle пропущен: {Message}", ex.Message);
                }
                catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
                {
                    return;
                }
                catch (Exception ex)
                {
                    logger.LogError(ex, "Ошибка фонового цикла RuTracker Atom");
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
