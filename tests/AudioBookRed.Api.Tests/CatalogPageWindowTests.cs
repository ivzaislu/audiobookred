using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Tests;

public sealed class CatalogPageWindowTests
{
    [Theory]
    [InlineData(3, 2, 2)]
    [InlineData(3, 5, 3)]
    [InlineData(1, 8, 1)]
    [InlineData(20, 12, 10)]
    public void EffectiveIncrementalPages_UsesConfiguredAndDiscoveredLimits(
        int requested,
        int discovered,
        int expected)
    {
        Assert.Equal(expected, CatalogPageWindow.EffectiveIncrementalPages(requested, discovered));
    }

    [Theory]
    [InlineData(3, 2, true)]
    [InlineData(2, 2, false)]
    [InlineData(1, 0, false)]
    public void IsOutOfRange_UsesKnownBoundary(
        int page,
        int knownLastPage,
        bool expected)
    {
        Assert.Equal(expected, CatalogPageWindow.IsOutOfRange(page, knownLastPage));
    }

    [Fact]
    public void IsOutOfRange_ReturnsFalseWhenBoundaryIsUnknown()
    {
        Assert.False(CatalogPageWindow.IsOutOfRange(3, null));
    }
}
