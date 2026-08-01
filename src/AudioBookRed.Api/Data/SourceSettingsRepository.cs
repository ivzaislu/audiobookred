using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class SourceSettingsRepository(
    IConfiguration configuration,
    RuTrackerSourceDefinition ruTrackerDefaults)
{
    private string ConnectionString => configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is missing");

    public async Task InitializeAsync(CancellationToken ct)
    {
        const string sql = """
        CREATE TABLE IF NOT EXISTS source_runtime_settings (
          source TEXT PRIMARY KEY,
          enabled BOOLEAN NOT NULL DEFAULT TRUE,
          incremental_pages INT NOT NULL DEFAULT 3,
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
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, cancellationToken: ct));
        await EnsureDefaultsAsync(RuTrackerSourceDefinition.Key, ct);
    }

    public async Task EnsureDefaultsAsync(string source, CancellationToken ct)
    {
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
        await db.ExecuteAsync(new CommandDefinition(sql, Defaults(source), cancellationToken: ct));
    }

    public async Task<SourceRuntimeSettings> GetAsync(string source, CancellationToken ct)
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
        return await db.QuerySingleAsync<SourceRuntimeSettings>(new CommandDefinition(
            sql,
            new { Source = source },
            cancellationToken: ct));
    }

    public async Task<SourceRuntimeSettings> UpdateAsync(
        string source,
        UpdateSourceRuntimeSettings update,
        CancellationToken ct)
    {
        var current = await GetAsync(source, ct);
        var args = new
        {
            Source = source,
            Enabled = update.Enabled ?? current.Enabled,
            IncrementalPages = Math.Clamp(update.IncrementalPages ?? current.IncrementalPages, 1, 10),
            WorkerJobLimit = Math.Clamp(update.WorkerJobLimit ?? current.WorkerJobLimit, 1, 16),
            PageConcurrency = Math.Clamp(update.PageConcurrency ?? current.PageConcurrency, 1, 8),
            DetailConcurrency = Math.Clamp(update.DetailConcurrency ?? current.DetailConcurrency, 1, 16),
            RequestDelayMilliseconds = Math.Clamp(
                update.RequestDelayMilliseconds ?? current.RequestDelayMilliseconds,
                0,
                10_000),
            MaximumAttempts = Math.Clamp(update.MaximumAttempts ?? current.MaximumAttempts, 1, 20)
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
        return await db.QuerySingleAsync<SourceRuntimeSettings>(new CommandDefinition(
            sql,
            args,
            cancellationToken: ct));
    }

    private object Defaults(string source) => new
    {
        Source = source,
        IncrementalPages = ruTrackerDefaults.DefaultIncrementalPages,
        WorkerJobLimit = ruTrackerDefaults.DefaultWorkerJobLimit,
        PageConcurrency = ruTrackerDefaults.DefaultPageConcurrency,
        DetailConcurrency = ruTrackerDefaults.DefaultDetailConcurrency,
        RequestDelayMilliseconds = ruTrackerDefaults.DefaultRequestDelayMilliseconds,
        MaximumAttempts = ruTrackerDefaults.DefaultMaximumAttempts
    };
}
