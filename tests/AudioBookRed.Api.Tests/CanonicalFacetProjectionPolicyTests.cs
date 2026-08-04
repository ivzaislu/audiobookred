using AudioBookRed.Api.Data;
using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class CanonicalFacetProjectionPolicyTests
{
    private const string RawTitle =
        "Зинина Татьяна - Карильский цикл. " +
        "Бастард королевской крови Часть 1 " +
        "[Юлия Булавина, 2024, 128 kbps, MP3]";

    [Fact]
    public void Preserves_explicit_source_metadata_when_syncing_facets()
    {
        var seriesNames = new SeriesNameParser();
        var parsedRaw = new TitleNormalizer(seriesNames).Parse(RawTitle);

        var projection = CanonicalFacetProjectionPolicy.Resolve(
            seriesNames,
            "Бастард королевской крови Часть 1",
            "Карильский цикл",
            null,
            parsedRaw,
            preserveReleaseMetadata: true);

        Assert.Equal(
            "Бастард королевской крови Часть 1",
            projection.Title);
        // SeriesNameParser canonicalizes the generic "цикл" suffix;
        // UpdateReleaseFields=false protects the persisted source metadata.
        Assert.Equal("Карильский", projection.Series?.DisplayName);
        Assert.Null(projection.Series?.Position);
        Assert.False(projection.UpdateReleaseFields);
    }

    [Fact]
    public void Keeps_legacy_raw_title_canonicalization_for_unparsed_rows()
    {
        var seriesNames = new SeriesNameParser();
        var parsedRaw = new TitleNormalizer(seriesNames).Parse(RawTitle);

        var projection = CanonicalFacetProjectionPolicy.Resolve(
            seriesNames,
            "Бастард королевской крови Часть 1",
            "Карильский цикл",
            null,
            parsedRaw,
            preserveReleaseMetadata: false);

        Assert.Equal(parsedRaw.Title, projection.Title);
        Assert.Equal(parsedRaw.Series, projection.Series?.DisplayName);
        Assert.Equal(parsedRaw.SeriesPosition, projection.Series?.Position);
        Assert.True(projection.UpdateReleaseFields);
    }
}
