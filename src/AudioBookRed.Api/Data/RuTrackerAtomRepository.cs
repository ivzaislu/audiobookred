using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class RuTrackerAtomRepository(IConfiguration configuration)
{
    private string ConnectionString => configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is missing");

    public async Task InitializeAsync(CancellationToken ct)
    {
        const string sql = """
        CREATE TABLE IF NOT EXISTS source_atom_topics (
          source TEXT NOT NULL,
          topic_id BIGINT NOT NULL,
          forum_id INT NOT NULL,
          title TEXT NOT NULL,
          topic_url TEXT NOT NULL,
          size_bytes BIGINT NULL,
          atom_updated_at TIMESTAMPTZ NULL,
          fingerprint TEXT NOT NULL,
          handled_fingerprint TEXT NULL,
          first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          last_enqueued_at TIMESTAMPTZ NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          PRIMARY KEY(source, topic_id)
        );

        ALTER TABLE source_atom_topics
          ADD COLUMN IF NOT EXISTS handled_fingerprint TEXT NULL;

        CREATE INDEX IF NOT EXISTS ix_source_atom_topics_forum_seen
          ON source_atom_topics(source, forum_id, last_seen_at DESC);
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, cancellationToken: ct));
    }

    public async Task<RuTrackerAtomObservation> ObserveAsync(
        RuTrackerAtomEntry entry,
        string fingerprint,
        CancellationToken ct)
    {
        const string selectSql = """
        SELECT fingerprint AS Fingerprint,
          handled_fingerprint AS HandledFingerprint
        FROM source_atom_topics
        WHERE source = @Source AND topic_id = @TopicId
        FOR UPDATE;
        """;

        const string insertSql = """
        INSERT INTO source_atom_topics(
          source, topic_id, forum_id, title, topic_url, size_bytes,
          atom_updated_at, fingerprint, handled_fingerprint)
        VALUES (
          @Source, @TopicId, @ForumId, @Title, @TopicUrl, @SizeBytes,
          @AtomUpdatedAt, @Fingerprint, NULL);
        """;

        const string updateSql = """
        UPDATE source_atom_topics
        SET forum_id = @ForumId,
            title = @Title,
            topic_url = @TopicUrl,
            size_bytes = @SizeBytes,
            atom_updated_at = @AtomUpdatedAt,
            fingerprint = @Fingerprint,
            last_seen_at = NOW(),
            updated_at = NOW()
        WHERE source = @Source AND topic_id = @TopicId;
        """;

        var args = new
        {
            Source = RuTrackerSourceDefinition.Key,
            entry.TopicId,
            entry.ForumId,
            entry.Title,
            entry.TopicUrl,
            entry.SizeBytes,
            AtomUpdatedAt = entry.UpdatedAt,
            Fingerprint = fingerprint
        };

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        var previous = await db.QuerySingleOrDefaultAsync<RuTrackerAtomFingerprintState>(new CommandDefinition(
            selectSql,
            args,
            tx,
            cancellationToken: ct));

        RuTrackerAtomObservationKind kind;
        if (previous is null)
        {
            await db.ExecuteAsync(new CommandDefinition(insertSql, args, tx, cancellationToken: ct));
            kind = RuTrackerAtomObservationKind.New;
        }
        else
        {
            await db.ExecuteAsync(new CommandDefinition(updateSql, args, tx, cancellationToken: ct));
            if (string.Equals(previous.HandledFingerprint, fingerprint, StringComparison.Ordinal))
                kind = RuTrackerAtomObservationKind.Skipped;
            else if (previous.HandledFingerprint is null &&
                     string.Equals(previous.Fingerprint, fingerprint, StringComparison.Ordinal))
                kind = RuTrackerAtomObservationKind.New;
            else
                kind = RuTrackerAtomObservationKind.Changed;
        }

        await tx.CommitAsync(ct);
        return new RuTrackerAtomObservation(kind, entry.TopicId, fingerprint);
    }

    public async Task<IReadOnlyList<RuTrackerAtomEntry>> GetUnhandledAsync(
        int forumId,
        int limit,
        CancellationToken ct)
    {
        const string sql = """
        SELECT topic_id AS TopicId,
          title AS Title,
          topic_url AS TopicUrl,
          size_bytes AS SizeBytes,
          atom_updated_at AS UpdatedAt,
          forum_id AS ForumId
        FROM source_atom_topics
        WHERE source = @Source
          AND forum_id = @ForumId
          AND handled_fingerprint IS DISTINCT FROM fingerprint
        ORDER BY last_seen_at, topic_id
        LIMIT @Limit;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        var rows = await db.QueryAsync<RuTrackerAtomPendingRow>(new CommandDefinition(
            sql,
            new
            {
                Source = RuTrackerSourceDefinition.Key,
                ForumId = forumId,
                Limit = Math.Clamp(limit, 1, 100)
            },
            cancellationToken: ct));

        return rows.Select(row => new RuTrackerAtomEntry(
            row.TopicId,
            row.Title,
            row.TopicUrl,
            row.SizeBytes,
            row.UpdatedAt,
            null,
            row.ForumId)).ToArray();
    }

    public async Task MarkHandledAsync(
        long topicId,
        string fingerprint,
        bool enqueued,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_atom_topics
        SET handled_fingerprint = @Fingerprint,
            last_enqueued_at = CASE WHEN @Enqueued THEN NOW() ELSE last_enqueued_at END,
            updated_at = NOW()
        WHERE source = @Source
          AND topic_id = @TopicId
          AND fingerprint = @Fingerprint;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new
            {
                Source = RuTrackerSourceDefinition.Key,
                TopicId = topicId,
                Fingerprint = fingerprint,
                Enqueued = enqueued
            },
            cancellationToken: ct));
    }
}
