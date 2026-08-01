using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerAtomState
{
    private readonly object _gate = new();
    private bool _running;
    private bool _cycleRunning;
    private DateTimeOffset? _lastStartedAt;
    private DateTimeOffset? _lastFinishedAt;
    private DateTimeOffset? _lastSuccessAt;
    private int? _lastForumId;
    private int _currentForumIndex;
    private int _totalForums;
    private long? _lastCycleDurationMilliseconds;
    private int _lastReceived;
    private int _lastNew;
    private int _lastChanged;
    private int _lastSkipped;
    private int _lastEnqueued;
    private int _lastFailed;
    private bool _lastNotModified;
    private string? _lastError;

    public void MarkCycleStarted(int totalForums)
    {
        lock (_gate)
        {
            _cycleRunning = true;
            _running = true;
            _lastStartedAt = DateTimeOffset.UtcNow;
            _lastFinishedAt = null;
            _lastForumId = null;
            _currentForumIndex = 0;
            _totalForums = Math.Max(0, totalForums);
            _lastCycleDurationMilliseconds = null;
            _lastReceived = 0;
            _lastNew = 0;
            _lastChanged = 0;
            _lastSkipped = 0;
            _lastEnqueued = 0;
            _lastFailed = 0;
            _lastNotModified = false;
            _lastError = null;
        }
    }

    public void MarkForumPosition(int index, int totalForums, int forumId)
    {
        lock (_gate)
        {
            _running = true;
            _currentForumIndex = Math.Max(0, index);
            _totalForums = Math.Max(0, totalForums);
            _lastForumId = forumId;
        }
    }

    public void MarkStarted(int forumId)
    {
        lock (_gate)
        {
            _running = true;
            if (!_cycleRunning)
            {
                _lastStartedAt = DateTimeOffset.UtcNow;
                _lastFinishedAt = null;
                _currentForumIndex = 1;
                _totalForums = 1;
                _lastCycleDurationMilliseconds = null;
                _lastReceived = 0;
                _lastNew = 0;
                _lastChanged = 0;
                _lastSkipped = 0;
                _lastEnqueued = 0;
                _lastFailed = 0;
                _lastNotModified = false;
                _lastError = null;
            }

            _lastForumId = forumId;
        }
    }

    public void MarkFinished(RuTrackerAtomImportResult result)
    {
        lock (_gate)
        {
            _lastForumId = result.ForumId;
            _lastReceived += result.Received;
            _lastNew += result.New;
            _lastChanged += result.Changed;
            _lastSkipped += result.Skipped;
            _lastEnqueued += result.Enqueued;
            _lastFailed += result.Failed;
            _lastNotModified = result.NotModified;
            if (result.Errors.Count > 0)
                _lastError = string.Join("; ", result.Errors.Take(3));

            if (!_cycleRunning)
                CompleteRunLocked(result.Failed == 0 && result.Errors.Count == 0);
        }
    }

    public void MarkCancelled(int forumId)
    {
        lock (_gate)
        {
            _lastForumId = forumId;
            if (!_cycleRunning)
                CompleteRunLocked(false, clearError: true);
        }
    }

    public void MarkFailed(int forumId, Exception exception)
    {
        lock (_gate)
        {
            _lastForumId = forumId;
            _lastFailed++;
            _lastError = exception.Message;
            if (!_cycleRunning)
                CompleteRunLocked(false);
        }
    }

    public void MarkCycleFinished()
    {
        lock (_gate)
        {
            _cycleRunning = false;
            CompleteRunLocked(_lastFailed == 0 && string.IsNullOrWhiteSpace(_lastError));
        }
    }

    public void MarkCycleCancelled()
    {
        lock (_gate)
        {
            _cycleRunning = false;
            CompleteRunLocked(false, clearError: true);
        }
    }

    public RuTrackerAtomStatus Snapshot(RuTrackerAtomClient client)
    {
        lock (_gate)
        {
            return new RuTrackerAtomStatus(
                client.Enabled,
                client.IntervalMinutes,
                client.MaxEntries,
                client.ForumIds,
                _running,
                _lastStartedAt,
                _lastFinishedAt,
                _lastSuccessAt,
                _lastForumId,
                _currentForumIndex,
                _totalForums,
                _lastCycleDurationMilliseconds,
                _lastReceived,
                _lastNew,
                _lastChanged,
                _lastSkipped,
                _lastEnqueued,
                _lastEnqueued,
                _lastFailed,
                _lastNotModified,
                _lastError);
        }
    }

    private void CompleteRunLocked(bool successful, bool clearError = false)
    {
        _running = false;
        _lastFinishedAt = DateTimeOffset.UtcNow;
        if (_lastStartedAt is { } started)
            _lastCycleDurationMilliseconds = Math.Max(
                0,
                (long)(_lastFinishedAt.Value - started).TotalMilliseconds);
        if (successful)
            _lastSuccessAt = _lastFinishedAt;
        if (clearError)
            _lastError = null;
    }
}
