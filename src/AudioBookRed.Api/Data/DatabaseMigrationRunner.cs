using System.Diagnostics;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class DatabaseMigrationRunner(
    IConfiguration configuration,
    ILogger<DatabaseMigrationRunner> logger)
{
    public static IReadOnlyList<string> RequiredMigrationKeys { get; } =
    [
        "audiobook-search-text-v1",
        "audiobook-normalized-series-v1",
        "audiobook-magnet-required-v1",
        "audiobook-infohash-dedup-v1",
        "audiobook-core-indexes-v1",
        "audiobook-infohash-search-index-v2"
    ];

    private const long AdvisoryLockKey = 4_172_020_001L;

    private int CommandTimeoutSeconds => Math.Clamp(
        configuration.GetValue<int?>("DatabaseMigration:CommandTimeoutSeconds") ?? 600,
        60,
        3_600);

    public async Task<DatabaseMigrationSummary> RunAsync(
        NpgsqlConnection db,
        CancellationToken ct)
    {
        if (db.State != System.Data.ConnectionState.Open)
            throw new InvalidOperationException("Database migration connection must be open.");

        var stopwatch = Stopwatch.StartNew();
        var applied = 0;
        var adopted = 0;
        var skipped = 0;

        logger.LogInformation(
            "Database migration lock requested: timeoutSeconds={TimeoutSeconds}",
            CommandTimeoutSeconds);

        await db.ExecuteAsync(new CommandDefinition(
            "SELECT pg_advisory_lock(@LockKey);",
            new { LockKey = AdvisoryLockKey },
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

        logger.LogInformation("Database migration lock acquired.");

        try
        {
            var migrations = CreateMigrations(db);
            foreach (var migration in migrations)
            {
                var outcome = await RunOneAsync(db, migration, ct);
                switch (outcome)
                {
                    case MigrationOutcome.Applied:
                        applied++;
                        break;
                    case MigrationOutcome.Adopted:
                        adopted++;
                        break;
                    default:
                        skipped++;
                        break;
                }
            }
        }
        finally
        {
            try
            {
                await db.ExecuteAsync(new CommandDefinition(
                    "SELECT pg_advisory_unlock(@LockKey);",
                    new { LockKey = AdvisoryLockKey },
                    commandTimeout: 30,
                    cancellationToken: CancellationToken.None));
                logger.LogInformation("Database migration lock released.");
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "Database migration advisory lock release failed.");
            }
        }

        stopwatch.Stop();
        logger.LogInformation(
            "Database migrations completed: applied={Applied}, adopted={Adopted}, skipped={Skipped}, durationMs={DurationMs}",
            applied,
            adopted,
            skipped,
            stopwatch.ElapsedMilliseconds);

        return new DatabaseMigrationSummary(applied, adopted, skipped, stopwatch.ElapsedMilliseconds);
    }

    private IReadOnlyList<MigrationDefinition> CreateMigrations(NpgsqlConnection db) =>
    [
        new(
            RequiredMigrationKeys[0],
            (transaction, token) => HasNoMissingSearchTextAsync(db, transaction, token),
            (transaction, token) => BackfillSearchTextAsync(db, transaction, token)),
        new(
            RequiredMigrationKeys[1],
            (transaction, token) => HasNoMissingNormalizedSeriesAsync(db, transaction, token),
            (transaction, token) => BackfillNormalizedSeriesAsync(db, transaction, token)),
        new(
            RequiredMigrationKeys[2],
            (transaction, token) => HasMagnetConstraintAsync(db, transaction, token),
            (transaction, token) => EnforceMagnetConstraintAsync(db, transaction, token)),
        new(
            RequiredMigrationKeys[3],
            (transaction, token) => HasNoDuplicateInfoHashesAsync(db, transaction, token),
            (transaction, token) => RemoveDuplicateInfoHashesAsync(db, transaction, token)),
        new(
            RequiredMigrationKeys[4],
            (transaction, token) => HasRequiredIndexesAsync(db, transaction, token),
            (transaction, token) => RebuildRequiredIndexesAsync(db, transaction, token)),
        new(
            RequiredMigrationKeys[5],
            (transaction, token) => HasFastInfoHashSearchIndexAsync(db, transaction, token),
            (transaction, token) => CreateFastInfoHashSearchIndexAsync(db, transaction, token))
    ];

    private async Task<MigrationOutcome> RunOneAsync(
        NpgsqlConnection db,
        MigrationDefinition migration,
        CancellationToken ct)
    {
        if (await IsRegisteredAsync(db, migration.Key, ct))
        {
            logger.LogInformation("Database migration skipped: {MigrationKey}", migration.Key);
            return MigrationOutcome.Skipped;
        }

        var stopwatch = Stopwatch.StartNew();
        if (await migration.IsComplete(null, ct))
        {
            await using var adoption = await db.BeginTransactionAsync(ct);
            await RegisterAsync(db, adoption, migration.Key, ct);
            await adoption.CommitAsync(ct);
            stopwatch.Stop();
            logger.LogInformation(
                "Database migration adopted: {MigrationKey}, durationMs={DurationMs}",
                migration.Key,
                stopwatch.ElapsedMilliseconds);
            return MigrationOutcome.Adopted;
        }

        logger.LogInformation("Database migration started: {MigrationKey}", migration.Key);
        await using var transaction = await db.BeginTransactionAsync(ct);
        try
        {
            await migration.Apply(transaction, ct);
            if (!await migration.IsComplete(transaction, ct))
                throw new InvalidOperationException($"Migration verification failed: {migration.Key}");

            await RegisterAsync(db, transaction, migration.Key, ct);
            await transaction.CommitAsync(ct);
        }
        catch
        {
            await transaction.RollbackAsync(CancellationToken.None);
            throw;
        }

        stopwatch.Stop();
        logger.LogInformation(
            "Database migration completed: {MigrationKey}, durationMs={DurationMs}",
            migration.Key,
            stopwatch.ElapsedMilliseconds);
        return MigrationOutcome.Applied;
    }

    private Task<bool> IsRegisteredAsync(
        NpgsqlConnection db,
        string key,
        CancellationToken ct) =>
        db.ExecuteScalarAsync<bool>(new CommandDefinition(
            "SELECT EXISTS(SELECT 1 FROM app_migrations WHERE migration_key = @Key);",
            new { Key = key },
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

    private Task RegisterAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        string key,
        CancellationToken ct) =>
        db.ExecuteAsync(new CommandDefinition(
            "INSERT INTO app_migrations(migration_key) VALUES (@Key) ON CONFLICT DO NOTHING;",
            new { Key = key },
            transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

    private Task<bool> HasNoMissingSearchTextAsync(
        NpgsqlConnection db,
        NpgsqlTransaction? transaction,
        CancellationToken ct = default) =>
        db.ExecuteScalarAsync<bool>(new CommandDefinition(
            "SELECT NOT EXISTS(SELECT 1 FROM audiobook_releases WHERE search_text IS NULL OR BTRIM(search_text) = '');",
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

    private async Task BackfillSearchTextAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        CancellationToken ct = default)
    {
        const string sql = """
        WITH batch AS (
          SELECT id
          FROM audiobook_releases
          WHERE search_text IS NULL OR BTRIM(search_text) = ''
          ORDER BY id
          LIMIT 5000
          FOR UPDATE
        )
        UPDATE audiobook_releases release
        SET search_text = LOWER(REPLACE(BTRIM(REGEXP_REPLACE(
          CONCAT_WS(' ', release.title, release.author, release.series,
            ARRAY_TO_STRING(release.narrators, ' '), release.raw_title),
          '\s+', ' ', 'g')), 'ё', 'е'))
        FROM batch
        WHERE release.id = batch.id;
        """;

        var total = 0;
        while (true)
        {
            var updated = await db.ExecuteAsync(new CommandDefinition(
                sql,
                transaction: transaction,
                commandTimeout: CommandTimeoutSeconds,
                cancellationToken: ct));
            if (updated == 0)
                break;
            total += updated;
            logger.LogInformation("Database search_text backfill progress: updated={Updated}", total);
        }
    }

    private Task<bool> HasNoMissingNormalizedSeriesAsync(
        NpgsqlConnection db,
        NpgsqlTransaction? transaction,
        CancellationToken ct = default) =>
        db.ExecuteScalarAsync<bool>(new CommandDefinition(
            """
            SELECT NOT EXISTS(
              SELECT 1
              FROM audiobook_releases
              WHERE series IS NOT NULL
                AND BTRIM(series) <> ''
                AND (normalized_series IS NULL OR BTRIM(normalized_series) = '')
            );
            """,
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

    private async Task BackfillNormalizedSeriesAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        CancellationToken ct = default)
    {
        const string sql = """
        WITH batch AS (
          SELECT id
          FROM audiobook_releases
          WHERE series IS NOT NULL
            AND BTRIM(series) <> ''
            AND (normalized_series IS NULL OR BTRIM(normalized_series) = '')
          ORDER BY id
          LIMIT 5000
          FOR UPDATE
        )
        UPDATE audiobook_releases release
        SET normalized_series = LOWER(REPLACE(BTRIM(REGEXP_REPLACE(
          release.series, '\s+', ' ', 'g')), 'ё', 'е'))
        FROM batch
        WHERE release.id = batch.id;
        """;

        var total = 0;
        while (true)
        {
            var updated = await db.ExecuteAsync(new CommandDefinition(
                sql,
                transaction: transaction,
                commandTimeout: CommandTimeoutSeconds,
                cancellationToken: ct));
            if (updated == 0)
                break;
            total += updated;
            logger.LogInformation("Database normalized_series backfill progress: updated={Updated}", total);
        }
    }

    private Task<bool> HasMagnetConstraintAsync(
        NpgsqlConnection db,
        NpgsqlTransaction? transaction,
        CancellationToken ct = default) =>
        db.ExecuteScalarAsync<bool>(new CommandDefinition(
            """
            SELECT
              NOT EXISTS(
                SELECT 1 FROM audiobook_releases
                WHERE magnet_uri IS NULL OR BTRIM(magnet_uri) = '')
              AND EXISTS(
                SELECT 1
                FROM pg_constraint constraint_row
                JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
                WHERE schema_row.nspname = 'public'
                  AND table_row.relname = 'audiobook_releases'
                  AND constraint_row.conname = 'ck_audiobook_magnet_required'
                  AND constraint_row.convalidated
              );
            """,
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

    private async Task EnforceMagnetConstraintAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        CancellationToken ct = default)
    {
        await db.ExecuteAsync(new CommandDefinition(
            """
            DELETE FROM audiobook_releases
            WHERE magnet_uri IS NULL OR BTRIM(magnet_uri) = '';

            ALTER TABLE audiobook_releases
              DROP CONSTRAINT IF EXISTS ck_audiobook_magnet_required;
            ALTER TABLE audiobook_releases
              ADD CONSTRAINT ck_audiobook_magnet_required
              CHECK (magnet_uri IS NOT NULL AND BTRIM(magnet_uri) <> '');
            """,
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));
    }

    private Task<bool> HasNoDuplicateInfoHashesAsync(
        NpgsqlConnection db,
        NpgsqlTransaction? transaction,
        CancellationToken ct = default) =>
        db.ExecuteScalarAsync<bool>(new CommandDefinition(
            """
            SELECT NOT EXISTS(
              SELECT 1
              FROM audiobook_releases
              WHERE info_hash IS NOT NULL AND BTRIM(info_hash) <> ''
              GROUP BY source, LOWER(info_hash)
              HAVING COUNT(*) > 1
            );
            """,
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

    private async Task RemoveDuplicateInfoHashesAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        CancellationToken ct = default)
    {
        await db.ExecuteAsync(new CommandDefinition(
            """
            WITH duplicates AS (
              SELECT id,
                ROW_NUMBER() OVER (
                  PARTITION BY source, LOWER(info_hash)
                  ORDER BY seeders DESC NULLS LAST, updated_at DESC, id DESC) AS row_number
              FROM audiobook_releases
              WHERE info_hash IS NOT NULL AND BTRIM(info_hash) <> ''
            )
            DELETE FROM audiobook_releases release
            USING duplicates
            WHERE release.id = duplicates.id AND duplicates.row_number > 1;
            """,
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));
    }

    private Task<bool> HasRequiredIndexesAsync(
        NpgsqlConnection db,
        NpgsqlTransaction? transaction,
        CancellationToken ct = default)
    {
        var names = RequiredIndexNames;
        return db.ExecuteScalarAsync<bool>(new CommandDefinition(
            """
            SELECT COUNT(*) = @Expected
            FROM pg_class index_row
            JOIN pg_index index_state ON index_state.indexrelid = index_row.oid
            JOIN pg_class table_row ON table_row.oid = index_state.indrelid
            JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
            WHERE schema_row.nspname = 'public'
              AND table_row.relname = 'audiobook_releases'
              AND index_row.relname = ANY(@Names)
              AND index_state.indisvalid
              AND index_state.indisready;
            """,
            new { Expected = names.Length, Names = names },
            transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));
    }

    private async Task RebuildRequiredIndexesAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        CancellationToken ct = default)
    {
        await db.ExecuteAsync(new CommandDefinition(
            """
            DROP INDEX IF EXISTS ux_audiobook_info_hash;
            DROP INDEX IF EXISTS ix_audiobook_search_text_trgm;
            DROP INDEX IF EXISTS ix_audiobook_info_hash;
            DROP INDEX IF EXISTS ux_audiobook_source_info_hash;
            DROP INDEX IF EXISTS ix_audiobook_search;
            DROP INDEX IF EXISTS ix_audiobook_series;
            DROP INDEX IF EXISTS ix_audiobook_source;
            DROP INDEX IF EXISTS ix_audiobook_format;
            DROP INDEX IF EXISTS ix_audiobook_year;
            DROP INDEX IF EXISTS ix_audiobook_bitrate;
            DROP INDEX IF EXISTS ix_audiobook_updated;
            DROP INDEX IF EXISTS ix_audiobook_missing_magnet;

            CREATE INDEX ix_audiobook_search_text_trgm
              ON audiobook_releases USING GIN(search_text gin_trgm_ops);
            CREATE INDEX ix_audiobook_info_hash
              ON audiobook_releases(info_hash)
              WHERE info_hash IS NOT NULL AND info_hash <> '';
            CREATE UNIQUE INDEX ux_audiobook_source_info_hash
              ON audiobook_releases(source, LOWER(info_hash))
              WHERE info_hash IS NOT NULL AND BTRIM(info_hash) <> '';
            CREATE INDEX ix_audiobook_search
              ON audiobook_releases(normalized_author, normalized_title);
            CREATE INDEX ix_audiobook_series
              ON audiobook_releases(normalized_series)
              WHERE normalized_series IS NOT NULL;
            CREATE INDEX ix_audiobook_source
              ON audiobook_releases(LOWER(source));
            CREATE INDEX ix_audiobook_format
              ON audiobook_releases(LOWER(audio_format))
              WHERE audio_format IS NOT NULL;
            CREATE INDEX ix_audiobook_year
              ON audiobook_releases(release_year)
              WHERE release_year IS NOT NULL;
            CREATE INDEX ix_audiobook_bitrate
              ON audiobook_releases(bitrate_kbps)
              WHERE bitrate_kbps IS NOT NULL;
            CREATE INDEX ix_audiobook_updated
              ON audiobook_releases(updated_at DESC);
            """,
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));
    }


    private Task<bool> HasFastInfoHashSearchIndexAsync(
        NpgsqlConnection db,
        NpgsqlTransaction? transaction,
        CancellationToken ct = default) =>
        db.ExecuteScalarAsync<bool>(new CommandDefinition(
            """
            SELECT
              NOT EXISTS(
                SELECT 1
                FROM audiobook_releases
                WHERE info_hash IS NOT NULL
                  AND info_hash <> LOWER(BTRIM(info_hash)))
              AND EXISTS(
                SELECT 1
                FROM pg_class index_row
                JOIN pg_index index_state ON index_state.indexrelid = index_row.oid
                JOIN pg_class table_row ON table_row.oid = index_state.indrelid
                JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
                WHERE schema_row.nspname = 'public'
                  AND table_row.relname = 'audiobook_releases'
                  AND index_row.relname = 'ix_audiobook_info_hash_search_v2'
                  AND index_state.indisvalid
                  AND index_state.indisready
              );
            """,
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

    private Task CreateFastInfoHashSearchIndexAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        CancellationToken ct = default) =>
        db.ExecuteAsync(new CommandDefinition(
            """
            UPDATE audiobook_releases
            SET info_hash = LOWER(BTRIM(info_hash))
            WHERE info_hash IS NOT NULL
              AND info_hash <> LOWER(BTRIM(info_hash));

            DROP INDEX IF EXISTS ix_audiobook_info_hash_search_v2;
            CREATE INDEX ix_audiobook_info_hash_search_v2
              ON audiobook_releases(
                info_hash,
                metadata_parser_version DESC,
                updated_at DESC,
                id DESC)
              INCLUDE (seeders, leechers, size_bytes)
              WHERE info_hash IS NOT NULL AND info_hash <> '';

            ANALYZE audiobook_releases (info_hash, seeders, leechers, updated_at);
            """,
            transaction: transaction,
            commandTimeout: CommandTimeoutSeconds,
            cancellationToken: ct));

    private static string[] RequiredIndexNames { get; } =
    [
        "ix_audiobook_search_text_trgm",
        "ix_audiobook_info_hash",
        "ux_audiobook_source_info_hash",
        "ix_audiobook_search",
        "ix_audiobook_series",
        "ix_audiobook_source",
        "ix_audiobook_format",
        "ix_audiobook_year",
        "ix_audiobook_bitrate",
        "ix_audiobook_updated"
    ];

    private sealed record MigrationDefinition(
        string Key,
        Func<NpgsqlTransaction?, CancellationToken, Task<bool>> IsComplete,
        Func<NpgsqlTransaction, CancellationToken, Task> Apply);

    private enum MigrationOutcome
    {
        Applied,
        Adopted,
        Skipped
    }
}

public sealed record DatabaseMigrationSummary(
    int Applied,
    int Adopted,
    int Skipped,
    long DurationMilliseconds);
