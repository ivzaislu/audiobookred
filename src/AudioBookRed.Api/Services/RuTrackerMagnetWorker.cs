namespace AudioBookRed.Api.Services;

public sealed class RuTrackerMagnetWorker(
    RuTrackerMagnetClient client,
    RuTrackerMagnetEnricher enricher,
    ILogger<RuTrackerMagnetWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(20), stoppingToken);
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
                    var result = await enricher.RunAsync(client.BatchSize, stoppingToken);
                    logger.LogInformation(
                        "RuTracker magnets: candidates={Candidates}, enriched={Enriched}, missing={Missing}, failed={Failed}",
                        result.Candidates,
                        result.Enriched,
                        result.Missing,
                        result.Failed);
                }
                catch (InvalidOperationException ex) when (ex.Message.Contains("уже выполняется", StringComparison.Ordinal))
                {
                    logger.LogDebug("RuTracker magnet run пропущен: {Message}", ex.Message);
                }
                catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
                {
                    return;
                }
                catch (Exception ex)
                {
                    logger.LogError(ex, "Ошибка фонового получения RuTracker magnet");
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
