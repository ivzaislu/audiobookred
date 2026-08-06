namespace AudioBookRed.Api.Models;

public sealed record RutorListingItem(
    long TopicId,
    string Title,
    string Category,
    string TopicUrl,
    long SizeBytes,
    int Seeders,
    int Leechers,
    string InfoHash,
    string MagnetUri) : ISourceListingItem;

public sealed record RutorListingPage(
    int CategoryId,
    int Page,
    int TotalPages,
    bool HasNextPage,
    int SourceRows,
    IReadOnlyList<RutorListingItem> Items) : ISourceListingPage
{
    public int ItemCount => Items.Count;
}

public sealed record RutorDetailValue(
    string MagnetUri,
    string InfoHash,
    RuTrackerTopicMetadata Metadata);
