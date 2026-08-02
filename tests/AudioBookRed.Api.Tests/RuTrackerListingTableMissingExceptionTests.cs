using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class RuTrackerListingTableMissingExceptionTests
{
    [Fact]
    public void Constructor_PreservesBoundaryContext()
    {
        var exception = new RuTrackerListingTableMissingException(2342, 3, "rutracker.org");

        Assert.Equal(2342, exception.CategoryId);
        Assert.Equal(3, exception.Page);
        Assert.Equal("rutracker.org", exception.PageTitle);
        Assert.Contains("категории 2342", exception.Message);
        Assert.Contains("страницы 3", exception.Message);
    }
}
