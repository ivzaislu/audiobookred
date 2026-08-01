using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Compatibility;

public static partial class TorznabXmlFormatter
{
    private const int AudioCategory = 3000;
    private const int AudiobookCategory = 3030;
    private static readonly XNamespace AtomNamespace = "http://www.w3.org/2005/Atom";
    private static readonly XNamespace TorznabNamespace = "http://torznab.com/schemas/2015/feed";
    private static readonly XNamespace NewznabNamespace = "http://www.newznab.com/DTD/2010/feeds/attributes/";

    public static string CreateCapabilities(string apiUrl)
    {
        var document = new XDocument(
            new XDeclaration("1.0", "UTF-8", null),
            new XElement("caps",
                new XElement("server",
                    new XAttribute("version", "1.0"),
                    new XAttribute("title", "AudioBookRed"),
                    new XAttribute("strapline", "Audiobook Torznab API"),
                    new XAttribute("url", apiUrl)),
                new XElement("limits",
                    new XAttribute("max", 250),
                    new XAttribute("default", 100)),
                new XElement("searching",
                    new XElement("search",
                        new XAttribute("available", "yes"),
                        new XAttribute("supportedParams", "q")),
                    new XElement("book-search",
                        new XAttribute("available", "yes"),
                        new XAttribute("supportedParams", "q,title,author,year")),
                    new XElement("music-search",
                        new XAttribute("available", "yes"),
                        new XAttribute("supportedParams", "q,album,artist,year")),
                    new XElement("audio-search",
                        new XAttribute("available", "yes"),
                        new XAttribute("supportedParams", "q,album,artist,year")),
                    new XElement("movie-search",
                        new XAttribute("available", "no"),
                        new XAttribute("supportedParams", "q")),
                    new XElement("tv-search",
                        new XAttribute("available", "no"),
                        new XAttribute("supportedParams", "q"))),
                new XElement("categories",
                    new XElement("category",
                        new XAttribute("id", AudioCategory),
                        new XAttribute("name", "Audio"),
                        new XElement("subcat",
                            new XAttribute("id", AudiobookCategory),
                            new XAttribute("name", "Audio/Audiobook"))))));

        return Serialize(document);
    }

    public static string CreateIndexerList()
    {
        var document = new XDocument(
            new XDeclaration("1.0", "UTF-8", null),
            new XElement("indexers",
                new XElement("indexer",
                    new XAttribute("id", "audiobookred"),
                    new XAttribute("configured", "true"),
                    new XElement("title", "AudioBookRed"),
                    new XElement("description", "Audiobook metadata and magnet aggregator"),
                    new XElement("link", "https://github.com/ivzaislu/audiobookred"),
                    new XElement("language", "ru-RU"),
                    new XElement("type", "public"))));

        return Serialize(document);
    }

    public static string CreateSearchFeed(
        IReadOnlyList<AudiobookRelease> releases,
        string siteOrigin,
        string apiUrl,
        int offset,
        long total)
    {
        var channel = new XElement("channel",
            new XElement(AtomNamespace + "link",
                new XAttribute("href", apiUrl),
                new XAttribute("rel", "self"),
                new XAttribute("type", "application/rss+xml")),
            new XElement("title", "AudioBookRed"),
            new XElement("description", "AudioBookRed Torznab API"),
            new XElement("link", siteOrigin.TrimEnd('/') + "/ui/"),
            new XElement("language", "ru-RU"),
            new XElement("category", "search"),
            new XElement(NewznabNamespace + "response",
                new XAttribute("offset", offset),
                new XAttribute("total", total)));

        foreach (var release in releases)
            channel.Add(CreateItem(release));

        var document = new XDocument(
            new XDeclaration("1.0", "UTF-8", null),
            new XElement("rss",
                new XAttribute("version", "2.0"),
                new XAttribute(XNamespace.Xmlns + "atom", AtomNamespace.NamespaceName),
                new XAttribute(XNamespace.Xmlns + "torznab", TorznabNamespace.NamespaceName),
                new XAttribute(XNamespace.Xmlns + "newznab", NewznabNamespace.NamespaceName),
                channel));

        return Serialize(document);
    }

    public static string CreateError(string code, string description)
    {
        var document = new XDocument(
            new XDeclaration("1.0", "UTF-8", null),
            new XElement("error",
                new XAttribute("code", code),
                new XAttribute("description", description)));
        return Serialize(document);
    }

