using AudioBookRed.Api.Models;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class StatisticsRepository(IConfiguration configuration)
{
    private string ConnectionString => configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is missing");

    public async Task InitializeAsync(CancellationToken ct)
    {
        const string sql = """
        CREATE TABLE IF NOT EXISTS source_statistics (
          source TEXT PRIMARY KEY,
          release_count BIGINT NOT NULL DEFAULT 0,
          added_last_24_hours BIGINT NOT NULL DEFAULT 0,
          updated_last_24_hours BIGINT NOT NULL DEFAULT 0,
          last_discovered_at TIMESTAMPTZ NULL,
          last_updated_at TIMESTAMPTZ NULL,
          pending_jobs INT NOT NULL DEFAULT 0,
          running_jobs INT NOT NULL DEFAULT 0,
          retry_jobs INT NOT NULL DEFAULT 0,
          failed_jobs INT NOT NULL DEFAULT 0,
          last_successful_crawl_at TIMESTAMPTZ NULL,
          refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, cancellationToken: ct));
    }

    public async Task<DatabaseStatistics> RefreshAsync(CancellationToken ct)
    {
        const string refreshSql = """
        WITH release_stats AS (
          SELECT source,
            COUNT(*)::bigint AS release_count,
            COUNT(*) FILTER (WHERE discovered_at >= NOW() - INTERVAL '24 hours')::bigint AS added_last_24_hours,
            COUNT(*) FILTER (WHERE updated_at >= NOW() - INTERVAL '24 hours')::bigint AS updated_last_24_hours,
            MAX(discovered_at) AS last_discovered_at,
            MAX(updated_at) AS last_updated_at
          FROM audiobook_releases
          WHERE magnet_uri IS NOT NULL AND BTRIM(magnet_uri) <> ''
          GROUP BY source
        ), queue_stats AS (
          SELECT source,
            COUNT(*) FILTER (WHERE status = 'pending')::int AS pending_jobs,
            COUNT(*) FILTER (WHERE status = 'running')::int AS running_jobs,
            COUNT(*) FILTER (WHERE status = 'retry')::int AS retry_jobs,
            COUNT(*) FILTER (WHERE status = 'failed')::int AS failed_jobs
          FROM source_crawl_jobs
          GROUP BY source
        ), run_stats AS (
          SELECT source,
            MAX(completed_at) FILTER (WHERE status = 'completed') AS last_successful_crawl_at
          FROM source_crawl_runs
          GROUP BY source
        ), all_sources AS (
          SELECT source FROM release_stats
          UNION SELECT source FROM queue_stats
          UNION SELECT source FROM run_stats
          UNION SELECT source FROM source_runtime_settings
        )
        INSERT INTO source_statistics(
          source, release_count, added_last_24_hours, updated_last_24_hours,
          last_discovered_at, last_updated_at,
          pending_jobs, running_jobs, retry_jobs, failed_jobs,
          last_successful_crawl_at, refreshed_at)
        SELECT source.source,
          COALESCE(release.release_count, 0),
          COALESCE(release.added_last_24_hours, 0),
          COALESCE(release.updated_last_24_hours, 0),
          release.last_discovered_at,
          release.last_updated_at,
          COALESCE(queue.pending_jobs, 0),
          COALESCE(queue.running_jobs, 0),
          COALESCE(queue.retry_jobs, 0),
          COALESCE(queue.failed_jobs, 0),
          runs.last_successful_crawl_at,
          NOW()
        FROM all_sources source
        LEFT JOIN release_stats release ON release.source = source.source
        LEFT JOIN queue_stats queue ON queue.source = source.source
        LEFT JOIN run_stats runs ON runs.source = source.source
        ON CONFLICT (source) DO UPDATE SET
          release_count = EXCLUDED.release_count,
          added_last_24_hours = EXCLUDED.added_last_24_hours,
          updated_last_24_hours = EXCLUDED.updated_last_24_hours,
          last_discovered_at = EXCLUDED.last_discovered_at,
          last_updated_at = EXCLUDED.last_updated_at,
          pending_jobs = EXCLUDED.pending_jobs,
          running_jobs = EXCLUDED.running_jobs,
          retry_jobs = EXCLUDED.retry_jobs,
          failed_jobs = EXCLUDED.failed_jobs,
          last_successful_crawl_at = EXCLUDED.last_successful_crawl_at,
          refreshed_at = EXCLUDED.refreshed_at;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(refreshSql, cancellationToken: ct));
        return await ReadAsync(db, ct);
    }

    public async Task<DatabaseStatistics> GetAsync(CancellationToken ct)
    {
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        var refreshedAt = await db.ExecuteScalarAsync<DateTime?>(new CommandDefinition(
            "SELECT MAX(refreshed_at) FROM source_statistics;",
            cancellationToken: ct));
        if (refreshedAt is null || ToUtcDateTime(refreshedAt.Value) < DateTime.UtcNow.AddMinutes(-10))
            return await RefreshAsync(ct);
        return await ReadAsync(db, ct);
    }

    private static async Task<DatabaseStatistics> ReadAsync(NpgsqlConnection db, CancellationToken ct)
    {
        const string sql = """
        SELECT COALESCE(SUM(release_count), 0)::bigint AS Total,
          COALESCE(SUM(added_last_24_hours), 0)::bigint AS AddedLast24Hours,
          COALESCE(SUM(updated_last_24_hours), 0)::bigint AS UpdatedLast24Hours,
          MAX(refreshed_at) AS RefreshedAt
        FROM source_statistics;

        SELECT source AS Source,
          release_count AS Count,
          added_last_24_hours AS AddedLast24Hours,
          updated_last_24_hours AS UpdatedLast24Hours,
          last_discovered_at AS LastDiscoveredAt,
          last_updated_at AS LastUpdatedAt,
          pending_jobs AS PendingJobs,
          running_jobs AS RunningJobs,
          retry_jobs AS RetryJobs,
          failed_jobs AS FailedJobs,
          last_successful_crawl_at AS LastSuccessfulCrawlAt,
          refreshed_at AS RefreshedAt
        FROM source_statistics
        ORDER BY release_count DESC, source;
        """;

        using var result = await db.QueryMultipleAsync(new CommandDefinition(sql, cancellationToken: ct));
        var summary = await result.ReadSingleAsync<SummaryDbRow>();
        var dbSources = (await result.ReadAsync<SourceDbRow>()).AsList();
        var sources = dbSources.Select(row => new DatabaseSourceStatistics
        {
            Source = row.Source,
            Count = row.Count,
            AddedLast24Hours = row.AddedLast24Hours,
            UpdatedLast24Hours = row.UpdatedLast24Hours,
            LastDiscoveredAt = ToDateTimeOffset(row.LastDiscoveredAt),
            LastUpdatedAt = ToDateTimeOffset(row.LastUpdatedAt),
            PendingJobs = row.PendingJobs,
            RunningJobs = row.RunningJobs,
            RetryJobs = row.RetryJobs,
            FailedJobs = row.FailedJobs,
            LastSuccessfulCrawlAt = ToDateTimeOffset(row.LastSuccessfulCrawlAt),
            RefreshedAt = ToDateTimeOffset(row.RefreshedAt)
        }).ToList();

        return new DatabaseStatistics(
            summary.Total,
            summary.AddedLast24Hours,
            summary.UpdatedLast24Hours,
            ToDateTimeOffset(summary.RefreshedAt),
            sources);
    }

    private static DateTime ToUtcDateTime(DateTime value) => value.Kind switch
    {
        DateTimeKind.Utc => value,
        DateTimeKind.Local => value.ToUniversalTime(),
        _ => DateTime.SpecifyKind(value, DateTimeKind.Utc)
    };

    private static DateTimeOffset ToDateTimeOffset(DateTime value) =>
        new(ToUtcDateTime(value));

    private static DateTimeOffset? ToDateTimeOffset(DateTime? value) =>
        value is null ? null : ToDateTimeOffset(value.Value);

    private sealed class SummaryDbRow
    {
        public long Total { get; set; }
        public long AddedLast24Hours { get; set; }
        public long UpdatedLast24Hours { get; set; }
        public DateTime? RefreshedAt { get; set; }
    }

    private sealed class SourceDbRow
    {
        public string Source { get; set; } = "";
        public long Count { get; set; }
        public long AddedLast24Hours { get; set; }
        public long UpdatedLast24Hours { get; set; }
        public DateTime? LastDiscoveredAt { get; set; }
        public DateTime? LastUpdatedAt { get; set; }
        public int PendingJobs { get; set; }
        public int RunningJobs { get; set; }
        public int RetryJobs { get; set; }
        public int FailedJobs { get; set; }
        public DateTime? LastSuccessfulCrawlAt { get; set; }
        public DateTime RefreshedAt { get; set; }
    }
}
