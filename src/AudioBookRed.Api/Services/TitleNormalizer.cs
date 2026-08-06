using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed partial class TitleNormalizer(SeriesNameParser seriesNames)
{
    public ParsedAudiobookTitle Parse(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            throw new ArgumentException("Raw title is required", nameof(raw));

        var cleaned = MultiSpace().Replace(raw.Trim(), " ");
        var format = AudioFormat().Match(cleaned).Groups[1].Value.ToUpperInvariant();
        var bitrateMatch = Bitrate().Match(cleaned);
        int? bitrate = bitrateMatch.Success
            ? int.Parse(bitrateMatch.Groups[1].Value, CultureInfo.InvariantCulture)
            : null;
        int? year = null;
        var yearMatch = BracketedYear().Match(cleaned);
        if (yearMatch.Success)
            year = int.Parse(yearMatch.Groups[1].Value, CultureInfo.InvariantCulture);

        var narrators = Narrator().Matches(cleaned)
            .SelectMany(match => match.Groups[1].Value.Split(
                [',', ';', '/'],
                StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        var withoutTags = BracketTag().Replace(cleaned, " ");
        withoutTags = AudioFormat().Replace(withoutTags, " ");
        withoutTags = MultiSpace().Replace(withoutTags, " ").Trim(' ', '-', '–', '—');

        var parts = AuthorTitleSeparator().Split(withoutTags, 2);
        var author = parts.Length == 2 ? parts[0].Trim() : "Неизвестный автор";
        var title = parts.Length == 2 ? parts[1].Trim() : withoutTags;

        SeriesNamePart? seriesPart = null;
        var explicitSeries = ExplicitSeries().Match(cleaned);
        if (explicitSeries.Success)
        {
            var position = ParseDecimal(explicitSeries.Groups["position"].Value);
            seriesPart = seriesNames.Parse(explicitSeries.Groups["series"].Value, position);
            title = Regex.Replace(title, Regex.Escape(explicitSeries.Value), "", RegexOptions.IgnoreCase)
                .Trim(' ', '-', '–', '—', ':', '.', ',');
        }

        // RuTracker часто кодирует цикл прямо перед названием:
        // «Лукьяненко Сергей - Соглашение 01: Порог [...]».
        // Здесь «Соглашение» — цикл, 1 — номер книги, «Порог» — название.
        if (seriesPart is null && seriesNames.TryExtractTitlePrefix(title, out var prefixSeries, out var bookTitle))
        {
            seriesPart = prefixSeries;
            title = bookTitle;
        }

        return new ParsedAudiobookTitle(
            string.IsNullOrWhiteSpace(title) ? cleaned : title,
            author,
            seriesPart?.DisplayName,
            seriesPart?.Position,
            narrators,
            DetectLanguage(cleaned),
            year,
            string.IsNullOrWhiteSpace(format) ? null : format,
            bitrate,
            ContainsAny(cleaned, "сокращ", "abridged"),
            ContainsAny(cleaned, "радиоспектак", "аудиоспектак", "dramatized"));
    }

    public string Normalize(string value)
    {
        var lowered = value
            .Replace('Ё', 'Е')
            .Replace('ё', 'е')
            .ToLowerInvariant();
        var builder = new StringBuilder(lowered.Length);
        foreach (var ch in lowered)
        {
            if (char.IsLetterOrDigit(ch)) builder.Append(ch);
            else builder.Append(' ');
        }
        return MultiSpace().Replace(builder.ToString(), " ").Trim();
    }

    private static decimal? ParseDecimal(string value) =>
        decimal.TryParse(
            value.Replace(',', '.'),
            NumberStyles.AllowDecimalPoint,
            CultureInfo.InvariantCulture,
            out var parsed)
            ? parsed
            : null;

    private static string? DetectLanguage(string value) =>
        Regex.IsMatch(value, "[А-Яа-яЁё]") ? "ru" : Regex.IsMatch(value, "[A-Za-z]") ? "en" : null;

    private static bool ContainsAny(string source, params string[] values) =>
        values.Any(value => source.Contains(value, StringComparison.OrdinalIgnoreCase));

    [GeneratedRegex(@"\s+")]
    private static partial Regex MultiSpace();

    [GeneratedRegex(@"(?i)\b(mp3|m4b|m4a|aac|flac|ogg|opus)\b")]
    private static partial Regex AudioFormat();

    [GeneratedRegex(@"(?i)\b(\d{2,4})\s*(?:kbps|кбит/?с|kb/s)\b")]
    private static partial Regex Bitrate();

    [GeneratedRegex(@"(?:\(|\[)[^()\[\]]*\b(19\d{2}|20\d{2})\b[^()\[\]]*(?:\)|\])")]
    private static partial Regex BracketedYear();

    [GeneratedRegex(@"(?i)(?:читает|чтец|исполнител(?:ь|и)|narrator)\s*[:\-]?\s*([^\[\]()]+)")]
    private static partial Regex Narrator();

    [GeneratedRegex(@"(?i)(?:цикл|серия)\s*[:\-]?\s*(?<series>[^\[\]()]+?)\s*(?:[#№]|кн\.?|книга|том)?\s*(?<position>\d+(?:[.,]\d+)?)")]
    private static partial Regex ExplicitSeries();

    [GeneratedRegex(@"\[[^\]]*\]|\([^\)]*\)")]
    private static partial Regex BracketTag();

    [GeneratedRegex(@"\s+(?:—|–|-)\s+")]
    private static partial Regex AuthorTitleSeparator();
}
