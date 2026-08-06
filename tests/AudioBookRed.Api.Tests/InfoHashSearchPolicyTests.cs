using AudioBookRed.Api.Data;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Tests;

public sealed class InfoHashSearchPolicyTests
{
    [Fact]
    public void Candidate_group_key_prefers_normalized_infohash()
    {
        var key = AudiobookRepository.BuildCandidateGroupKey(17, " ABCDEF1234 ");

        Assert.Equal("abcdef1234", key);
    }

    [Fact]
    public void Candidate_group_key_falls_back_to_release_id()
    {
        var key = AudiobookRepository.BuildCandidateGroupKey(17, " ");

        Assert.Equal("release:17", key);
    }

    [Theory]
    [InlineData(1, 0, 100)]
    [InlineData(100, 0, 400)]
    [InlineData(250, 1_000, 2_000)]
    public void Candidate_limit_overfetches_small_window_and_stays_bounded(
        int requestedLimit,
        int offset,
        int expected)
    {
        Assert.Equal(
            expected,
            AudiobookRepository.CalculateCandidateLimit(requestedLimit, offset));
    }

    [Fact]
    public void Candidate_order_uses_release_columns_without_grouped_aggregation()
    {
        var orderBy = AudiobookRepository.BuildCandidateOrderBy("seeders");

        Assert.StartsWith("r.seeders DESC", orderBy);
        Assert.DoesNotContain("grouped", orderBy, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void Merge_release_group_sums_peers_and_keeps_source_variants()
    {
        const string hash = "0123456789abcdef0123456789abcdef01234567";
        var merged = AudiobookRepository.MergeReleaseGroup(
        [
            Release(
                id: 1,
                source: "rutracker",
                hash: hash,
                seeders: 12,
                leechers: 2,
                parserVersion: 1,
                title: "Старая карточка"),
            Release(
                id: 2,
                source: "rutor",
                hash: hash,
                seeders: 28,
                leechers: 3,
                parserVersion: 2,
                title: "Полная карточка",
                publisher: "Издательство")
        ]);

        Assert.Equal(40, merged.Seeders);
        Assert.Equal(5, merged.Leechers);
        Assert.Equal(2, merged.Sources.Count);
        Assert.Equal(
            28,
            merged.Sources.Single(source => source.Source == "rutor").Seeders);
        Assert.Equal(
            12,
            merged.Sources.Single(source => source.Source == "rutracker").Seeders);
        Assert.Equal("Полная карточка", merged.Title);
        Assert.Equal(hash, merged.GroupKey);
    }

    [Fact]
    public void Merge_release_group_uses_one_latest_row_per_source()
    {
        const string hash = "abcdefabcdefabcdefabcdefabcdefabcdefabcd";
        var old = Release(1, "rutor", hash, 50, 4, 1, "Старая");
        old.UpdatedAt = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        var current = Release(2, "rutor", hash, 20, 1, 2, "Новая");
        current.UpdatedAt = new DateTime(2026, 2, 1, 0, 0, 0, DateTimeKind.Utc);
        var rutracker = Release(3, "rutracker", hash, 10, 2, 2, "Другая");

        var merged = AudiobookRepository.MergeReleaseGroup([old, current, rutracker]);

        Assert.Equal(30, merged.Seeders);
        Assert.Equal(3, merged.Leechers);
        Assert.Equal(2, merged.Sources.Count);
    }

    [Fact]
    public void Merge_magnet_keeps_one_xt_and_combines_tracker_announces()
    {
        const string hash = "0123456789abcdef0123456789abcdef01234567";
        var magnet = AudiobookRepository.MergeMagnetUris(
            hash,
            [
                $"magnet:?xt=urn:btih:{hash}&dn=Book&tr=udp%3A%2F%2Ftracker.one%3A80",
                $"magnet:?xt=urn:btih:{hash}&dn=Other&tr=https%3A%2F%2Ftracker.two%2Fannounce"
            ]);

        Assert.NotNull(magnet);
        Assert.StartsWith($"magnet:?xt=urn:btih:{hash}", magnet);
        Assert.Contains("tracker.one", Uri.UnescapeDataString(magnet!));
        Assert.Contains("tracker.two", Uri.UnescapeDataString(magnet!));
        Assert.Equal(1, CountParameter(magnet!, "xt"));
        Assert.Equal(1, CountParameter(magnet!, "dn"));
        Assert.Equal(2, CountParameter(magnet!, "tr"));
    }

    [Fact]
    public void Merged_candidates_are_sorted_by_summed_seeders()
    {
        var first = Release(1, "rutor", "a", 12, 0, 1, "A");
        var second = Release(2, "rutor", "b", 30, 0, 1, "B");

        var ordered = AudiobookRepository.SortMergedCandidates(
            [first, second],
            "seeders");

        Assert.Equal(2, ordered[0].Id);
        Assert.Equal(1, ordered[1].Id);
    }

    [Fact]
    public void Fast_candidate_index_is_a_required_migration()
    {
        Assert.Contains(
            "audiobook-fast-candidate-index-v3",
            DatabaseMigrationRunner.RequiredMigrationKeys);
    }

    private static AudiobookRelease Release(
        long id,
        string source,
        string hash,
        int seeders,
        int leechers,
        int parserVersion,
        string title,
        string? publisher = null) => new()
        {
            Id = id,
            Title = title,
            NormalizedTitle = title.ToLowerInvariant(),
            Author = "Автор",
            NormalizedAuthor = "автор",
            Source = source,
            SourceId = id.ToString(),
            InfoHash = hash,
            MagnetUri = $"magnet:?xt=urn:btih:{hash}&tr=https%3A%2F%2F{source}.example%2Fannounce",
            Seeders = seeders,
            Leechers = leechers,
            MetadataParserVersion = parserVersion,
            Publisher = publisher,
            UpdatedAt = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc),
            DiscoveredAt = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc)
        };

    private static int CountParameter(string magnet, string key) =>
        magnet[(magnet.IndexOf('?') + 1)..]
            .Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Count(part => part.StartsWith($"{key}=", StringComparison.OrdinalIgnoreCase));
}
