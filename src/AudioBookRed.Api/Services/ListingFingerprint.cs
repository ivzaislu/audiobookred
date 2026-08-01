using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public static class ListingFingerprint
{
    public static string ForListing(RuTrackerSearchItem item) => Hash(
        item.Title,
        item.SizeBytes.ToString(CultureInfo.InvariantCulture),
        item.Seeders.ToString(CultureInfo.InvariantCulture),
        item.Leechers.ToString(CultureInfo.InvariantCulture));

    // Изменение только числа сидов/личей не требует повторного открытия темы.
    public static string ForDetails(RuTrackerSearchItem item) => Hash(
        item.Title,
        item.SizeBytes.ToString(CultureInfo.InvariantCulture));

    // Atom не содержит достоверных сидов/личей. Его fingerprint отражает только
    // данные, изменение которых действительно может потребовать повторной detail-проверки.
    public static string ForAtom(RuTrackerAtomEntry entry) => Hash(
        entry.Title,
        entry.SizeBytes?.ToString(CultureInfo.InvariantCulture) ?? string.Empty,
        entry.TopicUrl.Trim());

    private static string Hash(params string[] values)
    {
        var normalized = string.Join('\u001f', values.Select(value =>
            string.Join(' ', (value ?? string.Empty)
                .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(normalized))).ToLowerInvariant();
    }
}
