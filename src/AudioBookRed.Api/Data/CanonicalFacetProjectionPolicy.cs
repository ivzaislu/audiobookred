using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Data;

internal static class CanonicalFacetProjectionPolicy
{
    internal static CanonicalFacetProjection Resolve(
        SeriesNameParser seriesNames,
        string currentTitle,
        string? currentSeries,
        decimal? currentSeriesPosition,
        ParsedAudiobookTitle? parsedRaw,
        bool preserveReleaseMetadata)
    {
        ArgumentNullException.ThrowIfNull(seriesNames);
        ArgumentException.ThrowIfNullOrWhiteSpace(currentTitle);

        if (preserveReleaseMetadata)
        {
            return new CanonicalFacetProjection(
                currentTitle,
                seriesNames.Parse(currentSeries, currentSeriesPosition),
                UpdateReleaseFields: false);
        }

        var canonicalSeries = !string.IsNullOrWhiteSpace(parsedRaw?.Series)
            ? seriesNames.Parse(parsedRaw.Series, parsedRaw.SeriesPosition)
            : seriesNames.Parse(currentSeries, currentSeriesPosition);
        var canonicalTitle =
            parsedRaw?.Series is not null &&
            !string.IsNullOrWhiteSpace(parsedRaw.Title)
                ? parsedRaw.Title
                : currentTitle;

        return new CanonicalFacetProjection(
            canonicalTitle,
            canonicalSeries,
            UpdateReleaseFields: true);
    }
}

internal sealed record CanonicalFacetProjection(
    string Title,
    SeriesNamePart? Series,
    bool UpdateReleaseFields);
