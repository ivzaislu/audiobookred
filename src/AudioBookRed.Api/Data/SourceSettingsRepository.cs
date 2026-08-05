using AudioBookRed.Api.Models;
using AudioBookRed.Api.Sources;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class SourceSettingsRepository
{
    private readonly IConfiguration _configuration;
    private readonly IReadOnlyDictionary<string, ISourceModule> _sourceModules;

    public SourceSettingsRepository(
        IConfiguration configuration,
        IEnumerable<ISourceModule> sourceModules)
    {
        _configuration = configuration;

        var modules = new Dictionary<string, ISourceModule>(
            StringComparer.OrdinalIgnoreCase);
        foreach (var module in sourceModules)
        {
            if (!modules.TryAdd(module.SourceKey, module))
            {
                throw new InvalidOperationException(
                    $"Duplicate source module registration: '{module.SourceKey}'.");
            }
        }

        if (modules.Count == 0)
            throw new InvalidOperationException("No source modules are registered.");

        _sourceModules = modules;
    }

    private string ConnectionString =>
        _configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException(
            "ConnectionStrings:DefaultConnection is missing");

    public async Task InitializeAsync(CancellationToken ct)
    {
        const string sql = """
        CREATE TABLE IF NOT EXISTS source_runtime_settings (
          source TEXT PRIMARY KEY,
          enabled BOOLEAN NOT NULL DEFAULT TRUE,
          incremental_pages INT NOT NULL DEFAULT 1,
          worker_job_limit INT NOT NULL DEFAULT 3,
          page_concurrency INT NOT NULL DEFAULT 3,
          detail_concurrency INT NOT NULL DEFAULT 3,
          request_delay_ms INT NOT NULL DEFAULT 150,
          maximum_attempts INT NOT NULL DEFAULT 8,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          CONSTRAINT ck_source_settings_incremental_pages CHECK (incremental_pages BETWEEN 1 AND 10),
          CONSTRAINT ck_source_settings_worker_limit CHECK (worker_job_limit BETWEEN 1 AND 16),
          CONSTRAINT ck_source_settings_page_concurrency CHECK (page_concurrency BETWEEN 1 AND 8),
          CONSTRAINT ck_source_settings_detail_concurrency CHECK (detail_concurrency BETWEEN 1 AND 16),
          CONSTRAINT ck_source_settings_delay CHECK (request_delay_ms BETWEEN 0 AND 10000),
          CONSTRAINT ck_source_settings_attempts CHECK (maximum_attempts BETWEEN 1 AND 20)
        );

        ALTER TABLE source_runtime_settings
          ALTER COLUMN incremental_pages SET DEFAULT 1;

        CREATE TABLE IF NOT EXISTS audiobookred_migrations (
          migration_key TEXT PRIMARY KEY,
          applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );

        -- Версия до 0.20.0 использовала 3 как штатное значение RuTracker.
        -- Миграция выполняется один раз: после неё пользователь снова может
        -- вручную выбрать 3, и последующие перезапуски это значение не изменят.
        WITH applied AS (
          INSERT INTO audiobookred_migrations(migration_key)
          VALUES ('0.20.0-rutracker-incremental-pages-2')
          ON CONFLICT (migration_key) DO NOTHING
          RETURNING migration_key
        )
        UPDATE source_runtime_settings
        SET incremental_pages = 2,
            updated_at = NOW()
        WHERE source = 'rutracker'
          AND incremental_pages = 3
          AND EXISTS (SELECT 1 FROM applied);


        -- JacRed-like fast path scans only page 1 every hour. This one-time
        -- migration changes the previous stock value 2, while preserving any
        -- other explicit administrator choice.
        WITH applied AS (
          INSERT INTO audiobookred_migrations(migration_key)
          VALUES ('0.23.13-rutracker-incremental-pages-1')
          ON CONFLICT (migration_key) DO NOTHING
          RETURNING migration_key
        )
        UPDATE source_runtime_settings
        SET incremental_pages = 1,
            updated_at = NOW()
        WHERE source = 'rutracker'
          AND incremental_pages = 2
          AND EXISTS (SELECT 1 FROM applied);
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, cancellationToken: ct));

        foreach (var module in _sourceModules.Values
                     .OrderBy(value => value.SourceKey, StringComparer.Ordinal))
        {
            await EnsureDefaultsAsync(module.SourceKey, ct);
        }
    }

    public async Task EnsureDefaultsAsync(string source, CancellationToken ct)
    {
        var defaults = Defaults(source);

        const string sql = """
        INSERT INTO source_runtime_settings(
          source, enabled, incremental_pages, worker_job_limit,
          page_concurrency, detail_concurrency, request_delay_ms, maximum_attempts)
        VALUES (
          @Source, TRUE, @IncrementalPages, @WorkerJobLimit,
          @PageConcurrency, @DetailConcurrency, @RequestDelayMilliseconds, @MaximumAttempts)
        ON CONFLICT (source) DO NOTHING;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            defaults,
            cancellationToken: ct));
    }

    public async Task<SourceRuntimeSettings> GetAsync(
        string source,
        CancellationToken ct)
    {
        await EnsureDefaultsAsync(source, ct);

        const string sql = """
        SELECT source AS Source,
          enabled AS Enabled,
          incremental_pages AS IncrementalPages,
          worker_job_limit AS WorkerJobLimit,
          page_concurrency AS PageConcurrency,
          detail_concurrency AS DetailConcurrency,
          request_delay_ms AS RequestDelayMilliseconds,
          maximum_attempts AS MaximumAttempts,
          updated_at AS UpdatedAt
        FROM source_runtime_settings
        WHERE source = @Source;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.QuerySingleAsync<SourceRuntimeSettings>(
            new CommandDefinition(
                sql,
                new { Source = CanonicalSource(source) },
                cancellationToken: ct));
    }

    public async Task<SourceRuntimeSettings> UpdateAsync(
        string source,
        UpdateSourceRuntimeSettings update,
        CancellationToken ct)
    {
        var canonicalSource = CanonicalSource(source);
        var current = await GetAsync(canonicalSource, ct);
        var args = new
        {
            Source = canonicalSource,
            Enabled = update.Enabled ?? current.Enabled,
            IncrementalPages = Math.Clamp(
                update.IncrementalPages ?? current.IncrementalPages,
                1,
                10),
            WorkerJobLimit = Math.Clamp(
                update.WorkerJobLimit ?? current.WorkerJobLimit,
                1,
                16),
            PageConcurrency = Math.Clamp(
                update.PageConcurrency ?? current.PageConcurrency,
                1,
                8),
            DetailConcurrency = Math.Clamp(
                update.DetailConcurrency ?? current.DetailConcurrency,
                1,
                16),
            RequestDelayMilliseconds = Math.Clamp(
                update.RequestDelayMilliseconds
                ?? current.RequestDelayMilliseconds,
                0,
                10_000),
            MaximumAttempts = Math.Clamp(
                update.MaximumAttempts ?? current.MaximumAttempts,
                1,
                20)
        };

        const string sql = """
        UPDATE source_runtime_settings
        SET enabled = @Enabled,
            incremental_pages = @IncrementalPages,
            worker_job_limit = @WorkerJobLimit,
            page_concurrency = @PageConcurrency,
            detail_concurrency = @DetailConcurrency,
            request_delay_ms = @RequestDelayMilliseconds,
            maximum_attempts = @MaximumAttempts,
            updated_at = NOW()
        WHERE source = @Source
        RETURNING source AS Source,
          enabled AS Enabled,
          incremental_pages AS IncrementalPages,
          worker_job_limit AS WorkerJobLimit,
          page_concurrency AS PageConcurrency,
          detail_concurrency AS DetailConcurrency,
          request_delay_ms AS RequestDelayMilliseconds,
          maximum_attempts AS MaximumAttempts,
          updated_at AS UpdatedAt;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.QuerySingleAsync<SourceRuntimeSettings>(
            new CommandDefinition(
                sql,
                args,
                cancellationToken: ct));
    }

    private object Defaults(string source)
    {
        var module = Module(source);
        var defaults = module.RuntimeDefaults;

        return new
        {
            Source = module.SourceKey,
            defaults.IncrementalPages,
            defaults.WorkerJobLimit,
            defaults.PageConcurrency,
            defaults.DetailConcurrency,
            defaults.RequestDelayMilliseconds,
            defaults.MaximumAttempts
        };
    }

    private string CanonicalSource(string source) => Module(source).SourceKey;

    private ISourceModule Module(string source)
    {
        if (_sourceModules.TryGetValue(source.Trim(), out var module))
            return module;

        throw new KeyNotFoundException(
            $"Источник '{source}' не зарегистрирован.");
    }
}
