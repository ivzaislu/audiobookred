using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Tests;

public sealed class SourceMetadataReparsePolicyTests
{
    [Fact]
    public void Normalize_topic_ids_preserves_order_and_removes_duplicates()
    {
        var result = SourceMetadataReparsePolicy.NormalizeTopicIds(
            [6889513, 6809133, 6889513, 5887830]);

        Assert.Equal(new long[] { 6889513, 6809133, 5887830 }, result);
    }

    [Fact]
    public void Normalize_topic_ids_rejects_empty_input()
    {
        var error = Assert.Throws<ArgumentException>(() =>
            SourceMetadataReparsePolicy.NormalizeTopicIds([]));

        Assert.Contains("хотя бы один", error.Message);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    public void Normalize_topic_ids_rejects_non_positive_values(long topicId)
    {
        var error = Assert.Throws<ArgumentException>(() =>
            SourceMetadataReparsePolicy.NormalizeTopicIds([topicId]));

        Assert.Contains("положительным", error.Message);
    }

    [Fact]
    public void Normalize_topic_ids_rejects_more_than_one_hundred_unique_values()
    {
        var values = Enumerable.Range(1, 101).Select(value => (long)value);

        var error = Assert.Throws<ArgumentException>(() =>
            SourceMetadataReparsePolicy.NormalizeTopicIds(values));

        Assert.Contains("не более 100", error.Message);
    }

    [Fact]
    public void Batch_limit_defaults_to_twenty_five()
    {
        Assert.Equal(
            25,
            SourceMetadataReparsePolicy.NormalizeBatchLimit(null));
    }

    [Theory]
    [InlineData(1)]
    [InlineData(100)]
    public void Batch_limit_accepts_supported_boundaries(int limit)
    {
        Assert.Equal(
            limit,
            SourceMetadataReparsePolicy.NormalizeBatchLimit(limit));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(101)]
    public void Batch_limit_rejects_values_outside_supported_range(int limit)
    {
        var error = Assert.Throws<ArgumentOutOfRangeException>(() =>
            SourceMetadataReparsePolicy.NormalizeBatchLimit(limit));

        Assert.Contains("1..100", error.Message);
    }
}
