using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class ListingFingerprintTests
{
    [Fact]
    public void Detail_fingerprint_ignores_seed_and_leech_changes()
    {
        var first = new RuTrackerSearchItem(
            42,
            "Author - Book",
            "Audiobooks",
            "https://rutracker.org/forum/viewtopic.php?t=42",
            1_234_567,
            10,
            2);
        var second = first with { Seeders = 999, Leechers = 100 };

        Assert.Equal(
            ListingFingerprint.ForDetails(first),
            ListingFingerprint.ForDetails(second));
        Assert.NotEqual(
            ListingFingerprint.ForListing(first),
            ListingFingerprint.ForListing(second));
    }

    [Fact]
    public void Atom_fingerprint_ignores_feed_timestamp_and_publisher()
    {
        var first = new RuTrackerAtomEntry(
            42,
            "Author - Book",
            "https://rutracker.org/forum/viewtopic.php?t=42",
            1_234_567,
            DateTimeOffset.Parse("2026-08-01T10:00:00Z"),
            "first",
            574);
        var second = first with
        {
            UpdatedAt = DateTimeOffset.Parse("2026-08-02T10:00:00Z"),
            Publisher = "second"
        };

        Assert.Equal(
            ListingFingerprint.ForAtom(first),
            ListingFingerprint.ForAtom(second));
    }
}
