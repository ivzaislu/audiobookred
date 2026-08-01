using System.Security.Cryptography;
using System.Text;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public static class ListingFingerprint
{
    public static string ForListing(RuTrackerSearchItem item) => Hash(
        item.Title,
        item.SizeBytes.ToString(),
        item.Seeders.ToString(),
        item.Leechers.ToString());

    // Изменение только числа сидов/личей не требует повторного открытия темы.
    public static string ForDetails(RuTrackerSearchItem item) => Hash(
        item.Title,
        item.SizeBytes.ToString());

    private static string Hash(params string[] values)
    {
        var normalized = string.Join('\u001f', values.Select(value =>
            string.Join(' ', (value ?? string.Empty)
                .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(normalized))).ToLowerInvariant();
    }
}
