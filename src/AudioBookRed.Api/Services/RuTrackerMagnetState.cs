using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerMagnetState
{
    private readonly object _gate = new();
    private bool _running;
    private DateTimeOffset? _lastStartedAt;
    private DateTimeOffset? _lastFinishedAt;
    private DateTimeOffset? _lastSuccessAt;
    private int _lastCandidates;
    private int _lastEnriched;
    private int _lastMissing;
    private int _lastFailed;
    private string? _lastError;

    public void MarkStarted()
    {
        lock (_gate)
        {
            _running = true;
            _lastStartedAt = DateTimeOffset.UtcNow;
            _lastError = null;
        }
    }

    public void MarkFinished(RuTrackerMagnetRunResult result)
    {
        lock (_gate)
        {
            _running = false;
            _lastFinishedAt = DateTimeOffset.UtcNow;
            _lastSuccessAt = _lastFinishedAt;
            _lastCandidates = result.Candidates;
            _lastEnriched = result.Enriched;
            _lastMissing = result.Missing;
            _lastFailed = result.Failed;
            _lastError = result.Errors.Count == 0 ? null : string.Join("; ", result.Errors.Take(3));
        }
    }

    public void MarkFailed(Exception exception)
    {
        lock (_gate)
        {
            _running = false;
            _lastFinishedAt = DateTimeOffset.UtcNow;
            _lastCandidates = 0;
            _lastEnriched = 0;
            _lastMissing = 0;
            _lastFailed = 1;
            _lastError = exception.Message;
        }
    }

    public RuTrackerMagnetStatus Snapshot(RuTrackerMagnetClient client)
    {
        lock (_gate)
        {
            return new RuTrackerMagnetStatus(
                client.Enabled,
                client.IntervalMinutes,
                client.BatchSize,
                client.DelayMilliseconds,
                client.MaxAttempts,
                client.RetryMinutes,
                _running,
                _lastStartedAt,
                _lastFinishedAt,
                _lastSuccessAt,
                _lastCandidates,
                _lastEnriched,
                _lastMissing,
                _lastFailed,
                _lastError);
        }
    }
}
