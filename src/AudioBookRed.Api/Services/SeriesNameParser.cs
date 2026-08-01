using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace AudioBookRed.Api.Services;

/// <summary>
/// Нормализует название цикла отдельно от номера книги.
/// Числа, являющиеся частью настоящего названия (например, «Метро 2033»),
/// не удаляются без явного маркера или двоеточия.
/// </summary>
public sealed partial class SeriesNameParser
{
    public SeriesNamePart? Parse(string? value, decimal? explicitPosition = null)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;

        var alias = Clean(value);
        if (alias.Length == 0) return null;

        var cleaned = SeriesPrefix().Replace(alias, string.Empty);
        cleaned = SeriesSuffix().Replace(cleaned, string.Empty);
        cleaned = TrimWrappingQuotes(cleaned);

        decimal? position = explicitPosition;
        var marked = MarkedPositionSuffix().Match(cleaned);
        if (marked.Success)
        {
            position ??= ParsePosition(marked.Groups["position"].Value);
            cleaned = cleaned[..marked.Index];
        }
        else
        {
            var zeroPadded = ZeroPaddedPositionSuffix().Match(cleaned);
            if (zeroPadded.Success)
            {
                position ??= ParsePosition(zeroPadded.Groups["position"].Value);
                cleaned = cleaned[..zeroPadded.Index];
            }
            else
            {
                var colon = ColonPositionSuffix().Match(cleaned);
                if (colon.Success)
                {
                    position ??= ParsePosition(colon.Groups["position"].Value);
                    cleaned = cleaned[..colon.Index];
                }
            }
        }

        var display = TrimWrappingQuotes(Clean(cleaned).Trim(' ', '-', '–', '—', ':', ';', '.', ',', '#', '№'));
        if (display.Length < 2) return null;

        var normalized = Normalize(display);
        if (normalized.Length < 2) return null;

        return new SeriesNamePart(
            display,
            normalized,
            position,
            alias,
            Normalize(alias));
    }

    public bool TryExtractTitlePrefix(
        string value,
        out SeriesNamePart? series,
        out string title)
    {
        series = null;
        title = value;
        if (string.IsNullOrWhiteSpace(value)) return false;

        var cleaned = Clean(value);
        var match = MarkedTitlePrefix().Match(cleaned);
        if (!match.Success) match = ZeroPaddedTitlePrefix().Match(cleaned);
        if (!match.Success) match = MultiwordTitlePrefix().Match(cleaned);
        if (!match.Success) return false;

        var position = ParsePosition(match.Groups["position"].Value);
        var parsed = Parse(match.Groups["series"].Value, position);
        var parsedTitle = Clean(match.Groups["title"].Value)
            .Trim(' ', '-', '–', '—', ':', ';', '.', ',');
        if (parsed is null || parsedTitle.Length == 0) return false;

        series = parsed;
        title = parsedTitle;
        return true;
    }

    public string Normalize(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return string.Empty;

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

    private static decimal? ParsePosition(string value) =>
        decimal.TryParse(
            value.Replace(',', '.'),
            NumberStyles.AllowDecimalPoint,
            CultureInfo.InvariantCulture,
            out var parsed)
            ? parsed
            : null;

    private static string Clean(string value) =>
        MultiSpace().Replace(value.Replace('\u00A0', ' ').Trim(), " ");

    private static string TrimWrappingQuotes(string value)
    {
        var result = value.Trim();
        while (result.Length >= 2 &&
               ((result[0] == '«' && result[^1] == '»') ||
                (result[0] == '"' && result[^1] == '"') ||
                (result[0] == '\'' && result[^1] == '\'')))
        {
            result = result[1..^1].Trim();
        }
        return result;
    }

    [GeneratedRegex(@"\s+")]
    private static partial Regex MultiSpace();

    [GeneratedRegex(@"(?i)^\s*(?:цикл|серия)\s*[:\-–—]?\s*")]
    private static partial Regex SeriesPrefix();

    [GeneratedRegex(@"(?i)\s*[,;:\-–—]?\s*(?:цикл|серия)\s*$")]
    private static partial Regex SeriesSuffix();

    [GeneratedRegex(@"(?i)\s*(?:#|№|кн\.?|книга|том)\s*(?<position>\d{1,3}(?:[.,]\d+)?)\s*[:.]?\s*$")]
    private static partial Regex MarkedPositionSuffix();

    // «Соглашение 01:» / «Небесное воинство 02» — ноль в начале является
    // сильным признаком порядкового номера, а не частью названия цикла.
    [GeneratedRegex(@"\s+(?<position>0\d)\s*[:.]?\s*$")]
    private static partial Regex ZeroPaddedPositionSuffix();

    // Одно- или двузначный номер отделяем без маркера только при наличии
    // двоеточия: «Небесное воинство 2:».
    [GeneratedRegex(@"\s+(?<position>[1-9]\d?)\s*:\s*$")]
    private static partial Regex ColonPositionSuffix();

    [GeneratedRegex(@"(?ix)^\s*(?<series>.+?)\s*(?:\#|№|кн\.?|книга|том)\s*(?<position>\d{1,3}(?:[.,]\d+)?)\s*[:\-–—]\s*(?<title>.+?)\s*$")]
    private static partial Regex MarkedTitlePrefix();

    [GeneratedRegex(@"(?ix)^\s*(?<series>.+?)\s+(?<position>0\d)\s*:\s*(?<title>.+?)\s*$")]
    private static partial Regex ZeroPaddedTitlePrefix();

    // Без ведущего нуля принимаем шаблон только для многословного названия
    // цикла: «Небесное воинство 2: Девятый». Это не затронет «Зона 31».
    [GeneratedRegex(@"(?ix)^\s*(?<series>\S+(?:\s+\S+)+?)\s+(?<position>[1-9]\d?)\s*:\s*(?<title>.+?)\s*$")]
    private static partial Regex MultiwordTitlePrefix();
}

public sealed record SeriesNamePart(
    string DisplayName,
    string NormalizedName,
    decimal? Position,
    string AliasName,
    string AliasNormalizedName);
