using AudioBookRed.Api.Models;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class SourceJobRepository(IConfiguration configuration)
{
    private string ConnectionString => configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is missing");

    public async Task InitializeAsync(CancellationToken ct)
    {
        const string sql = """
        CREATE TABLE IF NOT EXISTS source_crawl_runs (
          id BIGSERIAL PRIMARY KEY,
          source TEXT NOT NULL,
          mode TEXT NOT NULL,
          run_key TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'queued',
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          started_at TIMESTAMPTZ NULL,
          completed_at TIMESTAMPTZ NULL,
          last_error TEXT NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          CONSTRAINT ck_source_crawl_run_mode CHECK (mode IN ('bootstrap', 'incremental', 'reconcile')),
          CONSTRAINT ck_source_crawl_run_status CHECK (status IN ('queued', 'running', 'completed', 'failed')),
          UNIQUE(source, mode, run_key)
        );

        CREATE TABLE IF NOT EXISTS source_crawl_jobs (
          id BIGSERIAL PRIMARY KEY,
          run_id BIGINT NOT NULL REFERENCES source_crawl_runs(id) ON DELETE CASCADE,
          source TEXT NOT NULL,
          mode TEXT NOT NULL,
          category_id INT NOT NULL,
          page INT NOT NULL,
          priority INT NOT NULL DEFAULT 100,
          status TEXT NOT NULL DEFAULT 'pending',
          attempts INT NOT NULL DEFAULT 0,
          next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          lease_until TIMESTAMPTZ NULL,
          received INT NOT NULL DEFAULT 0,
          inserted INT NOT NULL DEFAULT 0,
          changed INT NOT NULL DEFAULT 0,
          last_error TEXT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          started_at TIMESTAMPTZ NULL,
          completed_at TIMESTAMPTZ NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          CONSTRAINT ck_source_crawl_job_mode CHECK (mode IN ('bootstrap', 'incremental', 'reconcile')),
          CONSTRAINT ck_source_crawl_job_status CHECK (status IN ('pending', 'running', 'retry', 'completed', 'failed')),
          UNIQUE(run_id, category_id, page)
        );

        ALTER TABLE source_crawl_runs
          DROP CONSTRAINT IF EXISTS ck_source_crawl_run_mode;
        ALTER TABLE source_crawl_runs
          ADD CONSTRAINT ck_source_crawl_run_mode
          CHECK (mode IN ('bootstrap', 'incremental', 'reconcile'));
        ALTER TABLE source_crawl_jobs
          DROP CONSTRAINT IF EXISTS ck_source_crawl_job_mode;
        ALTER TABLE source_crawl_jobs
          ADD CONSTRAINT ck_source_crawl_job_mode
          CHECK (mode IN ('bootstrap', 'incremental', 'reconcile'));

        CREATE INDEX IF NOT EXISTS ix_source_crawl_jobs_claim
          ON source_crawl_jobs(source, status, next_attempt_at, priority, id);
        CREATE INDEX IF NOT EXISTS ix_source_crawl_jobs_run
          ON source_crawl_jobs(run_id, status);

        CREATE TABLE IF NOT EXISTS source_job_events (
          id BIGSERIAL PRIMARY KEY,
          source TEXT NOT NULL,
          run_id BIGINT NULL,
          job_id BIGINT NULL,
          event_type TEXT NOT NULL,
          mode TEXT NULL,
          category_id INT NULL,
          page INT NULL,
          message TEXT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
        CREATE INDEX IF NOT EXISTS ix_source_job_events_source_created
          ON source_job_events(source, created_at DESC, id DESC);
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, cancellationToken: ct));
    }

    public async Task<(SourceCrawlRun Run, int JobsAdded)> CreateOrResumeBootstrapAsync(
        string source,
        IReadOnlyDictionary<int, int> discoveredPages,
        CancellationToken ct)
    {
        if (discoveredPages.Count == 0)
            throw new ArgumentException("Не найдено ни одной категории для bootstrap.", nameof(discoveredPages));

        const string runSql = """
        INSERT INTO source_crawl_runs(source, mode, run_key, status, started_at)
        VALUES (@Source, 'bootstrap', 'bootstrap', 'running', NOW())
        ON CONFLICT (source, mode, run_key) DO UPDATE SET
          status = 'running',
          started_at = COALESCE(source_crawl_runs.started_at, NOW()),
          completed_at = NULL,
          last_error = NULL,
          updated_at = NOW()
        RETURNING id AS Id,
          source AS Source,
          mode AS Mode,
          run_key AS RunKey,
          status AS Status,
          created_at AS CreatedAt,
          started_at AS StartedAt,
          completed_at AS CompletedAt,
          last_error AS LastError;
        """;

        const string stateSql = """
        WITH discovered AS (
          SELECT category_id, max_page
          FROM unnest(CAST(@Categories AS integer[]), CAST(@MaxPages AS integer[]))
            AS d(category_id, max_page)
        )
        UPDATE source_crawl_state state
        SET bootstrap_last_page = discovered.max_page,
            bootstrap_completed = state.bootstrap_completed OR state.bootstrap_next_page > discovered.max_page,
            last_error = NULL,
            updated_at = NOW()
        FROM discovered
        WHERE state.source = @Source
          AND state.category_id = discovered.category_id;
        """;

        const string jobsSql = """
        WITH discovered AS (
          SELECT category_id, max_page
          FROM unnest(CAST(@Categories AS integer[]), CAST(@MaxPages AS integer[]))
            AS d(category_id, max_page)
        )
        INSERT INTO source_crawl_jobs(
          run_id, source, mode, category_id, page, priority, status)
        SELECT @RunId,
          @Source,
          'bootstrap',
          state.category_id,
          page.page,
          100 + page.page,
          'pending'
        FROM source_crawl_state state
        JOIN discovered ON discovered.category_id = state.category_id
        CROSS JOIN LATERAL generate_series(
          GREATEST(state.bootstrap_next_page, 1),
          discovered.max_page) AS page(page)
        WHERE state.source = @Source
          AND state.bootstrap_completed = FALSE
        ON CONFLICT (run_id, category_id, page) DO NOTHING
        RETURNING id;
        """;

        const string reviveSql = """
        UPDATE source_crawl_jobs
        SET status = 'retry',
            attempts = 0,
            next_attempt_at = NOW(),
            lease_until = NULL,
            last_error = NULL,
            completed_at = NULL,
            updated_at = NOW()
        WHERE run_id = @RunId AND status = 'failed';
        """;

        const string controlSql = """
        UPDATE source_crawl_control
        SET bootstrap_started_at = COALESCE(bootstrap_started_at, NOW()),
            bootstrap_completed_at = NULL,
            bootstrap_paused = FALSE,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source;
        """;

        var categories = discoveredPages.Keys.ToArray();
        var maxPages = categories.Select(category => Math.Max(1, discoveredPages[category])).ToArray();

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        var run = await db.QuerySingleAsync<SourceCrawlRun>(new CommandDefinition(
            runSql,
            new { Source = source },
            tx,
            cancellationToken: ct));

        var parameters = new
        {
            Source = source,
            RunId = run.Id,
            Categories = categories,
            MaxPages = maxPages
        };
        await db.ExecuteAsync(new CommandDefinition(stateSql, parameters, tx, cancellationToken: ct));
        var revived = await db.ExecuteAsync(new CommandDefinition(reviveSql, parameters, tx, cancellationToken: ct));
        var ids = await db.QueryAsync<long>(new CommandDefinition(jobsSql, parameters, tx, cancellationToken: ct));
        var added = ids.Count() + revived;
        await db.ExecuteAsync(new CommandDefinition(controlSql, parameters, tx, cancellationToken: ct));

        await db.ExecuteAsync(new CommandDefinition(
            """
            INSERT INTO source_job_events(source, run_id, event_type, mode, message)
            VALUES (@Source, @RunId, 'discovered', 'bootstrap', @Message);
            """,
            new
            {
                Source = source,
                RunId = run.Id,
                Message = $"Категорий: {categories.Length}; страниц: {maxPages.Sum()}; добавлено заданий: {added}."
            },
            tx,
            cancellationToken: ct));

        await tx.CommitAsync(ct);
        return (run, added);
    }

    public async Task<(SourceCrawlRun Run, int JobsAdded)> CreateIncrementalRunAsync(
        string source,
        IReadOnlyList<int> categories,
        int pages,
        string runKey,
        CancellationToken ct)
    {
        var pageLimit = Math.Clamp(pages, 1, 10);

        const string runSql = """
        INSERT INTO source_crawl_runs(source, mode, run_key, status, started_at)
        VALUES (@Source, 'incremental', @RunKey, 'running', NOW())
        ON CONFLICT (source, mode, run_key) DO UPDATE SET
          status = CASE
            WHEN source_crawl_runs.status = 'failed' THEN 'running'
            ELSE source_crawl_runs.status
          END,
          completed_at = CASE
            WHEN source_crawl_runs.status = 'failed' THEN NULL
            ELSE source_crawl_runs.completed_at
          END,
          last_error = CASE
            WHEN source_crawl_runs.status = 'failed' THEN NULL
            ELSE source_crawl_runs.last_error
          END,
          updated_at = NOW()
        RETURNING id AS Id,
          source AS Source,
          mode AS Mode,
          run_key AS RunKey,
          status AS Status,
          created_at AS CreatedAt,
          started_at AS StartedAt,
          completed_at AS CompletedAt,
          last_error AS LastError;
        """;

        const string reviveSql = """
        UPDATE source_crawl_jobs job
        SET status = 'retry',
            attempts = 0,
            next_attempt_at = NOW(),
            lease_until = NULL,
            last_error = NULL,
            completed_at = NULL,
            updated_at = NOW()
        WHERE job.run_id = @RunId
          AND job.status = 'failed'
          AND job.page <= COALESCE((
            SELECT LEAST(
              @PageLimit,
              GREATEST(1, COALESCE(state.bootstrap_last_page, 1)))
            FROM source_crawl_state state
            WHERE state.source = job.source
              AND state.category_id = job.category_id
          ), 1)
          AND NOT EXISTS (
            SELECT 1
            FROM source_crawl_jobs active
            WHERE active.id <> job.id
              AND active.source = job.source
              AND active.mode = job.mode
              AND active.category_id = job.category_id
              AND active.page = job.page
              AND active.status IN ('pending', 'running', 'retry')
          );
        """;

        const string jobsSql = """
        INSERT INTO source_crawl_jobs(
          run_id, source, mode, category_id, page, priority, status)
        SELECT @RunId,
          @Source,
          'incremental',
          state.category_id,
          1,
          11,
          'pending'
        FROM source_crawl_state AS state
        WHERE state.source = @Source
          AND state.category_id = ANY(CAST(@Categories AS integer[]))
          AND NOT EXISTS (
            SELECT 1
            FROM source_crawl_jobs active
            WHERE active.source = @Source
              AND active.mode = 'incremental'
              AND active.category_id = state.category_id
              AND active.page = 1
              AND active.status IN ('pending', 'running', 'retry')
          )
        ON CONFLICT (run_id, category_id, page) DO NOTHING
        RETURNING id;
        """;

        const string controlSql = """
        UPDATE source_crawl_control
        SET last_incremental_started_at = NOW(),
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        var run = await db.QuerySingleAsync<SourceCrawlRun>(new CommandDefinition(
            runSql,
            new { Source = source, RunKey = runKey },
            tx,
            cancellationToken: ct));

        var revived = await db.ExecuteAsync(new CommandDefinition(
            reviveSql,
            new { RunId = run.Id, PageLimit = pageLimit },
            tx,
            cancellationToken: ct));

        var ids = await db.QueryAsync<long>(new CommandDefinition(
            jobsSql,
            new
            {
                Source = source,
                RunId = run.Id,
                Categories = categories.ToArray()
            },
            tx,
            cancellationToken: ct));
        var added = ids.Count() + revived;

        if (added > 0)
        {
            await db.ExecuteAsync(new CommandDefinition(
                controlSql,
                new { Source = source },
                tx,
                cancellationToken: ct));

            await db.ExecuteAsync(new CommandDefinition(
                """
                INSERT INTO source_job_events(source, run_id, event_type, mode, message)
                VALUES (@Source, @RunId, 'enqueued', 'incremental', @Message);
                """,
                new
                {
                    Source = source,
                    RunId = run.Id,
                    Message = $"Добавлено или возвращено заданий: {added}; предел на категорию: {pageLimit}."
                },
                tx,
                cancellationToken: ct));
        }

        await RefreshRunStateAsync(db, tx, run.Id, source, "incremental", null, ct);
        await tx.CommitAsync(ct);
        return (run, added);
    }

    public async Task<(SourceCrawlRun Run, int JobsAdded)> CreateReconcileRunAsync(
        string source,
        IReadOnlyDictionary<int, int> discoveredPages,
        string runKey,
        CancellationToken ct)
    {
        if (discoveredPages.Count == 0)
            throw new ArgumentException("Не найдено ни одной категории для reconcile.", nameof(discoveredPages));

        const string runSql = """
        INSERT INTO source_crawl_runs(source, mode, run_key, status, started_at)
        VALUES (@Source, 'reconcile', @RunKey, 'running', NOW())
        ON CONFLICT (source, mode, run_key) DO UPDATE SET
          status = CASE WHEN source_crawl_runs.status = 'failed' THEN 'running' ELSE source_crawl_runs.status END,
          completed_at = CASE WHEN source_crawl_runs.status = 'failed' THEN NULL ELSE source_crawl_runs.completed_at END,
          last_error = CASE WHEN source_crawl_runs.status = 'failed' THEN NULL ELSE source_crawl_runs.last_error END,
          updated_at = NOW()
        RETURNING id AS Id,
          source AS Source,
          mode AS Mode,
          run_key AS RunKey,
          status AS Status,
          created_at AS CreatedAt,
          started_at AS StartedAt,
          completed_at AS CompletedAt,
          last_error AS LastError;
        """;

        const string jobsSql = """
        WITH discovered AS (
          SELECT category_id, max_page
          FROM unnest(CAST(@Categories AS integer[]), CAST(@MaxPages AS integer[]))
            AS d(category_id, max_page)
        )
        INSERT INTO source_crawl_jobs(
          run_id, source, mode, category_id, page, priority, status)
        SELECT @RunId,
          @Source,
          'reconcile',
          discovered.category_id,
          page.page,
          50 + page.page,
          'pending'
        FROM discovered
        CROSS JOIN LATERAL generate_series(1, discovered.max_page) AS page(page)
        ON CONFLICT (run_id, category_id, page) DO NOTHING
        RETURNING id;
        """;

        var categories = discoveredPages.Keys.ToArray();
        var maxPages = categories.Select(category => Math.Max(1, discoveredPages[category])).ToArray();

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        var run = await db.QuerySingleAsync<SourceCrawlRun>(new CommandDefinition(
            runSql,
            new { Source = source, RunKey = runKey },
            tx,
            cancellationToken: ct));
        var ids = await db.QueryAsync<long>(new CommandDefinition(
            jobsSql,
            new
            {
                Source = source,
                RunId = run.Id,
                Categories = categories,
                MaxPages = maxPages
            },
            tx,
            cancellationToken: ct));
        var added = ids.Count();
        await db.ExecuteAsync(new CommandDefinition(
            """
            INSERT INTO source_job_events(source, run_id, event_type, mode, message)
            VALUES (@Source, @RunId, 'discovered', 'reconcile', @Message);
            """,
            new
            {
                Source = source,
                RunId = run.Id,
                Message = $"Reconcile: категорий {categories.Length}; страниц {maxPages.Sum()}; заданий {added}."
            },
            tx,
            cancellationToken: ct));
        await tx.CommitAsync(ct);
        return (run, added);
    }

    public async Task<bool> HasReadyJobsAsync(
        string source,
        string mode,
        CancellationToken ct)
    {
        const string sql = """
        SELECT EXISTS (
          SELECT 1
          FROM source_crawl_jobs
          WHERE source = @Source
            AND mode = @Mode
            AND status IN ('pending', 'retry')
            AND next_attempt_at <= NOW()
        );
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.ExecuteScalarAsync<bool>(new CommandDefinition(
            sql,
            new { Source = source, Mode = mode },
            cancellationToken: ct));
    }

    public async Task<IReadOnlyList<SourceCrawlJob>> ClaimJobsAsync(
        string source,
        int limit,
        int leaseMinutes,
        CancellationToken ct)
    {
        // Dapper.QueryAsync reads the first result set of a SQL batch. The old
        // implementation put the expired-lease UPDATE before the claiming
        // UPDATE ... RETURNING in one batch, so Dapper received an empty first
        // result and the worker claimed zero jobs even while pending jobs existed.
        const string recoverExpiredLeasesSql = """
        UPDATE source_crawl_jobs
        SET status = 'retry',
            lease_until = NULL,
            next_attempt_at = NOW(),
            last_error = COALESCE(last_error, 'Истёк lease предыдущего worker.'),
            updated_at = NOW()
        WHERE source = @Source
          AND status = 'running'
          AND lease_until IS NOT NULL
          AND lease_until < NOW();
        """;

        const string claimSql = """
        WITH picked AS (
          SELECT job.id
          FROM source_crawl_jobs job
          LEFT JOIN source_crawl_control control ON control.source = job.source
          WHERE job.source = @Source
            AND job.status IN ('pending', 'retry')
            AND job.next_attempt_at <= NOW()
            AND (job.mode <> 'bootstrap' OR COALESCE(control.bootstrap_paused, FALSE) = FALSE)
          ORDER BY job.priority, job.next_attempt_at, job.id
          FOR UPDATE OF job SKIP LOCKED
          LIMIT @Limit
        )
        UPDATE source_crawl_jobs job
        SET status = 'running',
            attempts = job.attempts + 1,
            started_at = COALESCE(job.started_at, NOW()),
            lease_until = NOW() + (@LeaseMinutes * INTERVAL '1 minute'),
            last_error = NULL,
            updated_at = NOW()
        FROM picked
        WHERE job.id = picked.id
        RETURNING job.id AS Id,
          job.run_id AS RunId,
          job.source AS Source,
          job.mode AS Mode,
          job.category_id AS CategoryId,
          job.page AS Page,
          job.status AS Status,
          job.attempts AS Attempts,
          job.next_attempt_at AS NextAttemptAt,
          job.lease_until AS LeaseUntil,
          job.last_error AS LastError;
        """;

        var parameters = new
        {
            Source = source,
            Limit = Math.Clamp(limit, 1, 16),
            LeaseMinutes = Math.Clamp(leaseMinutes, 2, 60)
        };

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        await db.ExecuteAsync(new CommandDefinition(
            recoverExpiredLeasesSql,
            parameters,
            tx,
            cancellationToken: ct));

        var rows = (await db.QueryAsync<SourceCrawlJob>(new CommandDefinition(
            claimSql,
            parameters,
            tx,
            cancellationToken: ct))).AsList();

        if (rows.Count > 0)
        {
            const string eventSql = """
            INSERT INTO source_job_events(
              source, run_id, job_id, event_type, mode, category_id, page, message)
            VALUES (
              @Source, @RunId, @Id, 'claimed', @Mode, @CategoryId, @Page,
              'Задание взято worker.');
            """;
            await db.ExecuteAsync(new CommandDefinition(
                eventSql,
                rows,
                tx,
                cancellationToken: ct));
        }

        await tx.CommitAsync(ct);
        return rows;
    }

    public async Task<int?> GetKnownLastPageAsync(
        string source,
        int categoryId,
        CancellationToken ct)
    {
        const string sql = """
        SELECT bootstrap_last_page
        FROM source_crawl_state
        WHERE source = @Source AND category_id = @CategoryId;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.ExecuteScalarAsync<int?>(new CommandDefinition(
            sql,
            new { Source = source, CategoryId = categoryId },
            cancellationToken: ct));
    }

    public async Task CompleteOutOfRangeJobAsync(
        SourceCrawlJob job,
        int lastPage,
        CancellationToken ct)
    {
        if (lastPage < 1 || job.Page <= lastPage)
            throw new ArgumentOutOfRangeException(
                nameof(lastPage),
                "Known catalog boundary must be smaller than the job page.");

        const string jobSql = """
        UPDATE source_crawl_jobs
        SET status = 'completed',
            lease_until = NULL,
            next_attempt_at = NOW(),
            received = 0,
            inserted = 0,
            changed = 0,
            last_error = NULL,
            completed_at = NOW(),
            updated_at = NOW()
        WHERE id = @JobId
          AND status = 'running';
        """;

        const string stateSql = """
        UPDATE source_crawl_state
        SET last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;
        """;

        const string eventSql = """
        INSERT INTO source_job_events(
          source, run_id, job_id, event_type, mode, category_id, page, message)
        VALUES (
          @Source, @RunId, @JobId, 'end_of_catalog', @Mode, @CategoryId, @Page, @Message);
        """;

        const string bootstrapStateSql = """
        UPDATE source_crawl_state state
        SET bootstrap_next_page = COALESCE((
              SELECT MIN(pending.page)
              FROM source_crawl_jobs pending
              WHERE pending.run_id = @RunId
                AND pending.category_id = @CategoryId
                AND pending.status <> 'completed'
            ), COALESCE(state.bootstrap_last_page, @LastPage) + 1),
            bootstrap_completed = NOT EXISTS (
              SELECT 1
              FROM source_crawl_jobs pending
              WHERE pending.run_id = @RunId
                AND pending.category_id = @CategoryId
                AND pending.status <> 'completed'
            ),
            updated_at = NOW()
        WHERE state.source = @Source AND state.category_id = @CategoryId;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        var args = new
        {
            JobId = job.Id,
            job.RunId,
            Source = job.Source,
            job.Mode,
            job.CategoryId,
            job.Page,
            LastPage = lastPage,
            Message = $"Страница {job.Page} выше известной границы каталога {lastPage}; задание завершено без повторов."
        };

        var updated = await db.ExecuteAsync(new CommandDefinition(
            jobSql,
            args,
            tx,
            cancellationToken: ct));
        if (updated > 0)
        {
            await db.ExecuteAsync(new CommandDefinition(stateSql, args, tx, cancellationToken: ct));
            await db.ExecuteAsync(new CommandDefinition(eventSql, args, tx, cancellationToken: ct));
            if (string.Equals(job.Mode, "bootstrap", StringComparison.OrdinalIgnoreCase))
            {
                await db.ExecuteAsync(new CommandDefinition(
                    bootstrapStateSql,
                    args,
                    tx,
                    cancellationToken: ct));
            }
        }

        await RefreshRunStateAsync(db, tx, job.RunId, job.Source, job.Mode, null, ct);
        await tx.CommitAsync(ct);
    }

    public async Task CompleteJobAsync(
        SourceCrawlJob job,
        RuTrackerListingPage listing,
        ListingImportSummary imported,
        int incrementalPageLimit,
        CancellationToken ct)
    {
        var plannedPages = CatalogPageWindow.EffectiveIncrementalPages(
            incrementalPageLimit,
            listing.TotalPages);

        const string jobSql = """
        UPDATE source_crawl_jobs
        SET status = 'completed',
            lease_until = NULL,
            received = @Received,
            inserted = @Inserted,
            changed = @Changed,
            last_error = NULL,
            completed_at = NOW(),
            updated_at = NOW()
        WHERE id = @JobId;
        """;

        const string bootstrapStateSql = """
        UPDATE source_crawl_state
        SET bootstrap_last_page = GREATEST(COALESCE(bootstrap_last_page, 1), @TotalPages),
            last_bootstrap_page_at = NOW(),
            pages_scanned = pages_scanned + 1,
            releases_seen = releases_seen + @Received,
            releases_inserted = releases_inserted + @Inserted,
            releases_changed = releases_changed + @Changed,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;
        """;

        const string recalculateStateSql = """
        UPDATE source_crawl_state state
        SET bootstrap_next_page = COALESCE((
              SELECT MIN(job.page)
              FROM source_crawl_jobs job
              WHERE job.run_id = @RunId
                AND job.category_id = @CategoryId
                AND job.status <> 'completed'
            ), COALESCE(state.bootstrap_last_page, @Page) + 1),
            bootstrap_completed = NOT EXISTS (
              SELECT 1
              FROM source_crawl_jobs job
              WHERE job.run_id = @RunId
                AND job.category_id = @CategoryId
                AND job.status <> 'completed'
            ),
            updated_at = NOW()
        WHERE state.source = @Source AND state.category_id = @CategoryId;
        """;

        const string incrementalStateSql = """
        UPDATE source_crawl_state
        SET last_incremental_at = NOW(),
            pages_scanned = pages_scanned + 1,
            releases_seen = releases_seen + @Received,
            releases_inserted = releases_inserted + @Inserted,
            releases_changed = releases_changed + @Changed,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;
        """;

        const string reconcileStateSql = """
        UPDATE source_crawl_state
        SET pages_scanned = pages_scanned + 1,
            releases_seen = releases_seen + @Received,
            releases_inserted = releases_inserted + @Inserted,
            releases_changed = releases_changed + @Changed,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;
        """;

        const string boundaryStateSql = """
        UPDATE source_crawl_state
        SET bootstrap_last_page = @TotalPages,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source
          AND category_id = @CategoryId
          AND @Page = 1;
        """;

        const string affectedRunsSql = """
        SELECT DISTINCT run_id AS RunId, mode AS Mode
        FROM source_crawl_jobs
        WHERE source = @Source
          AND mode = 'incremental'
          AND category_id = @CategoryId
          AND page > @TotalPages
          AND status IN ('pending', 'retry', 'failed');
        """;

        const string closeOutOfRangeSql = """
        WITH closed AS (
          UPDATE source_crawl_jobs
          SET status = 'completed',
              lease_until = NULL,
              next_attempt_at = NOW(),
              received = 0,
              inserted = 0,
              changed = 0,
              last_error = NULL,
              completed_at = NOW(),
              updated_at = NOW()
          WHERE source = @Source
            AND mode = 'incremental'
            AND category_id = @CategoryId
            AND page > @TotalPages
            AND status IN ('pending', 'retry', 'failed')
          RETURNING id, run_id, source, mode, category_id, page
        )
        INSERT INTO source_job_events(
          source, run_id, job_id, event_type, mode, category_id, page, message)
        SELECT source,
          run_id,
          id,
          'end_of_catalog',
          mode,
          category_id,
          page,
          'Страница ' || CAST(page AS text) || ' выше обнаруженной границы каталога ' ||
            CAST(@TotalPages AS text) || '; задание завершено без повторов.'
        FROM closed;
        """;

        const string reviveDiscoveredPagesSql = """
        UPDATE source_crawl_jobs job
        SET status = 'retry',
            attempts = 0,
            next_attempt_at = NOW(),
            lease_until = NULL,
            last_error = NULL,
            completed_at = NULL,
            updated_at = NOW()
        WHERE job.run_id = @RunId
          AND job.mode = 'incremental'
          AND job.category_id = @CategoryId
          AND job.page BETWEEN 2 AND @PlannedPages
          AND job.status = 'failed'
          AND NOT EXISTS (
            SELECT 1
            FROM source_crawl_jobs active
            WHERE active.id <> job.id
              AND active.source = job.source
              AND active.mode = job.mode
              AND active.category_id = job.category_id
              AND active.page = job.page
              AND active.status IN ('pending', 'running', 'retry')
          );
        """;

        const string enqueueDiscoveredPagesSql = """
        INSERT INTO source_crawl_jobs(
          run_id, source, mode, category_id, page, priority, status)
        SELECT @RunId,
          @Source,
          'incremental',
          @CategoryId,
          pages.page,
          10 + pages.page,
          'pending'
        FROM generate_series(2, @PlannedPages) AS pages(page)
        WHERE NOT EXISTS (
          SELECT 1
          FROM source_crawl_jobs active
          WHERE active.source = @Source
            AND active.mode = 'incremental'
            AND active.category_id = @CategoryId
            AND active.page = pages.page
            AND active.status IN ('pending', 'running', 'retry')
        )
        ON CONFLICT (run_id, category_id, page) DO NOTHING;
        """;

        const string pageDiscoveryEventSql = """
        INSERT INTO source_job_events(
          source, run_id, event_type, mode, category_id, page, message)
        VALUES (
          @Source, @RunId, 'pages_discovered', 'incremental', @CategoryId, @Page, @Message);
        """;

        const string eventSql = """
        INSERT INTO source_job_events(
          source, run_id, job_id, event_type, mode, category_id, page, message)
        VALUES (
          @Source, @RunId, @JobId, 'completed', @Mode, @CategoryId, @Page, @Message);
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        var args = new
        {
            JobId = job.Id,
            job.RunId,
            Source = job.Source,
            job.Mode,
            job.CategoryId,
            job.Page,
            TotalPages = listing.TotalPages,
            PlannedPages = plannedPages,
            Received = listing.Items.Count,
            imported.Inserted,
            imported.Changed,
            Message = $"Получено {listing.Items.Count}; добавлено {imported.Inserted}; изменено {imported.Changed}."
        };

        await db.ExecuteAsync(new CommandDefinition(jobSql, args, tx, cancellationToken: ct));

        if (string.Equals(job.Mode, "bootstrap", StringComparison.OrdinalIgnoreCase))
        {
            await db.ExecuteAsync(new CommandDefinition(bootstrapStateSql, args, tx, cancellationToken: ct));
            await db.ExecuteAsync(new CommandDefinition(recalculateStateSql, args, tx, cancellationToken: ct));
        }
        else if (string.Equals(job.Mode, "incremental", StringComparison.OrdinalIgnoreCase))
        {
            await db.ExecuteAsync(new CommandDefinition(incrementalStateSql, args, tx, cancellationToken: ct));
        }
        else
        {
            await db.ExecuteAsync(new CommandDefinition(reconcileStateSql, args, tx, cancellationToken: ct));
        }

        var affectedRuns = new List<RunReference>();
        if (job.Page == 1)
        {
            await db.ExecuteAsync(new CommandDefinition(boundaryStateSql, args, tx, cancellationToken: ct));
            affectedRuns.AddRange(await db.QueryAsync<RunReference>(new CommandDefinition(
                affectedRunsSql,
                args,
                tx,
                cancellationToken: ct)));
            await db.ExecuteAsync(new CommandDefinition(
                closeOutOfRangeSql,
                args,
                tx,
                cancellationToken: ct));

            if (string.Equals(job.Mode, "bootstrap", StringComparison.OrdinalIgnoreCase))
            {
                await db.ExecuteAsync(new CommandDefinition(
                    recalculateStateSql,
                    args,
                    tx,
                    cancellationToken: ct));
            }

            if (string.Equals(job.Mode, "incremental", StringComparison.OrdinalIgnoreCase))
            {
                await db.ExecuteAsync(new CommandDefinition(
                    reviveDiscoveredPagesSql,
                    args,
                    tx,
                    cancellationToken: ct));
                await db.ExecuteAsync(new CommandDefinition(
                    enqueueDiscoveredPagesSql,
                    args,
                    tx,
                    cancellationToken: ct));
                await db.ExecuteAsync(new CommandDefinition(
                    pageDiscoveryEventSql,
                    new
                    {
                        args.Source,
                        args.RunId,
                        args.CategoryId,
                        args.Page,
                        Message = $"Каталог сообщает {listing.TotalPages} страниц; incremental запланировал {plannedPages}."
                    },
                    tx,
                    cancellationToken: ct));
            }
        }

        await db.ExecuteAsync(new CommandDefinition(eventSql, args, tx, cancellationToken: ct));
        await RefreshRunStateAsync(db, tx, job.RunId, job.Source, job.Mode, null, ct);

        foreach (var affectedRun in affectedRuns
                     .Where(run => run.RunId != job.RunId)
                     .GroupBy(run => run.RunId)
                     .Select(group => group.First()))
        {
            await RefreshRunStateAsync(
                db,
                tx,
                affectedRun.RunId,
                job.Source,
                affectedRun.Mode,
                null,
                ct);
        }

        await tx.CommitAsync(ct);
    }

    public async Task<string> FailJobAsync(
        SourceCrawlJob job,
        string error,
        int maxAttempts,
        CancellationToken ct)
    {
        const string jobSql = """
        UPDATE source_crawl_jobs
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
            last_error = LEFT(@Error, 2000),
            updated_at = NOW()
        WHERE id = @JobId
        RETURNING status;
        """;

        const string stateSql = """
        UPDATE source_crawl_state
        SET last_error = LEFT(@Error, 2000),
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);

        var status = await db.ExecuteScalarAsync<string?>(new CommandDefinition(
            jobSql,
            new
            {
                JobId = job.Id,
                Error = error,
                MaxAttempts = Math.Clamp(maxAttempts, 1, 20)
            },
            tx,
            cancellationToken: ct));

        if (status is null)
            throw new InvalidOperationException(
                $"Page job {job.Id} was not found while recording a failure.");

        await db.ExecuteAsync(new CommandDefinition(
            stateSql,
            new { Source = job.Source, job.CategoryId, Error = error },
            tx,
            cancellationToken: ct));

        await db.ExecuteAsync(new CommandDefinition(
            """
            INSERT INTO source_job_events(
              source, run_id, job_id, event_type, mode, category_id, page, message)
            VALUES (
              @Source, @RunId, @JobId, @EventType, @Mode, @CategoryId, @Page, LEFT(@Error, 2000));
            """,
            new
            {
                Source = job.Source,
                job.RunId,
                JobId = job.Id,
                EventType = status,
                job.Mode,
                job.CategoryId,
                job.Page,
                Error = error
            },
            tx,
            cancellationToken: ct));

        await RefreshRunStateAsync(db, tx, job.RunId, job.Source, job.Mode, error, ct);
        await tx.CommitAsync(ct);
        return status;
    }

    public async Task<int> RetryFailedAsync(string source, string? mode, CancellationToken ct)
    {
        const string jobsSql = """
        UPDATE source_crawl_jobs
        SET status = 'retry',
            attempts = 0,
            next_attempt_at = NOW(),
            lease_until = NULL,
            last_error = NULL,
            completed_at = NULL,
            updated_at = NOW()
        WHERE source = @Source
          AND status = 'failed'
          AND (@Mode IS NULL OR mode = @Mode);
        """;

        const string runsSql = """
        UPDATE source_crawl_runs
        SET status = 'running',
            completed_at = NULL,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source
          AND status = 'failed'
          AND (@Mode IS NULL OR mode = @Mode);
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        var retried = await db.ExecuteAsync(new CommandDefinition(
            jobsSql,
            new { Source = source, Mode = mode },
            tx,
            cancellationToken: ct));
        await db.ExecuteAsync(new CommandDefinition(
            runsSql,
            new { Source = source, Mode = mode },
            tx,
            cancellationToken: ct));
        await tx.CommitAsync(ct);
        return retried;
    }

    public async Task PruneAsync(string source, CancellationToken ct)
    {
        const string sql = """
        DELETE FROM source_crawl_jobs
        WHERE source = @Source
          AND status = 'completed'
          AND completed_at < NOW() - INTERVAL '24 hours';

        DELETE FROM source_crawl_runs
        WHERE source = @Source
          AND mode IN ('incremental', 'reconcile')
          AND status IN ('completed', 'failed')
          AND completed_at < NOW() - INTERVAL '14 days';

        DELETE FROM source_job_events
        WHERE source = @Source
          AND created_at < NOW() - INTERVAL '7 days';
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new { Source = source },
            cancellationToken: ct));
    }

    public async Task ResetBootstrapAsync(string source, CancellationToken ct)
    {
        const string sql = """
        DELETE FROM source_crawl_runs
        WHERE source = @Source AND mode = 'bootstrap';
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, new { Source = source }, cancellationToken: ct));
    }

    public async Task<bool> HasRunningJobsAsync(string source, CancellationToken ct)
    {
        const string sql = """
        SELECT EXISTS (
          SELECT 1
          FROM source_crawl_jobs
          WHERE source = @Source AND status = 'running'
        );
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.ExecuteScalarAsync<bool>(new CommandDefinition(
            sql,
            new { Source = source },
            cancellationToken: ct));
    }

    public async Task<SourceQueueSummary> GetQueueSummaryAsync(
        string source,
        string? mode,
        CancellationToken ct)
    {
        const string sql = """
        SELECT
          COUNT(*) FILTER (WHERE status = 'pending')::int AS Pending,
          COUNT(*) FILTER (WHERE status = 'running')::int AS Running,
          COUNT(*) FILTER (WHERE status = 'retry')::int AS Retry,
          COUNT(*) FILTER (WHERE status = 'completed')::int AS Completed,
          COUNT(*) FILTER (WHERE status = 'failed')::int AS Failed
        FROM source_crawl_jobs
        WHERE source = @Source
          AND (@Mode IS NULL OR mode = @Mode);
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        var row = await db.QuerySingleAsync<QueueCounts>(new CommandDefinition(
            sql,
            new { Source = source, Mode = mode },
            cancellationToken: ct));
        return row.ToSummary();
    }

    public async Task<IReadOnlyList<SourceCrawlRun>> GetRecentRunsAsync(
        string source,
        int limit,
        CancellationToken ct)
    {
        const string sql = """
        SELECT id AS Id,
          source AS Source,
          mode AS Mode,
          run_key AS RunKey,
          status AS Status,
          created_at AS CreatedAt,
          started_at AS StartedAt,
          completed_at AS CompletedAt,
          last_error AS LastError
        FROM source_crawl_runs
        WHERE source = @Source
        ORDER BY id DESC
        LIMIT @Limit;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        var rows = await db.QueryAsync<SourceCrawlRun>(new CommandDefinition(
            sql,
            new { Source = source, Limit = Math.Clamp(limit, 1, 20) },
            cancellationToken: ct));
        return rows.AsList();
    }

    public async Task AddEventAsync(
        string source,
        string eventType,
        string? message,
        string? mode,
        CancellationToken ct)
    {
        const string sql = """
        INSERT INTO source_job_events(source, event_type, mode, message)
        VALUES (@Source, @EventType, @Mode, LEFT(@Message, 2000));
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new { Source = source, EventType = eventType, Mode = mode, Message = message },
            cancellationToken: ct));
    }

    public async Task<IReadOnlyList<SourceJobEvent>> GetRecentEventsAsync(
        string source,
        int limit,
        CancellationToken ct)
    {
        const string sql = """
        SELECT id AS Id,
          source AS Source,
          run_id AS RunId,
          job_id AS JobId,
          event_type AS EventType,
          mode AS Mode,
          category_id AS CategoryId,
          page AS Page,
          message AS Message,
          created_at AS CreatedAt
        FROM source_job_events
        WHERE source = @Source
        ORDER BY id DESC
        LIMIT @Limit;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        var rows = await db.QueryAsync<SourceJobEvent>(new CommandDefinition(
            sql,
            new { Source = source, Limit = Math.Clamp(limit, 1, 100) },
            cancellationToken: ct));
        return rows.AsList();
    }

    private static async Task RefreshRunStateAsync(
        NpgsqlConnection db,
        NpgsqlTransaction tx,
        long runId,
        string source,
        string mode,
        string? error,
        CancellationToken ct)
    {
        const string countsSql = """
        SELECT
          COUNT(*) FILTER (WHERE status IN ('pending', 'running', 'retry'))::int AS Active,
          COUNT(*) FILTER (WHERE status = 'failed')::int AS Failed
        FROM source_crawl_jobs
        WHERE run_id = @RunId;
        """;

        const string updateRunSql = """
        UPDATE source_crawl_runs
        SET status = @Status,
            completed_at = CASE WHEN @Status IN ('completed', 'failed') THEN NOW() ELSE NULL END,
            last_error = CASE
              WHEN @Status = 'failed' THEN LEFT(COALESCE(@Error, last_error), 2000)
              WHEN @Status = 'completed' THEN NULL
              ELSE last_error
            END,
            updated_at = NOW()
        WHERE id = @RunId;
        """;

        var counts = await db.QuerySingleAsync<RunCounts>(new CommandDefinition(
            countsSql,
            new { RunId = runId },
            tx,
            cancellationToken: ct));

        var hasIncompleteBootstrapState = false;
        if (string.Equals(mode, "bootstrap", StringComparison.OrdinalIgnoreCase) && counts.Active == 0 && counts.Failed == 0)
        {
            hasIncompleteBootstrapState = await db.ExecuteScalarAsync<bool>(new CommandDefinition(
                """
                SELECT EXISTS (
                  SELECT 1 FROM source_crawl_state
                  WHERE source = @Source AND bootstrap_completed = FALSE
                );
                """,
                new { Source = source },
                tx,
                cancellationToken: ct));
        }

        var status = counts.Active > 0 || hasIncompleteBootstrapState
            ? "running"
            : counts.Failed > 0 ? "failed" : "completed";

        await db.ExecuteAsync(new CommandDefinition(
            updateRunSql,
            new { RunId = runId, Status = status, Error = error },
            tx,
            cancellationToken: ct));

        if (status == "running")
            return;

        if (string.Equals(mode, "bootstrap", StringComparison.OrdinalIgnoreCase))
        {
            const string controlSql = """
            UPDATE source_crawl_control control
            SET bootstrap_completed_at = CASE
                  WHEN @Status = 'completed'
                   AND NOT EXISTS (
                     SELECT 1 FROM source_crawl_state state
                     WHERE state.source = @Source AND state.bootstrap_completed = FALSE
                   )
                  THEN NOW()
                  ELSE control.bootstrap_completed_at
                END,
                last_error = CASE WHEN @Status = 'failed' THEN LEFT(@Error, 2000) ELSE NULL END,
                updated_at = NOW()
            WHERE source = @Source;
            """;
            await db.ExecuteAsync(new CommandDefinition(
                controlSql,
                new { Source = source, Status = status, Error = error },
                tx,
                cancellationToken: ct));
        }
        else if (string.Equals(mode, "incremental", StringComparison.OrdinalIgnoreCase))
        {
            const string controlSql = """
            UPDATE source_crawl_control
            SET last_incremental_completed_at = NOW(),
                last_error = CASE WHEN @Status = 'failed' THEN LEFT(@Error, 2000) ELSE NULL END,
                updated_at = NOW()
            WHERE source = @Source;
            """;
            await db.ExecuteAsync(new CommandDefinition(
                controlSql,
                new { Source = source, Status = status, Error = error },
                tx,
                cancellationToken: ct));
        }
    }

    private sealed class RunReference
    {
        public long RunId { get; set; }
        public string Mode { get; set; } = string.Empty;
    }

    private sealed class QueueCounts
    {
        public int Pending { get; set; }
        public int Running { get; set; }
        public int Retry { get; set; }
        public int Completed { get; set; }
        public int Failed { get; set; }

        public SourceQueueSummary ToSummary() =>
            new(Pending, Running, Retry, Completed, Failed);
    }

    private sealed class RunCounts
    {
        public int Active { get; set; }
        public int Failed { get; set; }
    }
}
