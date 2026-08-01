using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;
using Npgsql;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerMagnetEnricher(
    RuTrackerMagnetClient client,
    RuTrackerMagnetState state,
    AudiobookRepository repository,
    ILogger<RuTrackerMagnetEnricher> logger)
{
    private readonly SemaphoreSlim _runLock = new(1, 1);

    public async Task<RuTrackerMagnetRunResult> RunAsync(int limit, CancellationToken ct)
    {
        limit = Math.Clamp(limit, 1, 100);
        if (!await _runLock.WaitAsync(0, ct))
            throw new InvalidOperationException("Обогащение magnet уже выполняется.");

        state.MarkStarted();
        try
        {
            var candidates = await repository.GetMissingMagnetsAsync(
                limit,
                client.MaxAttempts,
                client.RetryMinutes,
                ct);

            var enriched = 0;
            var missing = 0;
            var failed = 0;
            var errors = new List<string>();

            for (var index = 0; index < candidates.Count; index++)
            {
                var candidate = candidates[index];
                try
                {
                    var value = await client.FetchAsync(candidate.SourceUrl, candidate.Title, ct);
                    if (value is null)
                    {
                        missing++;
                        await repository.MarkMagnetFailureAsync(
                            candidate.Id,
                            "magnet или infohash не найден на странице темы",
                            ct);
                    }
                    else
                    {
                        await repository.UpdateMagnetAsync(
                            candidate.Id,
                            value.InfoHash,
                            value.MagnetUri,
                            ct);
                        enriched++;
                    }
                }
                catch (PostgresException ex) when (ex.SqlState == PostgresErrorCodes.UniqueViolation)
                {
                    failed++;
                    var message = "infohash уже принадлежит другой записи";
                    await repository.MarkMagnetFailureAsync(candidate.Id, message, ct);
                    if (errors.Count < 10)
                        errors.Add($"topic {candidate.SourceId}: {message}");
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    failed++;
                    logger.LogWarning(ex, "Не удалось получить magnet RuTracker topic {TopicId}", candidate.SourceId);
                    await repository.MarkMagnetFailureAsync(candidate.Id, ex.Message, ct);
                    if (errors.Count < 10)
                        errors.Add($"topic {candidate.SourceId}: {ex.Message}");
                }

                if (index + 1 < candidates.Count)
                    await Task.Delay(client.DelayMilliseconds, ct);
            }

            var result = new RuTrackerMagnetRunResult(
                limit,
                candidates.Count,
                enriched,
                missing,
                failed,
                errors);
            state.MarkFinished(result);
            return result;
        }
        catch (Exception ex)
        {
            state.MarkFailed(ex);
            throw;
        }
        finally
        {
            _runLock.Release();
        }
    }
}
