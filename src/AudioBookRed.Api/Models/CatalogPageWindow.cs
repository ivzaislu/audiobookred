namespace AudioBookRed.Api.Models;

public static class CatalogPageWindow
{
    public static int EffectiveIncrementalPages(int requestedPages, int discoveredPages)
    {
        if (discoveredPages < 1)
            throw new ArgumentOutOfRangeException(
                nameof(discoveredPages),
                "Discovered page count must be at least 1.");

        return Math.Min(Math.Clamp(requestedPages, 1, 10), discoveredPages);
    }

    public static bool IsOutOfRange(int page, int? knownLastPage) =>
        knownLastPage.HasValue &&
        knownLastPage.Value > 0 &&
        page > knownLastPage.Value;
}
