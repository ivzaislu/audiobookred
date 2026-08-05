using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class ListingFingerprintTests
{
    [Fact]
    public void Detail_fingerprint_ignores_seed_and_leech_changes()
    {
        var first = Item();
        var second = first with { Seeders = 999, Leechers = 100 };

        Assert.Equal(
            ListingFingerprint.ForDetails(first),
            ListingFingerprint.ForDetails(second));
        Assert.NotEqual(
            ListingFingerprint.ForListing(first),
            ListingFingerprint.ForListing(second));
    }

    [Fact]
    public void Detail_fingerprint_changes_when_title_changes()
    {
        var first = Item();
        var second = first with { Title = "Author - Revised Book" };

        Assert.NotEqual(
            ListingFingerprint.ForDetails(first),
            ListingFingerprint.ForDetails(second));
    }

    [Fact]
    public void Detail_fingerprint_changes_when_size_changes()
    {
        var first = Item();
        var second = first with { SizeBytes = first.SizeBytes + 1 };

        Assert.NotEqual(
            ListingFingerprint.ForDetails(first),
            ListingFingerprint.ForDetails(second));
    }

    private static RuTrackerSearchItem Item() => new(
        42,
        "Author - Book",
        "Audiobooks",
        "https://rutracker.org/forum/viewtopic.php?t=42",
        1_234_567,
        10,
        2);
}
