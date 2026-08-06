using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class RuTrackerTopicRepository(
    IConfiguration configuration,
    ILogger<RuTrackerTopicRepository> logger)
{
    private string ConnectionString => configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is missing");

    public async Task InitializeAsync(CancellationToken ct)
    {
        const string schemaSql = """
        CREATE TABLE IF NOT EXISTS source_topic_jobs (
          id BIGSERIAL PRIMARY KEY,
          source TEXT NOT NULL,
          topic_id BIGINT NOT NULL,
          category_id INT NOT NULL,
          last_page INT NOT NULL DEFAULT 1,
          title TEXT NOT NULL,
          topic_url TEXT NOT NULL,
          size_bytes BIGINT NOT NULL DEFAULT 0,
          seeders INT NOT NULL DEFAULT 0,
          leechers INT NOT NULL DEFAULT 0,
          listing_fingerprint TEXT NOT NULL,
          detail_fingerprint TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending',
          attempts INT NOT NULL DEFAULT 0,
          next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          lease_until TIMESTAMPTZ NULL,
          info_hash TEXT NULL,
          release_id BIGINT NULL REFERENCES audiobook_releases(id) ON DELETE SET NULL,
          last_error TEXT NULL,
          discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          started_at TIMESTAMPTZ NULL,
          completed_at TIMESTAMPTZ NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          CONSTRAINT ck_source_topic_job_status CHECK (
            status IN ('pending', 'running', 'retry', 'imported', 'missing_magnet', 'duplicate_infohash', 'failed')),
          UNIQUE(source, topic_id)
        );

        CREATE INDEX IF NOT EXISTS ix_source_topic_jobs_claim
          ON source_topic_jobs(source, status, next_attempt_at, id);
        CREATE INDEX IF NOT EXISTS ix_source_topic_jobs_status
          ON source_topic_jobs(source, status, updated_at DESC);
        CREATE INDEX IF NOT EXISTS ix_source_topic_jobs_infohash
          ON source_topic_jobs(source, info_hash)
          WHERE info_hash IS NOT NULL;

        CREATE TABLE IF NOT EXISTS source_topic_occurrences (
          source TEXT NOT NULL,
          topic_id BIGINT NOT NULL,
          category_id INT NOT NULL,
          page INT NOT NULL,
          first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          PRIMARY KEY(source, topic_id, category_id, page)
        );
        CREATE INDEX IF NOT EXISTS ix_source_topic_occurrences_topic
          ON source_topic_occurrences(source, topic_id);
        """;

        // В старой версии на каждом запуске выполнялся UPSERT всех раздач RuTracker.
        // При десятках тысяч записей конфликтующая ветка обновляла каждую строку и
        // упиралась в стандартный 30-секундный timeout Npgsql. Для восстановления
        // достаточно добавить только отсутствующие topic_id; существующие задания
        // уже поддерживаются crawler/detail processor.
        const string insertMissingSql = """
        INSERT INTO source_topic_jobs(
          source, topic_id, category_id, last_page, title, topic_url,
          size_bytes, seeders, leechers, listing_fingerprint, detail_fingerprint,
          status, attempts, next_attempt_at, info_hash, release_id,
          discovered_at, last_seen_at, completed_at, updated_at)
        SELECT release.source,
          release.source_id::bigint,
          COALESCE(release.source_category_id, 0),
          1,
          release.raw_title,
          COALESCE(release.source_url, ''),
          COALESCE(release.size_bytes, 0),
          COALESCE(release.seeders, 0),
          COALESCE(release.leechers, 0),
          COALESCE(release.listing_fingerprint, MD5(release.raw_title)),
          COALESCE(release.detail_fingerprint, MD5(release.raw_title || ':' || COALESCE(release.size_bytes, 0)::text)),
          'imported',
          0,
          NOW(),
          LOWER(release.info_hash),
          release.id,
          release.discovered_at,
          COALESCE(release.last_seen_at, release.updated_at),
          NOW(),
          NOW()
        FROM audiobook_releases release
        WHERE release.source = 'rutracker'
          AND release.source_id ~ '^[0-9]+$'
          AND release.magnet_uri IS NOT NULL
          AND BTRIM(release.magnet_uri) <> ''
          AND NOT EXISTS (
            SELECT 1
            FROM source_topic_jobs job
            WHERE job.source = release.source
              AND job.topic_id = release.source_id::bigint)
        ON CONFLICT (source, topic_id) DO NOTHING;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await db.ExecuteAsync(new CommandDefinition(
            schemaSql,
            commandTimeout: 120,
            cancellationToken: ct));

        var inserted = await db.ExecuteAsync(new CommandDefinition(
            insertMissingSql,
            commandTimeout: 180,
            cancellationToken: ct));

        if (inserted > 0)
            logger.LogInformation("Реестр тем RuTracker восстановлен: добавлено {Inserted} отсутствующих записей.", inserted);
    }

    public async Task RegisterDiscoveredAsync(
        string source,
        ISourceListingItem item,
        int categoryId,
        int page,
        string listingFingerprint,
        string detailFingerprint,
        bool needsDetails,
        CancellationToken ct)
    {
        const string jobSql = """
        INSERT INTO source_topic_jobs(
          source, topic_id, category_id, last_page, title, topic_url,
          size_bytes, seeders, leechers, listing_fingerprint, detail_fingerprint,
          status, attempts, next_attempt_at, completed_at)
        VALUES (
          @Source, @TopicId, @CategoryId, @Page, @Title, @TopicUrl,
          @SizeBytes, @Seeders, @Leechers, @ListingFingerprint, @DetailFingerprint,
          CASE WHEN @NeedsDetails THEN 'pending' ELSE 'imported' END,
          0, NOW(), CASE WHEN @NeedsDetails THEN NULL ELSE NOW() END)
        ON CONFLICT (source, topic_id) DO UPDATE SET
          category_id = EXCLUDED.category_id,
          last_page = EXCLUDED.last_page,
          title = EXCLUDED.title,
          topic_url = EXCLUDED.topic_url,
          size_bytes = EXCLUDED.size_bytes,
          seeders = EXCLUDED.seeders,
          leechers = EXCLUDED.leechers,
          listing_fingerprint = EXCLUDED.listing_fingerprint,
          detail_fingerprint = EXCLUDED.detail_fingerprint,
          status = CASE
            WHEN @NeedsDetails = FALSE THEN 'imported'
            WHEN source_topic_jobs.detail_fingerprint IS DISTINCT FROM EXCLUDED.detail_fingerprint THEN 'pending'
            WHEN source_topic_jobs.status = 'running'
             AND source_topic_jobs.lease_until IS NOT NULL
             AND source_topic_jobs.lease_until < NOW() THEN 'retry'
            WHEN source_topic_jobs.status = 'missing_magnet'
             AND source_topic_jobs.next_attempt_at <= NOW() THEN 'pending'
            ELSE source_topic_jobs.status
          END,
          attempts = CASE
            WHEN source_topic_jobs.detail_fingerprint IS DISTINCT FROM EXCLUDED.detail_fingerprint THEN 0
            WHEN @NeedsDetails = FALSE THEN 0
            ELSE source_topic_jobs.attempts
          END,
          next_attempt_at = CASE
            WHEN source_topic_jobs.detail_fingerprint IS DISTINCT FROM EXCLUDED.detail_fingerprint THEN NOW()
            WHEN @NeedsDetails = FALSE THEN NOW()
            ELSE source_topic_jobs.next_attempt_at
          END,
          lease_until = CASE
            WHEN source_topic_jobs.detail_fingerprint IS DISTINCT FROM EXCLUDED.detail_fingerprint
              OR @NeedsDetails = FALSE THEN NULL
            ELSE source_topic_jobs.lease_until
          END,
          last_error = CASE
            WHEN source_topic_jobs.detail_fingerprint IS DISTINCT FROM EXCLUDED.detail_fingerprint
              OR @NeedsDetails = FALSE THEN NULL
            ELSE source_topic_jobs.last_error
          END,
          completed_at = CASE
            WHEN @NeedsDetails = FALSE THEN NOW()
            WHEN source_topic_jobs.detail_fingerprint IS DISTINCT FROM EXCLUDED.detail_fingerprint THEN NULL
            ELSE source_topic_jobs.completed_at
          END,
          last_seen_at = NOW(),
          updated_at = NOW();
        """;

        const string occurrenceSql = """
        INSERT INTO source_topic_occurrences(source, topic_id, category_id, page)
        VALUES (@Source, @TopicId, @CategoryId, @Page)
        ON CONFLICT (source, topic_id, category_id, page) DO UPDATE SET
          last_seen_at = NOW();
        """;

        var args = new
        {
            Source = source,
            item.TopicId,
            CategoryId = categoryId,
            Page = Math.Max(1, page),
            Title = item.Title,
            item.TopicUrl,
            item.SizeBytes,
            item.Seeders,
            item.Leechers,
            ListingFingerprint = listingFingerprint,
            DetailFingerprint = detailFingerprint,
            NeedsDetails = needsDetails
        };

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await db.ExecuteAsync(new CommandDefinition(jobSql, args, tx, cancellationToken: ct));
        await db.ExecuteAsync(new CommandDefinition(occurrenceSql, args, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
    }

    public async Task<RuTrackerTopicJob?> TryClaimAsync(
        string source,
        long topicId,
        int leaseMinutes,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_topic_jobs
        SET status = 'running',
            attempts = attempts + 1,
            started_at = COALESCE(started_at, NOW()),
            lease_until = NOW() + (@LeaseMinutes * INTERVAL '1 minute'),
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source
          AND topic_id = @TopicId
          AND (
            (status IN ('pending', 'retry') AND next_attempt_at <= NOW())
            OR (status = 'running' AND lease_until IS NOT NULL AND lease_until < NOW())
          )
        RETURNING id AS Id,
          source AS Source,
          topic_id AS TopicId,
          category_id AS CategoryId,
          last_page AS LastPage,
          title AS Title,
          topic_url AS TopicUrl,
          size_bytes AS SizeBytes,
          seeders AS Seeders,
          leechers AS Leechers,
          listing_fingerprint AS ListingFingerprint,
          detail_fingerprint AS DetailFingerprint,
          status AS Status,
          attempts AS Attempts,
          next_attempt_at AS NextAttemptAt,
          lease_until AS LeaseUntil,
          info_hash AS InfoHash,
          release_id AS ReleaseId,
          last_error AS LastError;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.QuerySingleOrDefaultAsync<RuTrackerTopicJob>(new CommandDefinition(
            sql,
            new
            {
                Source = source,
                TopicId = topicId,
                LeaseMinutes = Math.Clamp(leaseMinutes, 2, 60)
            },
            cancellationToken: ct));
    }

    public async Task<IReadOnlyList<RuTrackerTopicJob>> ClaimPendingAsync(
        string source,
        int limit,
        int leaseMinutes,
        CancellationToken ct)
    {
        const string recoverSql = """
        UPDATE source_topic_jobs
        SET status = 'retry',
            lease_until = NULL,
            next_attempt_at = NOW(),
            last_error = COALESCE(last_error, 'Истёк lease topic worker.'),
            updated_at = NOW()
        WHERE source = @Source
          AND status = 'running'
          AND lease_until IS NOT NULL
          AND lease_until < NOW();
        """;

        const string claimSql = """
        WITH picked AS (
          SELECT id
          FROM source_topic_jobs
          WHERE source = @Source
            AND status IN ('pending', 'retry')
            AND next_attempt_at <= NOW()
          ORDER BY next_attempt_at, id
          FOR UPDATE SKIP LOCKED
          LIMIT @Limit
        )
        UPDATE source_topic_jobs job
        SET status = 'running',
            attempts = job.attempts + 1,
            started_at = COALESCE(job.started_at, NOW()),
            lease_until = NOW() + (@LeaseMinutes * INTERVAL '1 minute'),
            last_error = NULL,
            updated_at = NOW()
        FROM picked
        WHERE job.id = picked.id
        RETURNING job.id AS Id,
          job.source AS Source,
          job.topic_id AS TopicId,
          job.category_id AS CategoryId,
          job.last_page AS LastPage,
          job.title AS Title,
          job.topic_url AS TopicUrl,
          job.size_bytes AS SizeBytes,
          job.seeders AS Seeders,
          job.leechers AS Leechers,
          job.listing_fingerprint AS ListingFingerprint,
          job.detail_fingerprint AS DetailFingerprint,
          job.status AS Status,
          job.attempts AS Attempts,
          job.next_attempt_at AS NextAttemptAt,
          job.lease_until AS LeaseUntil,
          job.info_hash AS InfoHash,
          job.release_id AS ReleaseId,
          job.last_error AS LastError;
        """;

        var args = new
        {
            Source = source,
            Limit = Math.Clamp(limit, 1, 500),
            LeaseMinutes = Math.Clamp(leaseMinutes, 2, 60)
        };

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await db.ExecuteAsync(new CommandDefinition(recoverSql, args, tx, cancellationToken: ct));
        var rows = (await db.QueryAsync<RuTrackerTopicJob>(new CommandDefinition(
            claimSql,
            args,
            tx,
            cancellationToken: ct))).AsList();
        await tx.CommitAsync(ct);
        return rows;
    }

    public Task MarkImportedAsync(
        RuTrackerTopicJob job,
        CrawlUpsertResult result,
        string infoHash,
        CancellationToken ct) => SetResolvedAsync(
            job,
            "imported",
            infoHash,
            result.Id,
            null,
            ct);

    public Task MarkDuplicateAsync(
        RuTrackerTopicJob job,
        string? infoHash,
        string message,
        CancellationToken ct) => SetResolvedAsync(
            job,
            "duplicate_infohash",
            infoHash,
            null,
            message,
            ct);

    public async Task MarkMissingMagnetAsync(
        RuTrackerTopicJob job,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_topic_jobs
        SET status = 'missing_magnet',
            lease_until = NULL,
            next_attempt_at = NOW() + INTERVAL '7 days',
            completed_at = NOW(),
            last_error = 'Magnet не найден на странице темы.',
            updated_at = NOW()
        WHERE id = @Id;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, new { job.Id }, cancellationToken: ct));
    }

    public async Task<string> MarkFailureAsync(
        RuTrackerTopicJob job,
        string error,
        int maxAttempts,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_topic_jobs
        SET status = CASE WHEN attempts >= @MaxAttempts THEN 'failed' ELSE 'retry' END,
            lease_until = NULL,
            next_attempt_at = CASE
              WHEN attempts >= @MaxAttempts THEN NOW()
              WHEN attempts = 1 THEN NOW() + INTERVAL '30 seconds'
              WHEN attempts = 2 THEN NOW() + INTERVAL '2 minutes'
              WHEN attempts = 3 THEN NOW() + INTERVAL '10 minutes'
              WHEN attempts = 4 THEN NOW() + INTERVAL '30 minutes'
              ELSE NOW() + INTERVAL '1 hour'
            END,
            completed_at = CASE WHEN attempts >= @MaxAttempts THEN NOW() ELSE NULL END,
            last_error = LEFT(@Error, 2000),
            updated_at = NOW()
        WHERE id = @Id
        RETURNING status;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.ExecuteScalarAsync<string>(new CommandDefinition(
            sql,
            new
            {
                job.Id,
                Error = error,
                MaxAttempts = Math.Clamp(maxAttempts, 1, 20)
            },
            cancellationToken: ct)) ?? "retry";
    }

    public async Task<int> RetryFailedAsync(string source, CancellationToken ct)
    {
        const string sql = """
        UPDATE source_topic_jobs
        SET status = 'retry',
            attempts = 0,
            next_attempt_at = NOW(),
            lease_until = NULL,
            completed_at = NULL,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source
          AND status IN ('failed', 'missing_magnet');
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.ExecuteAsync(new CommandDefinition(
            sql,
            new { Source = source },
            cancellationToken: ct));
    }

    public async Task<int> EnsureReleaseJobsAsync(
        string source,
        int parserVersion,
        CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(source))
            throw new ArgumentException("Source key is required.", nameof(source));
        if (parserVersion <= 0)
            throw new ArgumentOutOfRangeException(nameof(parserVersion));

        const string sql = """
        INSERT INTO source_topic_jobs(
          source, topic_id, category_id, last_page, title, topic_url,
          size_bytes, seeders, leechers, listing_fingerprint, detail_fingerprint,
          status, attempts, next_attempt_at, info_hash, release_id,
          discovered_at, last_seen_at, completed_at, updated_at)
        SELECT release.source,
          release.source_id::bigint,
          COALESCE(release.source_category_id, 0),
          1,
          release.raw_title,
          COALESCE(release.source_url, ''),
          COALESCE(release.size_bytes, 0),
          COALESCE(release.seeders, 0),
          COALESCE(release.leechers, 0),
          COALESCE(release.listing_fingerprint, MD5(release.raw_title)),
          COALESCE(
            release.detail_fingerprint,
            MD5(release.raw_title || ':' || COALESCE(release.size_bytes, 0)::text)),
          CASE
            WHEN release.metadata_parser_version < @ParserVersion THEN 'pending'
            ELSE 'imported'
          END,
          0,
          NOW(),
          LOWER(release.info_hash),
          release.id,
          release.discovered_at,
          COALESCE(release.last_seen_at, release.updated_at),
          CASE
            WHEN release.metadata_parser_version < @ParserVersion THEN NULL
            ELSE NOW()
          END,
          NOW()
        FROM audiobook_releases release
        WHERE release.source = @Source
          AND release.source_id ~ '^[0-9]+$'
          AND release.magnet_uri IS NOT NULL
          AND BTRIM(release.magnet_uri) <> ''
          AND NOT EXISTS (
            SELECT 1
            FROM source_topic_jobs job
            WHERE job.source = release.source
              AND job.topic_id = release.source_id::bigint)
        ON CONFLICT (source, topic_id) DO NOTHING;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        var inserted = await db.ExecuteAsync(new CommandDefinition(
            sql,
            new
            {
                Source = source.Trim().ToLowerInvariant(),
                ParserVersion = parserVersion
            },
            commandTimeout: 120,
            cancellationToken: ct));

        if (inserted > 0)
        {
            logger.LogInformation(
                "Реестр detail jobs источника {Source} дополнен: {Inserted}",
                source,
                inserted);
        }

        return inserted;
    }

    public async Task<SourceMetadataReparseResult> EnqueueMetadataReparseAsync(
        string source,
        IReadOnlyList<long> requestedTopicIds,
        int parserVersion,
        bool force,
        CancellationToken ct)
    {
        var topicIds = SourceMetadataReparsePolicy.NormalizeTopicIds(
            requestedTopicIds);
        if (parserVersion <= 0)
            throw new ArgumentOutOfRangeException(nameof(parserVersion));

        const string selectSql = """
        WITH requested AS (
          SELECT topic_id, ordinal
          FROM unnest(CAST(@TopicIds AS bigint[])) WITH ORDINALITY
            AS requested_values(topic_id, ordinal)
        )
        SELECT requested.topic_id AS TopicId,
          job.id AS JobId,
          job.status AS Status,
          release.id AS ReleaseId,
          release.metadata_parser_version AS MetadataParserVersion
        FROM requested
        LEFT JOIN source_topic_jobs job
          ON job.source = @Source
         AND job.topic_id = requested.topic_id
        LEFT JOIN audiobook_releases release
          ON release.source = @Source
         AND release.source_id = requested.topic_id::text
         AND release.magnet_uri IS NOT NULL
         AND BTRIM(release.magnet_uri) <> ''
        ORDER BY requested.ordinal;
        """;

        const string updateSql = """
        UPDATE source_topic_jobs job
        SET status = 'pending',
            attempts = 0,
            next_attempt_at = NOW(),
            lease_until = NULL,
            completed_at = NULL,
            last_error = NULL,
            updated_at = NOW()
        WHERE job.source = @Source
          AND job.topic_id = ANY(CAST(@TopicIds AS bigint[]))
          AND job.status <> 'running'
          AND EXISTS (
            SELECT 1
            FROM audiobook_releases release
            WHERE release.source = @Source
              AND release.source_id = job.topic_id::text
              AND release.magnet_uri IS NOT NULL
              AND BTRIM(release.magnet_uri) <> ''
              AND (
                @Force
                OR release.metadata_parser_version < @ParserVersion
              )
          )
        RETURNING job.topic_id;
        """;

        var args = new
        {
            Source = source,
            TopicIds = topicIds,
            ParserVersion = parserVersion,
            Force = force
        };

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        var candidates = (await db.QueryAsync<MetadataReparseCandidate>(
            new CommandDefinition(
                selectSql,
                args,
                tx,
                cancellationToken: ct))).AsList();

        var queuedIds = (await db.QueryAsync<long>(
            new CommandDefinition(
                updateSql,
                args,
                tx,
                cancellationToken: ct))).AsList();

        await tx.CommitAsync(ct);

        var queuedSet = queuedIds.ToHashSet();
        var orderedQueuedIds = topicIds
            .Where(queuedSet.Contains)
            .ToArray();
        var matched = candidates
            .Where(candidate =>
                candidate.JobId is not null &&
                candidate.ReleaseId is not null)
            .ToArray();
        var busy = matched.Count(candidate =>
            string.Equals(
                candidate.Status,
                "running",
                StringComparison.Ordinal));
        var alreadyCurrent = force
            ? 0
            : matched.Count(candidate =>
                !string.Equals(
                    candidate.Status,
                    "running",
                    StringComparison.Ordinal) &&
                candidate.MetadataParserVersion.GetValueOrDefault() >=
                    parserVersion);
        var status = await GetMetadataStatusAsync(
            source,
            parserVersion,
            ct);

        return new SourceMetadataReparseResult(
            source,
            "explicit",
            parserVersion,
            topicIds.Length,
            matched.Length,
            orderedQueuedIds.Length,
            alreadyCurrent,
            busy,
            candidates.Count - matched.Length,
            status.Stale,
            orderedQueuedIds);
    }

    public async Task<SourceMetadataReparseResult> EnqueueMetadataBackfillAsync(
        string source,
        int requestedLimit,
        int parserVersion,
        CancellationToken ct)
    {
        var limit = SourceMetadataReparsePolicy.NormalizeBatchLimit(
            requestedLimit);
        if (parserVersion <= 0)
            throw new ArgumentOutOfRangeException(nameof(parserVersion));

        const string selectSql = """
        SELECT job.topic_id
        FROM source_topic_jobs job
        JOIN audiobook_releases release
          ON release.source = job.source
         AND release.source_id = job.topic_id::text
        WHERE job.source = @Source
          AND release.magnet_uri IS NOT NULL
          AND BTRIM(release.magnet_uri) <> ''
          AND release.metadata_parser_version < @ParserVersion
          AND job.status NOT IN ('pending', 'retry', 'running')
        ORDER BY release.metadata_parsed_at NULLS FIRST, release.id
        LIMIT @Limit
        FOR UPDATE OF job SKIP LOCKED;
        """;

        const string updateSql = """
        UPDATE source_topic_jobs
        SET status = 'pending',
            attempts = 0,
            next_attempt_at = NOW(),
            lease_until = NULL,
            completed_at = NULL,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source
          AND topic_id = ANY(CAST(@TopicIds AS bigint[]))
        RETURNING topic_id;
        """;

        var args = new
        {
            Source = source,
            ParserVersion = parserVersion,
            Limit = limit
        };

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        var selected = (await db.QueryAsync<long>(
            new CommandDefinition(
                selectSql,
                args,
                tx,
                cancellationToken: ct))).AsList();

        var queued = (await db.QueryAsync<long>(
            new CommandDefinition(
                updateSql,
                new { Source = source, TopicIds = selected.ToArray() },
                tx,
                cancellationToken: ct))).AsList();

        await tx.CommitAsync(ct);

        var queuedSet = queued.ToHashSet();
        var orderedQueuedIds = selected
            .Where(queuedSet.Contains)
            .ToArray();
        var status = await GetMetadataStatusAsync(
            source,
            parserVersion,
            ct);

        return new SourceMetadataReparseResult(
            source,
            "backfill",
            parserVersion,
            limit,
            selected.Count,
            orderedQueuedIds.Length,
            0,
            0,
            0,
            status.Stale,
            orderedQueuedIds);
    }

    public async Task<SourceMetadataStatus> GetMetadataStatusAsync(
        string source,
        int parserVersion,
        CancellationToken ct)
    {
        if (parserVersion <= 0)
            throw new ArgumentOutOfRangeException(nameof(parserVersion));

        const string sql = """
        SELECT COUNT(*)::bigint AS Total,
          COUNT(*) FILTER (
            WHERE metadata_parser_version >= @ParserVersion
          )::bigint AS Current,
          COUNT(*) FILTER (
            WHERE metadata_parser_version < @ParserVersion
          )::bigint AS Stale,
          MIN(metadata_parsed_at) FILTER (
            WHERE metadata_parser_version >= @ParserVersion
          ) AS FirstParsedAt,
          MAX(metadata_parsed_at) FILTER (
            WHERE metadata_parser_version >= @ParserVersion
          ) AS LastParsedAt
        FROM audiobook_releases
        WHERE source = @Source
          AND magnet_uri IS NOT NULL
          AND BTRIM(magnet_uri) <> '';

        SELECT COUNT(*) FILTER (
            WHERE job.status IN ('pending', 'retry')
          )::int AS Queued,
          COUNT(*) FILTER (
            WHERE job.status = 'running'
          )::int AS Running
        FROM source_topic_jobs job
        JOIN audiobook_releases release
          ON release.source = job.source
         AND release.source_id = job.topic_id::text
        WHERE job.source = @Source
          AND release.magnet_uri IS NOT NULL
          AND BTRIM(release.magnet_uri) <> ''
          AND release.metadata_parser_version < @ParserVersion;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        using var grid = await db.QueryMultipleAsync(
            new CommandDefinition(
                sql,
                new
                {
                    Source = source,
                    ParserVersion = parserVersion
                },
                cancellationToken: ct));

        var counts = await grid.ReadSingleAsync<MetadataVersionCounts>();
        var queue = await grid.ReadSingleAsync<MetadataQueueCounts>();

        return new SourceMetadataStatus(
            source,
            parserVersion,
            counts.Total,
            counts.Current,
            counts.Stale,
            queue.Queued,
            queue.Running,
            counts.FirstParsedAt,
            counts.LastParsedAt,
            DateTimeOffset.UtcNow);
    }

    public async Task<RuTrackerTopicQueueSummary> GetSummaryAsync(string source, CancellationToken ct)
    {
        const string sql = """
        SELECT
          COUNT(*) FILTER (WHERE status = 'pending')::int AS Pending,
          COUNT(*) FILTER (WHERE status = 'running')::int AS Running,
          COUNT(*) FILTER (WHERE status = 'retry')::int AS Retry,
          COUNT(*) FILTER (WHERE status = 'imported')::int AS Imported,
          COUNT(*) FILTER (WHERE status = 'missing_magnet')::int AS MissingMagnet,
          COUNT(*) FILTER (WHERE status = 'duplicate_infohash')::int AS DuplicateInfoHash,
          COUNT(*) FILTER (WHERE status = 'failed')::int AS Failed
        FROM source_topic_jobs
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        var row = await db.QuerySingleAsync<TopicCounts>(new CommandDefinition(
            sql,
            new { Source = source },
            cancellationToken: ct));
        return new RuTrackerTopicQueueSummary(
            row.Pending,
            row.Running,
            row.Retry,
            row.Imported,
            row.MissingMagnet,
            row.DuplicateInfoHash,
            row.Failed);
    }

    public async Task<RuTrackerCompletenessStatus> GetCompletenessAsync(string source, CancellationToken ct)
    {
        var summary = await GetSummaryAsync(source, ct);
        const string occurrencesSql = """
        SELECT COUNT(*)::int
        FROM source_topic_occurrences
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        var occurrences = await db.ExecuteScalarAsync<int>(new CommandDefinition(
            occurrencesSql,
            new { Source = source },
            cancellationToken: ct));

        var terminal = summary.Imported + summary.MissingMagnet + summary.DuplicateInfoHash + summary.Failed;
        var percent = summary.Discovered == 0
            ? 0m
            : Math.Round(terminal * 100m / summary.Discovered, 2);
        return new RuTrackerCompletenessStatus(
            source,
            summary.Discovered,
            summary.Imported,
            summary.MissingMagnet,
            summary.DuplicateInfoHash,
            summary.Waiting,
            summary.Running,
            summary.Failed,
            occurrences,
            percent,
            DateTimeOffset.UtcNow);
    }

    private async Task SetResolvedAsync(
        RuTrackerTopicJob job,
        string status,
        string? infoHash,
        long? releaseId,
        string? error,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_topic_jobs
        SET status = @Status,
            lease_until = NULL,
            next_attempt_at = NOW(),
            info_hash = LOWER(@InfoHash),
            release_id = @ReleaseId,
            completed_at = NOW(),
            last_error = LEFT(@Error, 2000),
            updated_at = NOW()
        WHERE id = @Id;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new
            {
                job.Id,
                Status = status,
                InfoHash = infoHash,
                ReleaseId = releaseId,
                Error = error
            },
            cancellationToken: ct));
    }

    private sealed class MetadataReparseCandidate
    {
        public long TopicId { get; set; }
        public long? JobId { get; set; }
        public string? Status { get; set; }
        public long? ReleaseId { get; set; }
        public int? MetadataParserVersion { get; set; }
    }

    private sealed class MetadataVersionCounts
    {
        public long Total { get; set; }
        public long Current { get; set; }
        public long Stale { get; set; }
        public DateTimeOffset? FirstParsedAt { get; set; }
        public DateTimeOffset? LastParsedAt { get; set; }
    }

    private sealed class MetadataQueueCounts
    {
        public int Queued { get; set; }
        public int Running { get; set; }
    }

    private sealed class TopicCounts
    {
        public int Pending { get; set; }
        public int Running { get; set; }
        public int Retry { get; set; }
        public int Imported { get; set; }
        public int MissingMagnet { get; set; }
        public int DuplicateInfoHash { get; set; }
        public int Failed { get; set; }
    }

}
