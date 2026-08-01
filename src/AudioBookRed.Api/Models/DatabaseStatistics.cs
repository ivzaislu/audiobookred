namespace AudioBookRed.Api.Models;

public sealed class DatabaseSourceStatistics
{
    public string Source { get; set; } = "";
    public long Count { get; set; }
    public long AddedLast24Hours { get; set; }
    public long UpdatedLast24Hours { get; set; }
    public DateTimeOffset? LastDiscoveredAt { get; set; }
    public DateTimeOffset? LastUpdatedAt { get; set; }
    public int PendingJobs { get; set; }
    public int RunningJobs { get; set; }
    public int RetryJobs { get; set; }
    public int FailedJobs { get; set; }
    public DateTimeOffset? LastSuccessfulCrawlAt { get; set; }
    public DateTimeOffset RefreshedAt { get; set; }
}

public sealed record DatabaseStatistics(
    long Total,
    long AddedLast24Hours,
    long UpdatedLast24Hours,
    DateTimeOffset? RefreshedAt,
    IReadOnlyList<DatabaseSourceStatistics> Sources);
