using System.Globalization;
using System.Net;
using System.Text.RegularExpressions;
using AngleSharp.Dom;
using AngleSharp.Html.Parser;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed partial class RutorDetailParser(
    TitleNormalizer titleNormalizer,
    PersonNameParser personNames)
{
    public const int CurrentParserVersion = 2;

    public RutorDetailValue Parse(string html, string fallbackTitle)
    {
        ArgumentNullException.ThrowIfNull(html);

        var document = new HtmlParser().ParseDocument(html);
        var safeFallbackTitle = string.IsNullOrWhiteSpace(fallbackTitle)
            ? "Неизвестный автор - Неизвестное произведение"
            : RutorHtmlParser.NormalizeAudioTokens(fallbackTitle);
        var fallback = titleNormalizer.Parse(safeFallbackTitle);

        var detailCell = FindDetailCell(document)
            ?? throw new InvalidDataException("Rutor detail page does not contain table#details metadata.");
        var fields = ExtractFields(detailCell);

        var magnet = WebUtility.HtmlDecode(
            document.QuerySelector("#download a[href^='magnet:?']")?.GetAttribute("href")
            ?? string.Empty);
        var infoHash = ParseInfoHash(magnet);
        if (string.IsNullOrWhiteSpace(magnet) || string.IsNullOrWhiteSpace(infoHash))
            throw new InvalidDataException("Rutor detail page does not contain a valid magnet link.");

        var explicitTitle = Value(fields, RutorField.Title);
        var explicitAuthor = Value(fields, RutorField.Author);
        var explicitNarrators = Value(fields, RutorField.Narrators);
        var year = ParseYear(Value(fields, RutorField.ReleaseYear)) ?? fallback.ReleaseYear;
        var formatSource = string.Join(
            ' ',
            new[]
            {
                Value(fields, RutorField.AudioFormat),
                Value(fields, RutorField.Bitrate)
            }.Where(value => !string.IsNullOrWhiteSpace(value))
                .Select(value => value!));
        var audioFormat = ParseAudioFormat(formatSource) ??
            NormalizeAudioFormat(fallback.AudioFormat);
        var bitrate = ParseBitrate(formatSource) ?? fallback.BitrateKbps;
        var narrators = ParseNarrators(explicitNarrators, fallback.Narrators);

        var cleanExplicitTitle = CleanValue(explicitTitle);
        var explicitParsed = string.IsNullOrWhiteSpace(cleanExplicitTitle)
            ? fallback
            : titleNormalizer.Parse($"{CleanValue(explicitAuthor) ?? fallback.Author} - {RutorHtmlParser.NormalizeAudioTokens(cleanExplicitTitle)}");
        var displayTitle = explicitParsed.Series is not null
            ? explicitParsed.Title
            : CleanAudioFormatSuffix(cleanExplicitTitle) ?? fallback.Title;

        var parsedTitle = new ParsedAudiobookTitle(
            displayTitle,
            CleanValue(explicitAuthor) ?? explicitParsed.Author,
            explicitParsed.Series ?? fallback.Series,
            explicitParsed.SeriesPosition ?? fallback.SeriesPosition,
            narrators,
            fallback.Language,
            year,
            audioFormat,
            bitrate,
            fallback.IsAbridged,
            fallback.IsDramatized);

        var metadata = new RuTrackerTopicMetadata(
            parsedTitle,
            ParseDuration(Value(fields, RutorField.Duration)),
            ParseGenres(Value(fields, RutorField.Genres)),
            CleanValue(Value(fields, RutorField.Publisher)),
            null,
            null,
            null,
            null,
            null,
            null,
            CurrentParserVersion);

        return new RutorDetailValue(magnet, infoHash, metadata);
    }

    private static IElement? FindDetailCell(IDocument document)
    {
        var firstRow = document.QuerySelector("#details tr");
        return firstRow?.Children
            .Where(element => element.LocalName == "td")
            .LastOrDefault();
    }

    private static IReadOnlyDictionary<RutorField, string> ExtractFields(IElement detailCell)
    {
        var html = ScriptOrStyle().Replace(detailCell.InnerHtml, " ");
        html = LineBreak().Replace(html, "\n");
        html = BlockEnd().Replace(html, "\n");
        html = Tags().Replace(html, " ");
        var text = WebUtility.HtmlDecode(html);

        var fields = new Dictionary<RutorField, string>();
        foreach (var rawLine in text.Split('\n'))
        {
            var line = CleanLine(rawLine);
            if (line.Length == 0)
                continue;

            var separator = line.IndexOf(':');
            if (separator <= 0 || separator >= line.Length - 1)
                continue;

            var label = NormalizeLabel(line[..separator]);
            if (!TryMapField(label, out var field))
                continue;

            var value = CleanLine(line[(separator + 1)..]);
            if (value.Length > 0 && !fields.ContainsKey(field))
                fields[field] = RutorHtmlParser.NormalizeAudioTokens(value);
        }

        return fields;
    }

    private string[] ParseNarrators(string? value, IReadOnlyList<string> fallback)
    {
        if (string.IsNullOrWhiteSpace(value))
            return fallback.ToArray();

        var parsed = personNames.ParseNarrators(new[] { value })
            .Select(item => item.DisplayName)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
        if (parsed.Length > 0)
            return parsed;

        return value.Split(
                [',', ';', '/'],
                StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    private static string[] ParseGenres(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return [];

        return value.Split(
                [',', ';', '|'],
                StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(CleanLine)
            .Where(item => item.Length > 0)
            .Where(item => !item.Equals("аудиокнига", StringComparison.OrdinalIgnoreCase))
            .Where(item => !item.Equals("аудиокниги", StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    private static long? ParseDuration(string? value)
    {
        var match = Duration().Match(value ?? string.Empty);
        if (!match.Success)
            return null;

        if (!long.TryParse(match.Groups["hours"].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var hours)
            || !int.TryParse(match.Groups["minutes"].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var minutes)
            || !int.TryParse(match.Groups["seconds"].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var seconds))
            return null;

        try
        {
            return checked(hours * 3600L + minutes * 60L + seconds);
        }
        catch (OverflowException)
        {
            return null;
        }
    }

    private static int? ParseYear(string? value)
    {
        var years = Year().Matches(value ?? string.Empty)
            .Cast<Match>()
            .Select(match => int.TryParse(
                match.Value,
                NumberStyles.None,
                CultureInfo.InvariantCulture,
                out var parsed)
                ? parsed
                : 0)
            .Where(year => year is >= 1900 and <= 2100)
            .ToArray();
        return years.Length == 0 ? null : years.Max();
    }

    private static int? ParseBitrate(string? value)
    {
        var match = Bitrate().Match(value ?? string.Empty);
        return match.Success && int.TryParse(
            match.Groups[1].Value,
            NumberStyles.None,
            CultureInfo.InvariantCulture,
            out var parsed)
            ? parsed
            : null;
    }

    private static string? ParseAudioFormat(string? value)
    {
        var normalized = RutorHtmlParser.NormalizeAudioTokens(value ?? string.Empty);
        var match = AudioFormat().Match(normalized);
        return match.Success ? NormalizeAudioFormat(match.Groups[1].Value) : null;
    }

    private static string? NormalizeAudioFormat(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var normalized = RutorHtmlParser.NormalizeAudioTokens(value).ToUpperInvariant();
        return normalized switch
        {
            "MP3" or "M4B" or "M4A" or "AAC" or "FLAC" or "OGG" or
                "OPUS" or "APE" or "WMA" => normalized,
            _ => null
        };
    }

    private static string ParseInfoHash(string magnet)
    {
        var match = InfoHash().Match(magnet);
        return match.Success ? match.Groups[1].Value.ToLowerInvariant() : string.Empty;
    }

    private static string? Value(
        IReadOnlyDictionary<RutorField, string> fields,
        RutorField field) =>
        fields.TryGetValue(field, out var value) ? value : null;

    private static string? CleanValue(string? value)
    {
        var cleaned = CleanLine(value ?? string.Empty);
        return cleaned.Length == 0 ? null : cleaned;
    }

    private static string? CleanAudioFormatSuffix(string? value)
    {
        var cleaned = CleanValue(value);
        if (cleaned is null)
            return null;
        cleaned = AudioFormatSuffix().Replace(RutorHtmlParser.NormalizeAudioTokens(cleaned), string.Empty)
            .Trim(' ', '-', '–', '—', ':', '.', ',');
        return cleaned.Length == 0 ? null : cleaned;
    }

    private static string CleanLine(string value) =>
        string.Join(
            ' ',
            value.Split(
                (char[]?)null,
                StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));

    private static string NormalizeLabel(string value)
    {
        var normalized = LabelPunctuation().Replace(value.ToLowerInvariant(), " ");
        return CleanLine(normalized);
    }

    private static bool TryMapField(string label, out RutorField field)
    {
        field = label switch
        {
            "название" => RutorField.Title,
            "автор" or "авторы" => RutorField.Author,
            "жанр" or "жанры" => RutorField.Genres,
            "озвучивает" or "исполнитель" or "исполнители" or
                "читает" or "чтец" or "чтецы" => RutorField.Narrators,
            "год" or "год выпуска" or "годы выпуска" or "год издания" or
                "год издания книги" => RutorField.ReleaseYear,
            "издатель" or "издательство" => RutorField.Publisher,
            "продолжительность" or "длительность" or "время звучания" =>
                RutorField.Duration,
            "формат" or "формат кодек" or "кодек" or "аудиокодек" or
                "аудио кодек" => RutorField.AudioFormat,
            "битрейт" or "битрейт аудио" => RutorField.Bitrate,
            _ => RutorField.None
        };
        return field != RutorField.None;
    }

    private enum RutorField
    {
        None,
        Title,
        Author,
        Genres,
        Narrators,
        ReleaseYear,
        Publisher,
        Duration,
        AudioFormat,
        Bitrate
    }

    [GeneratedRegex(@"<\s*(?:script|style)\b[^>]*>.*?<\s*/\s*(?:script|style)\s*>", RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.CultureInvariant)]
    private static partial Regex ScriptOrStyle();

    [GeneratedRegex(@"<\s*br\s*/?\s*>", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex LineBreak();

    [GeneratedRegex(@"<\s*/\s*(?:p|div|li|tr|td|h[1-6])\s*>", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex BlockEnd();

    [GeneratedRegex(@"<[^>]+>", RegexOptions.CultureInvariant)]
    private static partial Regex Tags();

    [GeneratedRegex(@"[^\p{L}\p{N}]+", RegexOptions.CultureInvariant)]
    private static partial Regex LabelPunctuation();

    [GeneratedRegex(@"\b(19\d{2}|20\d{2})\b", RegexOptions.CultureInvariant)]
    private static partial Regex Year();

    [GeneratedRegex(@"\b(?<hours>\d{1,5}):(?<minutes>[0-5]\d):(?<seconds>[0-5]\d)\b", RegexOptions.CultureInvariant)]
    private static partial Regex Duration();

    [GeneratedRegex(@"\b(\d{2,4})\s*(?:kbps|kbit|kb/s|кбит/?с)\b", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex Bitrate();

    [GeneratedRegex(@"(?<![\p{L}\p{N}])(MP3|M4B|M4A|AAC|FLAC|OGG|OPUS|APE|WMA)(?![\p{L}\p{N}])", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex AudioFormat();

    [GeneratedRegex(@"\s+(?:MP3|M4B|M4A|AAC|FLAC|OGG|OPUS|APE|WMA)\s*$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex AudioFormatSuffix();

    [GeneratedRegex(@"(?:[?&]xt=urn:btih:)([a-f0-9]{40})(?:&|$)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex InfoHash();
}
