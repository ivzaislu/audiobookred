using AudioBookRed.Api.Data;

namespace AudioBookRed.Api.Tests;

public sealed class InfoHashSearchPolicyTests
{
    [Fact]
    public void Group_key_uses_normalized_infohash_without_runtime_lower()
    {
        var expression = AudiobookRepository.BuildGroupKeyExpression("r");

        Assert.Contains("r.info_hash", expression);
        Assert.False(expression.Contains("LOWER", StringComparison.OrdinalIgnoreCase));
        Assert.Contains("release:", expression);
    }

    [Theory]
    [InlineData("seeders")]
    [InlineData("leechers")]
    public void Peer_counts_are_summed_across_sources(string column)
    {
        var expression = AudiobookRepository.BuildPeerSumExpression("peer", column);

        Assert.Equal($"SUM(COALESCE(peer.{column}, 0))::bigint", expression);
    }

    [Fact]
    public void Seeder_sort_uses_grouped_sum()
    {
        var orderBy = AudiobookRepository.BuildGroupedOrderBy("seeders");

        Assert.StartsWith("g.grouped_seeders DESC", orderBy);
    }

    [Fact]
    public void Fast_infohash_index_is_a_required_migration()
    {
        Assert.Contains(
            "audiobook-infohash-search-index-v2",
            DatabaseMigrationRunner.RequiredMigrationKeys);
    }
}