    private static XElement CreateItem(AudiobookRelease release)
    {
        var magnet = release.MagnetUri?.Trim() ?? string.Empty;
        var infoHash = NormalizeInfoHash(release.InfoHash) ?? ExtractInfoHash(magnet);
        var displayTitle = BuildDisplayTitle(release);
        var guid = infoHash ?? StableGuid($"{release.Source}:{release.SourceId}:{displayTitle}");
        var size = Math.Max(0, release.SizeBytes ?? 0);
        var seeders = Math.Max(0, release.Seeders ?? 0);
        var leechers = Math.Max(0, release.Leechers ?? 0);
        var peers = seeders + leechers;
        var published = NormalizeDate(release.DiscoveredAt, release.UpdatedAt);
        var source = string.IsNullOrWhiteSpace(release.Source) ? "AudioBookRed" : release.Source.Trim();

        var item = new XElement("item",
            new XElement("title", displayTitle),
            new XElement("guid", new XAttribute("isPermaLink", "false"), guid),
            new XElement("jackettindexer", new XAttribute("id", "audiobookred"), source),
            new XElement("link", magnet),
            new XElement("pubDate", published.ToString("r", CultureInfo.InvariantCulture)),
            new XElement("category", AudiobookCategory),
            new XElement("size", size),
            new XElement("enclosure",
                new XAttribute("url", magnet),
                new XAttribute("length", size),
                new XAttribute("type", "application/x-bittorrent;x-scheme-handler/magnet")));

        if (Uri.TryCreate(release.SourceUrl, UriKind.Absolute, out var sourceUrl))
            item.Add(new XElement("comments", sourceUrl.ToString()));

        AddAttribute(item, "category", AudiobookCategory);
        AddAttribute(item, "magneturl", magnet);
        AddAttribute(item, "infohash", infoHash);
        AddAttribute(item, "size", size);
        AddAttribute(item, "seeders", seeders);
        AddAttribute(item, "leechers", leechers);
        AddAttribute(item, "peers", peers);
        AddAttribute(item, "downloadvolumefactor", 1);
        AddAttribute(item, "uploadvolumefactor", 1);
        AddAttribute(item, "site", source);
        AddAttribute(item, "language", NormalizeLanguage(release.Language, displayTitle));
        AddAttribute(item, "author", release.Author);
        AddAttribute(item, "booktitle", release.Title);
        AddAttribute(item, "year", release.ReleaseYear);
        AddAttribute(item, "series", release.Series);
        AddAttribute(item, "audiobook", "true");
        AddAttribute(item, "format", release.AudioFormat);
        AddAttribute(item, "bitrate", release.BitrateKbps);
        if (release.Narrators is { Length: > 0 })
            AddAttribute(item, "narrator", string.Join(", ", release.Narrators));

        return item;
    }

    private static void AddAttribute(XElement item, string name, object? value)
    {
        var text = Convert.ToString(value, CultureInfo.InvariantCulture)?.Trim();
        if (string.IsNullOrEmpty(text))
            return;

        item.Add(new XElement(TorznabNamespace + "attr",
            new XAttribute("name", name),
            new XAttribute("value", text)));
    }

    private static string BuildDisplayTitle(AudiobookRelease release)
    {
        var title = string.IsNullOrWhiteSpace(release.Title) ? "Без названия" : release.Title.Trim();
        var author = release.Author?.Trim();
        var display = !string.IsNullOrWhiteSpace(author) &&
                      !title.Contains(author, StringComparison.OrdinalIgnoreCase)
            ? $"{author} — {title}"
            : title;

        if (!string.IsNullOrWhiteSpace(release.Series))
        {
            var position = release.SeriesPosition is null
                ? string.Empty
                : $" #{release.SeriesPosition.Value.ToString("0.##", CultureInfo.InvariantCulture)}";
            display += $" [{release.Series.Trim()}{position}]";
        }

        return display;
    }

    private static DateTime NormalizeDate(DateTime discoveredAt, DateTime updatedAt)
    {
        var value = discoveredAt.Year >= 2000 ? discoveredAt : updatedAt;
        if (value.Year < 2000)
            return DateTime.UtcNow;
        if (value.Kind == DateTimeKind.Unspecified)
            value = DateTime.SpecifyKind(value, DateTimeKind.Utc);
        return value.ToUniversalTime();
    }

    private static string NormalizeLanguage(string? language, string title)
    {
        var value = language?.Trim().ToLowerInvariant();
        return value switch
        {
            "ru" or "rus" or "ru-ru" => "ru-RU",
            "en" or "eng" or "en-us" or "en-gb" => "en-US",
            { Length: > 0 } => value,
            _ => CyrillicRegex().IsMatch(title) ? "ru-RU" : "und"
        };
    }

    private static string? NormalizeInfoHash(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;
        var normalized = value.Trim();
        return InfoHashOnlyRegex().IsMatch(normalized) ? normalized.ToLowerInvariant() : null;
    }

    private static string? ExtractInfoHash(string magnet)
    {
        if (string.IsNullOrWhiteSpace(magnet))
            return null;
        string decoded;
        try
        {
            decoded = Uri.UnescapeDataString(magnet);
        }
        catch (UriFormatException)
        {
            decoded = magnet;
        }

        var match = InfoHashFromMagnetRegex().Match(decoded);
        return match.Success ? match.Groups[1].Value.ToLowerInvariant() : null;
    }

    private static string StableGuid(string value)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(hash).ToLowerInvariant()[..40];
    }

    private static string Serialize(XDocument document) =>
        document.Declaration + Environment.NewLine + document.ToString(SaveOptions.DisableFormatting);

    [GeneratedRegex("[а-яА-ЯёЁ]")]
    private static partial Regex CyrillicRegex();

    [GeneratedRegex("^[a-fA-F0-9]{40}$|^[a-zA-Z2-7]{32}$")]
    private static partial Regex InfoHashOnlyRegex();

    [GeneratedRegex("(?:urn:btih:|btih:)([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})", RegexOptions.IgnoreCase)]
    private static partial Regex InfoHashFromMagnetRegex();
}
