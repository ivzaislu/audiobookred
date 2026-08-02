using System.Diagnostics;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class DatabaseReadinessProbe(
    IConfiguration configuration,
    ILogger<DatabaseReadinessProbe> logger)
{
    private string ConnectionString => configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is missing");

    public async Task<DatabaseReadinessStatus> CheckAsync(CancellationToken ct)
    {
        var stopwatch = Stopwatch.StartNew();

        try
        {
            var connectionSettings = new NpgsqlConnectionStringBuilder(ConnectionString)
            {
                Timeout = 3
            };
            await using var db = new NpgsqlConnection(connectionSettings.ConnectionString);
            await db.OpenAsync(ct);

            var required = DatabaseMigrationRunner.RequiredMigrationKeys.ToArray();
            var registered = (await db.QueryAsync<string>(new CommandDefinition(
                """
                SELECT migration_key
                FROM app_migrations
                WHERE migration_key = ANY(@Keys);
                """,
                new { Keys = required },
                commandTimeout: 5,
                cancellationToken: ct))).ToHashSet(StringComparer.Ordinal);

            var missing = required
                .Where(key => !registered.Contains(key))
                .ToArray();

            stopwatch.Stop();
            return new DatabaseReadinessStatus(
                missing.Length == 0,
                missing,
                stopwatch.ElapsedMilliseconds,
                null);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            stopwatch.Stop();
            logger.LogWarning("Database readiness check timed out.");
            return new DatabaseReadinessStatus(
                false,
                DatabaseMigrationRunner.RequiredMigrationKeys,
                stopwatch.ElapsedMilliseconds,
                "database_timeout");
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            stopwatch.Stop();
            logger.LogWarning(ex, "Database readiness check failed.");
            return new DatabaseReadinessStatus(
                false,
                DatabaseMigrationRunner.RequiredMigrationKeys,
                stopwatch.ElapsedMilliseconds,
                "database_unavailable");
        }
    }
}

public sealed record DatabaseReadinessStatus(
    bool Ready,
    IReadOnlyList<string> MissingMigrations,
    long DurationMilliseconds,
    string? Error);
