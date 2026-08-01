using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerAtomState
{
    private readonly object _gate = new();
    private bool _running;
    private DateTimeOffset? _lastStartedAt;
    private DateTimeOffset? _lastFinishedAt;
    private DateTimeOffset? _lastSuccessAt;
    private int? _lastForumId;
    private int _lastReceived;
    private int _lastImported;
    private int _lastFailed;
    private bool _lastNotModified;
    private string? _lastError;

    public void MarkStarted(int forumId)
    {
        lock (_gate)
        {
            _running = true;
            _lastStartedAt = DateTimeOffset.UtcNow;
            _lastForumId = forumId;
            _lastError = null;
        }
    }

    public void MarkFinished(RuTrackerAtomImportResult result)
    {
        lock (_gate)
        {
            _running = false;
            _lastFinishedAt = DateTimeOffset.UtcNow;
            _lastSuccessAt = _lastFinishedAt;
            _lastForumId = result.ForumId;
            _lastReceived = result.Received;
            _lastImported = result.Imported;
            _lastFailed = result.Failed;
            _lastNotModified = result.NotModified;
            _lastError = result.Errors.Count == 0 ? null : string.Join("; ", result.Errors.Take(3));
        }
    }

    public void MarkFailed(int forumId, Exception exception)
    {
        lock (_gate)
        {
            _running = false;
            _lastFinishedAt = DateTimeOffset.UtcNow;
            _lastForumId = forumId;
            _lastReceived = 0;
            _lastImported = 0;
            _lastFailed = 1;
            _lastNotModified = false;
            _lastError = exception.Message;
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
                _lastReceived,
                _lastImported,
                _lastFailed,
                _lastNotModified,
                _lastError);
        }
    }
}
