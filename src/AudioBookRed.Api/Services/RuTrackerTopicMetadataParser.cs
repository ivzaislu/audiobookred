using System.Globalization;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using AngleSharp.Dom;
using AngleSharp.Html.Parser;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RuTrackerTopicMetadataParser(TitleNormalizer titleNormalizer)
{
    public const int CurrentParserVersion = 3;

    private static readonly Regex YearPattern = new(
        @"\b(19\d{2}|20\d{2})\b",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex DecimalPattern = new(
        @"\d+(?:[.,]\d+)?",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex FormatPattern = new(
        @"\b(mp3|m4b|m4a|aac|flac|ogg|opus|ape|alac|wav|wavpack|wv)\b",
        RegexOptions.Compiled | RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private static readonly Regex BitratePattern = new(
        @"\b(\d{2,4})\s*(?:kbps|кбит/?с|kb/s)\b",
        RegexOptions.Compiled | RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private static readonly Regex SampleRatePattern = new(
        @"\b(?<value>\d+(?:[.,]\d+)?)\s*(?<unit>khz|hz|кгц|гц)\b",
        RegexOptions.Compiled | RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private static readonly Regex ChannelPattern = new(
        @"\b(joint\s+stereo|stereo|mono|стерео|моно)\b",
        RegexOptions.Compiled | RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private static readonly Regex DurationPattern = new(
        @"\b(?<hours>\d{1,4}):(?<minutes>[0-5]?\d):(?<seconds>[0-5]\d)\b",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex TitleContinuationPattern = new(
        @"^\(?\s*(?:том|часть|книга|выпуск)\s*(?:№\s*)?(?:\d+|[ivxlcdm]+)\s*\)?$",
        RegexOptions.Compiled |
        RegexOptions.IgnoreCase |
        RegexOptions.CultureInvariant);

    private static readonly IReadOnlyDictionary<string, TopicField> KnownFields =
        new Dictionary<string, TopicField>(StringComparer.Ordinal)
        {
            ["год выпуска"] = TopicField.ReleaseYear,
            ["автор"] = TopicField.Authors,
            ["авторы"] = TopicField.Authors,
            ["фамилия автора"] = TopicField.AuthorSurname,
            ["имя автора"] = TopicField.AuthorGivenName,
            ["исполнитель"] = TopicField.Narrators,
            ["исполнители"] = TopicField.Narrators,
            ["чтец"] = TopicField.Narrators,
            ["чтецы"] = TopicField.Narrators,
            ["цикл"] = TopicField.Series,
            ["серия"] = TopicField.Series,
            ["цикл серия"] = TopicField.Series,
            ["номер книги"] = TopicField.SeriesPosition,
            ["номер в серии"] = TopicField.SeriesPosition,
            ["жанр"] = TopicField.Genres,
            ["жанры"] = TopicField.Genres,
            ["издательство"] = TopicField.Publisher,
            ["аудиокодек"] = TopicField.AudioFormat,
            ["формат"] = TopicField.AudioFormat,
            ["битрейт"] = TopicField.Bitrate,
            ["вид битрейта"] = TopicField.BitrateMode,
            ["частота дискретизации"] = TopicField.SampleRate,
            ["количество каналов моно стерео"] = TopicField.AudioChannels,
            ["каналы"] = TopicField.AudioChannels,
            ["качество"] = TopicField.Quality,
            ["время звучания"] = TopicField.Duration,
            ["длительность"] = TopicField.Duration,
            ["тип издания"] = TopicField.EditionType,
            ["категория"] = TopicField.EditionCategory,
            ["музыкальное сопровождение"] = TopicField.Music,
            ["описание"] = TopicField.Description
        };

    public RuTrackerTopicMetadata Parse(string html, string fallbackTitle)
    {
        ArgumentNullException.ThrowIfNull(html);

        var document = new HtmlParser().ParseDocument(html);
        return Parse(document, fallbackTitle);
    }

    public RuTrackerTopicMetadata Parse(IDocument document, string fallbackTitle)
    {
        ArgumentNullException.ThrowIfNull(document);

        var safeFallbackTitle = string.IsNullOrWhiteSpace(fallbackTitle)
            ? "Неизвестное произведение"
            : fallbackTitle;
        var fallback = titleNormalizer.Parse(safeFallbackTitle);

        var authorBody = document.QuerySelectorAll("tbody")
            .Select(container => new
            {
                Container = container,
                Body = container.QuerySelector(".post_body")
            })
            .FirstOrDefault(candidate =>
                candidate.Container.QuerySelector(".nick-author") is not null &&
                candidate.Body is not null)
            ?.Body;

        if (authorBody is null)
            return Empty(fallback);

        var lines = ExtractLines(authorBody);
        var fields = new Dictionary<TopicField, string>();
        var firstFieldIndex = lines.Count;

        for (var index = 0; index < lines.Count; index++)
        {
            if (!TryParseField(lines[index], out var field, out var value))
                continue;

            firstFieldIndex = Math.Min(firstFieldIndex, index);
            if (!fields.ContainsKey(field) && !string.IsNullOrWhiteSpace(value))
                fields[field] = value;
        }

        if (fields.Count == 0)
            return Empty(fallback);

        var series = Value(fields, TopicField.Series);
        var seriesPosition = ParseDecimal(Value(fields, TopicField.SeriesPosition));
        var title = SelectTitle(
            lines.Take(firstFieldIndex).ToArray(),
            series,
            seriesPosition,
            fallback.Title);

        var author = BuildAuthor(fields, fallback.Author);
        var narrators = SplitValues(Value(fields, TopicField.Narrators));
        if (narrators.Length == 0)
            narrators = fallback.Narrators;

        var quality = Value(fields, TopicField.Quality);
        var audioFormat =
            ParseFormat(Value(fields, TopicField.AudioFormat)) ??
            ParseFormat(quality) ??
            fallback.AudioFormat;
        var bitrate =
            ParseInteger(BitratePattern, Value(fields, TopicField.Bitrate)) ??
            ParseInteger(BitratePattern, quality) ??
            fallback.BitrateKbps;
        var sampleRate =
            ParseSampleRate(Value(fields, TopicField.SampleRate)) ??
            ParseSampleRate(quality);
        var channels =
            CleanOptional(Value(fields, TopicField.AudioChannels)) ??
            ParseChannels(quality);
        var bitrateMode = ParseBitrateMode(Value(fields, TopicField.BitrateMode));

        var corpus = string.Join(' ', lines);
        var parsed = new ParsedAudiobookTitle(
            title,
            author,
            CleanOptional(series) ?? fallback.Series,
            seriesPosition ?? fallback.SeriesPosition,
            narrators,
            fallback.Language,
            ParseYear(Value(fields, TopicField.ReleaseYear)) ?? fallback.ReleaseYear,
            audioFormat,
            bitrate,
            fallback.IsAbridged == true ||
                ContainsAny(corpus, "сокращ", "abridged"),
            fallback.IsDramatized == true ||
                ContainsAny(corpus, "радиоспектак", "аудиоспектак", "dramatized"));

        return new RuTrackerTopicMetadata(
            parsed,
            ParseDuration(Value(fields, TopicField.Duration)),
            SplitValues(Value(fields, TopicField.Genres)),
            CleanOptional(Value(fields, TopicField.Publisher)),
            sampleRate,
            channels,
            bitrateMode,
            CleanOptional(Value(fields, TopicField.EditionType)),
            CleanOptional(Value(fields, TopicField.EditionCategory)),
            CleanOptional(Value(fields, TopicField.Music)),
            CurrentParserVersion);
    }

    private static RuTrackerTopicMetadata Empty(ParsedAudiobookTitle fallback) =>
        new(
            fallback,
            null,
            [],
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0);

    private static IReadOnlyList<string> ExtractLines(IElement body)
    {
        var text = new StringBuilder();
        AppendChildren(body, text);

        return text.ToString()
            .Split(
                '\n',
                StringSplitOptions.RemoveEmptyEntries |
                StringSplitOptions.TrimEntries)
            .Select(CleanText)
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .ToArray();
    }

    private static void AppendChildren(INode node, StringBuilder output)
    {
        foreach (var child in node.ChildNodes)
        {
            if (child.NodeType == NodeType.Text)
            {
                output.Append(child.TextContent);
                continue;
            }

            if (child is not IElement element)
                continue;

            var tag = element.TagName;
            if (tag is "SCRIPT" or "STYLE" or "NOSCRIPT" or "VAR" or "IMG")
                continue;
            if (element.ClassList.Contains("q-wrap"))
                continue;

            if (tag is "BR" or "HR")
            {
                AppendLineBreak(output);
                continue;
            }

            // RuTracker may render the centered author and title as sibling
            // span.post-align elements without a BR between them. Treat these
            // visual blocks as separate lines before title selection.
            var block =
                tag is "DIV" or "P" or "LI" or "TR" or "TD" or "TABLE" ||
                element.ClassList.Contains("post-align");
            if (block)
                AppendLineBreak(output);

            AppendChildren(element, output);

            if (block)
                AppendLineBreak(output);
        }
    }

    private static void AppendLineBreak(StringBuilder output)
    {
        if (output.Length == 0 || output[^1] == '\n')
            return;
        output.Append('\n');
    }

    private static bool TryParseField(
        string line,
        out TopicField field,
        out string value)
    {
        var separator = line.IndexOf(':');
        if (separator <= 0)
        {
            field = default;
            value = string.Empty;
            return false;
        }

        var label = NormalizeLabel(line[..separator]);
        if (!KnownFields.TryGetValue(label, out field))
        {
            value = string.Empty;
            return false;
        }

        value = CleanText(line[(separator + 1)..]);
        return true;
    }

    private static string SelectTitle(
        IReadOnlyList<string> candidates,
        string? series,
        decimal? position,
        string fallback)
    {
        var meaningful = candidates
            .Select(CleanText)
            .Where(value => value.Count(char.IsLetterOrDigit) >= 2)
            .ToArray();

        if (meaningful.Length == 0)
            return fallback;
        if (meaningful.Length == 1)
            return meaningful[0];

        var selectedIndex = Array.FindLastIndex(
            meaningful,
            value => !LooksLikeSeriesHeading(value, series, position));
        if (selectedIndex < 0)
            return meaningful[^1];

        var selected = meaningful[selectedIndex];
        if (selectedIndex > 0 && TitleContinuationPattern.IsMatch(selected))
        {
            var prefix = meaningful[selectedIndex - 1];
            if (!LooksLikeSeriesHeading(prefix, series, position))
            {
                var suffix = selected.Trim(' ', '(', ')');
                if (fallback.Contains(prefix, StringComparison.OrdinalIgnoreCase) &&
                    fallback.Contains(suffix, StringComparison.OrdinalIgnoreCase))
                    return fallback;

                return $"{prefix} ({suffix})";
            }
        }

        return selected;
    }

    private static bool LooksLikeSeriesHeading(
        string candidate,
        string? series,
        decimal? position)
    {
        if (string.IsNullOrWhiteSpace(series))
            return false;

        var normalizedCandidate = NormalizeLabel(candidate);
        var normalizedSeries = NormalizeLabel(series);
        if (!normalizedCandidate.StartsWith(
                normalizedSeries,
                StringComparison.Ordinal))
            return false;

        if (position is null)
            return normalizedCandidate.Equals(
                normalizedSeries,
                StringComparison.Ordinal);

        var positionText = decimal.Truncate(position.Value)
            .ToString(CultureInfo.InvariantCulture);
        return normalizedCandidate
            .Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .Any(part =>
                part.TrimStart('0').Equals(
                    positionText.TrimStart('0'),
                    StringComparison.Ordinal));
    }

    private static string BuildAuthor(
        IReadOnlyDictionary<TopicField, string> fields,
        string fallback)
    {
        var direct = CleanOptional(Value(fields, TopicField.Authors));
        if (direct is not null)
            return direct;

        var surname = CleanOptional(Value(fields, TopicField.AuthorSurname));
        var givenName = CleanOptional(Value(fields, TopicField.AuthorGivenName));
        var combined = CleanText($"{surname} {givenName}");
        return string.IsNullOrWhiteSpace(combined) ? fallback : combined;
    }

    private static string[] SplitValues(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return [];

        return value.Split(
                [',', ';', '/'],
                StringSplitOptions.RemoveEmptyEntries |
                StringSplitOptions.TrimEntries)
            .Select(CleanText)
            .Where(item => !string.IsNullOrWhiteSpace(item))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    private static int? ParseYear(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var match = YearPattern.Match(value);
        return match.Success &&
               int.TryParse(
                   match.Groups[1].Value,
                   NumberStyles.None,
                   CultureInfo.InvariantCulture,
                   out var parsed)
            ? parsed
            : null;
    }

    private static decimal? ParseDecimal(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var match = DecimalPattern.Match(value);
        return match.Success &&
               decimal.TryParse(
                   match.Value.Replace(',', '.'),
                   NumberStyles.AllowDecimalPoint,
                   CultureInfo.InvariantCulture,
                   out var parsed)
            ? parsed
            : null;
    }

    private static int? ParseInteger(Regex pattern, string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var match = pattern.Match(value);
        return match.Success &&
               int.TryParse(
                   match.Groups[1].Value,
                   NumberStyles.None,
                   CultureInfo.InvariantCulture,
                   out var parsed)
            ? parsed
            : null;
    }

    private static string? ParseFormat(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var match = FormatPattern.Match(value);
        return match.Success ? match.Groups[1].Value.ToUpperInvariant() : null;
    }

    private static int? ParseSampleRate(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var match = SampleRatePattern.Match(value);
        if (!match.Success ||
            !decimal.TryParse(
                match.Groups["value"].Value.Replace(',', '.'),
                NumberStyles.AllowDecimalPoint,
                CultureInfo.InvariantCulture,
                out var parsed))
            return null;

        var unit = match.Groups["unit"].Value.ToLowerInvariant();
        var hertz = unit is "khz" or "кгц" ? parsed * 1000m : parsed;
        if (hertz <= 0 || hertz > int.MaxValue)
            return null;

        return decimal.ToInt32(
            decimal.Round(hertz, 0, MidpointRounding.AwayFromZero));
    }

    private static string? ParseChannels(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var match = ChannelPattern.Match(value);
        if (!match.Success)
            return null;

        var channels = CleanText(match.Value);
        if (channels.Equals("joint stereo", StringComparison.OrdinalIgnoreCase))
            return "Joint Stereo";
        if (channels.Equals("stereo", StringComparison.OrdinalIgnoreCase))
            return "Stereo";
        if (channels.Equals("mono", StringComparison.OrdinalIgnoreCase))
            return "Mono";
        return channels;
    }

    private static string? ParseBitrateMode(string? value)
    {
        var cleaned = CleanOptional(value);
        if (cleaned is null)
            return null;

        if (ContainsAny(cleaned, "cbr", "постоян"))
            return "CBR";
        if (ContainsAny(cleaned, "vbr", "перемен"))
            return "VBR";
        if (ContainsAny(cleaned, "abr", "средн"))
            return "ABR";
        return cleaned;
    }

    private static long? ParseDuration(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var match = DurationPattern.Match(value);
        if (!match.Success ||
            !long.TryParse(
                match.Groups["hours"].Value,
                NumberStyles.None,
                CultureInfo.InvariantCulture,
                out var hours) ||
            !long.TryParse(
                match.Groups["minutes"].Value,
                NumberStyles.None,
                CultureInfo.InvariantCulture,
                out var minutes) ||
            !long.TryParse(
                match.Groups["seconds"].Value,
                NumberStyles.None,
                CultureInfo.InvariantCulture,
                out var seconds))
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

    private static string? CleanOptional(string? value)
    {
        var cleaned = CleanText(value ?? string.Empty);
        return string.IsNullOrWhiteSpace(cleaned) ? null : cleaned;
    }

    private static string CleanText(string value) =>
        string.Join(
            ' ',
            WebUtility.HtmlDecode(value)
                .Split(
                    (char[]?)null,
                    StringSplitOptions.RemoveEmptyEntries |
                    StringSplitOptions.TrimEntries));

    private static string NormalizeLabel(string value)
    {
        var decoded = WebUtility.HtmlDecode(value)
            .Replace('Ё', 'Е')
            .Replace('ё', 'е')
            .ToLowerInvariant();
        var result = new StringBuilder(decoded.Length);

        foreach (var character in decoded)
        {
            if (char.IsLetterOrDigit(character))
            {
                result.Append(character);
            }
            else if (result.Length > 0 && result[^1] != ' ')
            {
                result.Append(' ');
            }
        }

        return result.ToString().Trim();
    }

    private static string? Value(
        IReadOnlyDictionary<TopicField, string> fields,
        TopicField field) =>
        fields.TryGetValue(field, out var value) ? value : null;

    private static bool ContainsAny(string source, params string[] values) =>
        values.Any(value =>
            source.Contains(value, StringComparison.OrdinalIgnoreCase));

    private enum TopicField
    {
        ReleaseYear,
        Authors,
        AuthorSurname,
        AuthorGivenName,
        Narrators,
        Series,
        SeriesPosition,
        Genres,
        Publisher,
        AudioFormat,
        Bitrate,
        BitrateMode,
        SampleRate,
        AudioChannels,
        Quality,
        Duration,
        EditionType,
        EditionCategory,
        Music,
        Description
    }
}
