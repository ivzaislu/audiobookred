namespace AudioBookRed.Api.Models;

public sealed class AudiobookRelease
{
    public long Id { get; set; }
    public string Title { get; set; } = "";
    public string NormalizedTitle { get; set; } = "";
    public string Author { get; set; } = "";
    public string NormalizedAuthor { get; set; } = "";
    public string? Series { get; set; }
    public decimal? SeriesPosition { get; set; }
    public string[] Narrators { get; set; } = [];
    public string? Language { get; set; }
    public int? ReleaseYear { get; set; }
    public long? DurationSeconds { get; set; }
    public string? AudioFormat { get; set; }
    public int? BitrateKbps { get; set; }
    public string[] Genres { get; set; } = [];
    public string? Publisher { get; set; }
    public int? SampleRateHz { get; set; }
    public string? AudioChannels { get; set; }
    public string? BitrateMode { get; set; }
    public string? EditionType { get; set; }
    public string? EditionCategory { get; set; }
    public string? Music { get; set; }
    public int MetadataParserVersion { get; set; }
    public DateTime? MetadataParsedAt { get; set; }
    public bool? IsAbridged { get; set; }
    public bool? IsDramatized { get; set; }
    public string Source { get; set; } = "";
    public string SourceId { get; set; } = "";
    public string? SourceUrl { get; set; }
    public string? InfoHash { get; set; }
    public string? MagnetUri { get; set; }
    public long? SizeBytes { get; set; }
    public int? Seeders { get; set; }
    public int? Leechers { get; set; }
    public DateTime DiscoveredAt { get; set; }
    public DateTime UpdatedAt { get; set; }
}

public sealed record CreateAudiobookRelease(
    string RawTitle,
    string Source,
    string SourceId,
    string? SourceUrl,
    string? InfoHash,
    string? MagnetUri,
    long? SizeBytes,
    int? Seeders,
    int? Leechers);

public sealed record ParsedAudiobookTitle(
    string Title,
    string Author,
    string? Series,
    decimal? SeriesPosition,
    string[] Narrators,
    string? Language,
    int? ReleaseYear,
    string? AudioFormat,
    int? BitrateKbps,
    bool? IsAbridged,
    bool? IsDramatized);
