using System.Text.RegularExpressions;

namespace AudioBookRed.Api.Services;

/// <summary>
/// Выделяет отдельных авторов и чтецов и строит устойчивый канонический ключ.
/// Порядок «Фамилия Имя» и «Имя Фамилия» даёт один и тот же ключ, а исходное
/// написание сохраняется как псевдоним.
/// </summary>
public sealed partial class PersonNameParser(TitleNormalizer normalizer)
{
    private static readonly HashSet<string> UnknownValues = new(StringComparer.Ordinal)
    {
        "неизвестный автор", "неизвестен", "неизвестно", "unknown"
    };

    private static readonly HashSet<string> GivenNames = new(StringComparer.Ordinal)
    {
        "август", "аврора", "адам", "адриан", "александр", "александра", "алексей", "алена",
        "алина", "алиса", "алла", "альберт", "анатолий", "андрей", "анжела", "анна", "антон",
        "антонина", "аркадий", "арсений", "артем", "артемий", "артур", "борис", "вадим", "валентин",
        "валентина", "валерий", "валерия", "василий", "вера", "вероника", "виктор", "виктория",
        "виталий", "владимир", "владислав", "вячеслав", "геннадий", "георгий", "герман", "глеб",
        "григорий", "даниил", "дарья", "денис", "диана", "дмитрий", "евгений", "евгения", "егор",
        "екатерина", "елена", "елизавета", "иван", "игорь", "илья", "инна", "ирина", "кирилл",
        "константин", "ксения", "лариса", "лев", "леонид", "лидия", "любовь", "людмила", "максим",
        "маргарита", "марина", "мария", "марк", "матвей", "михаил", "надежда", "наталья", "никита",
        "николай", "нина", "олег", "ольга", "павел", "петр", "полина", "раиса", "роман", "светлана",
        "семен", "сергей", "софия", "станислав", "степан", "тамара", "татьяна", "тимофей", "федор",
        "юлия", "юрий", "яна", "ярослав",
        "альфред", "артур", "брэндон", "гарри", "генри", "джеймс", "джейн", "джек", "джоан",
        "джозеф", "джон", "джордж", "дэвид", "клайв", "кристофер", "майкл", "марк", "мартин",
        "мэри", "нил", "патрик", "питер", "ричард", "роберт", "рэй", "саймон", "стив", "стивен",
        "терри", "томас", "уильям", "филип", "фрэнк", "чарльз", "энн"
    };

    private static readonly Dictionary<string, string> TokenCorrections = new(StringComparer.Ordinal)
    {
        ["сергеий"] = "сергей",
        ["сергеи"] = "сергей",
        ["александрр"] = "александр",
        ["евгении"] = "евгений"
    };

    public IReadOnlyList<PersonNamePart> ParseAuthors(string? value) => Parse(value);

    public IReadOnlyList<PersonNamePart> ParseNarrators(IEnumerable<string>? values)
    {
        if (values is null) return [];

        var result = new List<PersonNamePart>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var value in values)
        {
            foreach (var part in Parse(value))
            {
                if (seen.Add(part.NormalizedName)) result.Add(part);
            }
        }
        return result;
    }

    // Оставлено для совместимости со старым кодом. Канонизация серий теперь
    // выполняется отдельным SeriesNameParser.
    public string NormalizeSeries(string? value) =>
        string.IsNullOrWhiteSpace(value)
            ? string.Empty
            : normalizer.Normalize(value);

    private IReadOnlyList<PersonNamePart> Parse(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return [];

        var cleaned = Prefix().Replace(value.Trim(), string.Empty);
        cleaned = SplitGluedNameBoundaries(cleaned);
        cleaned = Brackets().Replace(cleaned, " ");
        cleaned = ContributorSuffix().Replace(cleaned, string.Empty);
        cleaned = MultiSpace().Replace(cleaned, " ").Trim(' ', ',', ';', '/', '-', '–', '—');
        if (cleaned.Length == 0) return [];

        var coarse = StrongSeparator().Split(cleaned)
            .Select(item => item.Trim(' ', ',', ';', '/', '-', '–', '—'))
            .Where(item => item.Length > 0)
            .ToList();

        var candidates = new List<string>();
        foreach (var item in coarse)
        {
            var andParts = AndSeparator().Split(item)
                .Select(part => part.Trim())
                .Where(part => part.Length > 0)
                .ToArray();
            var logicalParts = CanSplitByWords(andParts) ? andParts : new[] { item };

            foreach (var logicalPart in logicalParts)
            {
                var commaParts = logicalPart.Split(
                    ',',
                    StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
                if (CanSplitByComma(commaParts)) candidates.AddRange(commaParts);
                else candidates.Add(logicalPart);
            }
        }

        var expandedCandidates = candidates
            .SelectMany(ExpandMergedCandidate)
            .ToList();

        var result = new List<PersonNamePart>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var candidate in expandedCandidates)
        {
            var alias = MultiSpace().Replace(candidate.Trim(), " ")
                .Trim(' ', '.', ',', ';', '/', '-', '–', '—');
            if (alias.Length < 2) continue;

            var aliasNormalized = CorrectTokens(normalizer.Normalize(alias));
            if (aliasNormalized.Length < 2 || UnknownValues.Contains(aliasNormalized)) continue;

            var tokens = aliasNormalized.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (tokens.Length == 0 || tokens.Length > 7) continue;

            var normalizedName = string.Join(' ', tokens.OrderBy(token => token, StringComparer.Ordinal));
            if (!seen.Add(normalizedName)) continue;

            var displayName = CanonicalDisplay(alias, tokens);
            result.Add(new PersonNamePart(displayName, normalizedName, alias, aliasNormalized));
        }

        return result;
    }


    private IEnumerable<string> ExpandMergedCandidate(string candidate)
    {
        var originalTokens = PersonToken().Matches(candidate)
            .Select(match => match.Value)
            .ToArray();
        if (originalTokens.Length is < 4 or > 8 || originalTokens.Length % 2 != 0)
        {
            yield return candidate;
            yield break;
        }

        var normalizedTokens = originalTokens
            .Select(token => CorrectTokens(normalizer.Normalize(token)))
            .ToArray();
        if (normalizedTokens.Any(string.IsNullOrWhiteSpace) || normalizedTokens.Any(IsPatronymic))
        {
            yield return candidate;
            yield break;
        }

        var givenIndexes = normalizedTokens
            .Select((token, index) => (token, index))
            .Where(item => GivenNames.Contains(item.token))
            .Select(item => item.index)
            .ToArray();
        var surnameIndexes = normalizedTokens
            .Select((token, index) => (token, index))
            .Where(item => !GivenNames.Contains(item.token))
            .Select(item => item.index)
            .ToArray();
        var peopleCount = originalTokens.Length / 2;

        // Разделяем только при высокой уверенности: половина токенов — известные
        // имена, половина — фамилии с характерными окончаниями. Для двух людей
        // сохраняем поддержку смешанных последовательностей; для трёх и четырёх
        // принимаем только блочный или строго чередующийся порядок.
        if (givenIndexes.Length != peopleCount || surnameIndexes.Length != peopleCount ||
            surnameIndexes.Any(index => !LooksLikeRussianSurname(normalizedTokens[index])))
        {
            yield return candidate;
            yield break;
        }

        IReadOnlyList<PersonTokenPair> pairs;
        if (originalTokens.Length == 4)
        {
            pairs = PairTwoPeople(givenIndexes, surnameIndexes);
        }
        else if (!TryPairOrderedPeople(
                     givenIndexes,
                     surnameIndexes,
                     originalTokens.Length,
                     out pairs))
        {
            yield return candidate;
            yield break;
        }

        foreach (var pair in pairs)
            yield return $"{originalTokens[pair.SurnameIndex]} {originalTokens[pair.GivenIndex]}";
    }

    private static bool TryPairOrderedPeople(
        IReadOnlyList<int> givenIndexes,
        IReadOnlyList<int> surnameIndexes,
        int tokenCount,
        out IReadOnlyList<PersonTokenPair> pairs)
    {
        var peopleCount = tokenCount / 2;
        var firstBlock = Enumerable.Range(0, peopleCount).ToArray();
        var secondBlock = Enumerable.Range(peopleCount, peopleCount).ToArray();
        var even = Enumerable.Range(0, peopleCount).Select(index => index * 2).ToArray();
        var odd = Enumerable.Range(0, peopleCount).Select(index => index * 2 + 1).ToArray();

        if (surnameIndexes.SequenceEqual(firstBlock) && givenIndexes.SequenceEqual(secondBlock))
        {
            pairs = Enumerable.Range(0, peopleCount)
                .Select(index => new PersonTokenPair(secondBlock[index], firstBlock[index]))
                .ToArray();
            return true;
        }

        if (givenIndexes.SequenceEqual(firstBlock) && surnameIndexes.SequenceEqual(secondBlock))
        {
            pairs = Enumerable.Range(0, peopleCount)
                .Select(index => new PersonTokenPair(firstBlock[index], secondBlock[index]))
                .ToArray();
            return true;
        }

        if (surnameIndexes.SequenceEqual(even) && givenIndexes.SequenceEqual(odd))
        {
            pairs = Enumerable.Range(0, peopleCount)
                .Select(index => new PersonTokenPair(odd[index], even[index]))
                .ToArray();
            return true;
        }

        if (givenIndexes.SequenceEqual(even) && surnameIndexes.SequenceEqual(odd))
        {
            pairs = Enumerable.Range(0, peopleCount)
                .Select(index => new PersonTokenPair(even[index], odd[index]))
                .ToArray();
            return true;
        }

        pairs = [];
        return false;
    }

    private static IReadOnlyList<PersonTokenPair> PairTwoPeople(
        IReadOnlyList<int> givenIndexes,
        IReadOnlyList<int> surnameIndexes)
    {
        var g0 = givenIndexes[0];
        var g1 = givenIndexes[1];
        var s0 = surnameIndexes[0];
        var s1 = surnameIndexes[1];

        // Сначала поддерживаем распространённые последовательности:
        // Ф1 Ф2 И1 И2, И1 И2 Ф1 Ф2, Ф1 И1 Ф2 И2, И1 Ф1 И2 Ф2.
        if (g0 == 2 && g1 == 3 || g0 == 0 && g1 == 1)
            return [new PersonTokenPair(g0, s0), new PersonTokenPair(g1, s1)];
        if (g0 == 1 && g1 == 3)
            return [new PersonTokenPair(g0, 0), new PersonTokenPair(g1, 2)];
        if (g0 == 0 && g1 == 2)
            return [new PersonTokenPair(g0, 1), new PersonTokenPair(g1, 3)];

        // Смешанные варианты: Ф1 И1 И2 Ф2 и И1 Ф1 Ф2 И2.
        if (g0 == 1 && g1 == 2)
            return [new PersonTokenPair(g0, 0), new PersonTokenPair(g1, 3)];
        return [new PersonTokenPair(g0, 1), new PersonTokenPair(g1, 2)];
    }

    private static bool LooksLikeRussianSurname(string token) =>
        token.Length >= 4 && (
            token.EndsWith("ов", StringComparison.Ordinal) ||
            token.EndsWith("ев", StringComparison.Ordinal) ||
            token.EndsWith("ин", StringComparison.Ordinal) ||
            token.EndsWith("ын", StringComparison.Ordinal) ||
            token.EndsWith("ова", StringComparison.Ordinal) ||
            token.EndsWith("ева", StringComparison.Ordinal) ||
            token.EndsWith("ина", StringComparison.Ordinal) ||
            token.EndsWith("ына", StringComparison.Ordinal) ||
            token.EndsWith("ский", StringComparison.Ordinal) ||
            token.EndsWith("цкий", StringComparison.Ordinal) ||
            token.EndsWith("ская", StringComparison.Ordinal) ||
            token.EndsWith("цкая", StringComparison.Ordinal) ||
            token.EndsWith("енко", StringComparison.Ordinal) ||
            token.EndsWith("ук", StringComparison.Ordinal) ||
            token.EndsWith("юк", StringComparison.Ordinal) ||
            token.EndsWith("ич", StringComparison.Ordinal) ||
            token.EndsWith("ян", StringComparison.Ordinal));

    private static string CorrectTokens(string value)
    {
        var tokens = value.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        for (var index = 0; index < tokens.Length; index++)
        {
            if (TokenCorrections.TryGetValue(tokens[index], out var corrected))
                tokens[index] = corrected;
        }
        return string.Join(' ', tokens);
    }

    private string CanonicalDisplay(string alias, IReadOnlyList<string> normalizedTokens)
    {
        var displayTokens = MultiSpace().Split(alias.Trim())
            .Where(token => token.Length > 0)
            .Select(token => token.Trim('.', ',', ';', ':'))
            .Where(token => token.Length > 0)
            .Select(NormalizeDisplayToken)
            .ToList();

        for (var index = 0; index < displayTokens.Count; index++)
        {
            var normalized = normalizer.Normalize(displayTokens[index]);
            if (TokenCorrections.TryGetValue(normalized, out var corrected))
                displayTokens[index] = NormalizeDisplayToken(corrected);
        }

        if (displayTokens.Count != normalizedTokens.Count || displayTokens.Count is < 2 or > 4)
            return string.Join(' ', displayTokens);

        var givenIndexes = normalizedTokens
            .Select((token, index) => (token, index))
            .Where(item => GivenNames.Contains(item.token))
            .Select(item => item.index)
            .ToArray();
        if (givenIndexes.Length != 1)
            return string.Join(' ', displayTokens);

        var givenIndex = givenIndexes[0];
        var patronymicIndex = normalizedTokens
            .Select((token, index) => (token, index))
            .Where(item => item.index != givenIndex && IsPatronymic(item.token))
            .Select(item => item.index)
            .DefaultIfEmpty(-1)
            .First();

        var ordered = new List<string> { displayTokens[givenIndex] };
        if (patronymicIndex >= 0) ordered.Add(displayTokens[patronymicIndex]);
        ordered.AddRange(displayTokens.Where((_, index) => index != givenIndex && index != patronymicIndex));
        return string.Join(' ', ordered);
    }

    private static string NormalizeDisplayToken(string token)
    {
        if (token.Length == 1) return token.ToUpperInvariant();
        if (token.All(char.IsUpper) || token.All(char.IsLower))
            return char.ToUpperInvariant(token[0]) + token[1..].ToLowerInvariant();
        return token;
    }

    private static bool IsPatronymic(string token) =>
        token.EndsWith("ович", StringComparison.Ordinal) ||
        token.EndsWith("евич", StringComparison.Ordinal) ||
        token.EndsWith("ич", StringComparison.Ordinal) ||
        token.EndsWith("овна", StringComparison.Ordinal) ||
        token.EndsWith("евна", StringComparison.Ordinal) ||
        token.EndsWith("ична", StringComparison.Ordinal);

    private static bool CanSplitByComma(IReadOnlyList<string> parts)
    {
        if (parts.Count < 2 || parts.Count > 8) return false;
        return CanSplitByWords(parts);
    }

    private static bool CanSplitByWords(IReadOnlyList<string> parts)
    {
        if (parts.Count < 2 || parts.Count > 8) return false;
        return parts.All(part =>
        {
            var words = MultiSpace().Split(part.Trim())
                .Where(word => word.Length > 0)
                .ToArray();
            return words.Length is >= 2 and <= 5;
        });
    }

    [GeneratedRegex(@"(?i)^\s*(?:автор(?:ы)?|читает|чтец|исполнител(?:ь|и))\s*[:\-–—]?\s*")]
    private static partial Regex Prefix();

    [GeneratedRegex(@"(?i)\s*,?\s*(?:и\s+(?:другие|др\.?|прочие)|and\s+others|et\s+al\.?)\s*$")]
    private static partial Regex ContributorSuffix();

    [GeneratedRegex(@"\[[^\]]*\]|\([^\)]*\)")]
    private static partial Regex Brackets();

    [GeneratedRegex(@"\s*(?:;|/|\\|\s+&\s+)\s*", RegexOptions.IgnoreCase)]
    private static partial Regex StrongSeparator();

    [GeneratedRegex(@"\s+и\s+", RegexOptions.IgnoreCase)]
    private static partial Regex AndSeparator();

    [GeneratedRegex(@"[\p{L}][\p{L}'’\-]*")]
    private static partial Regex PersonToken();

    private static string SplitGluedNameBoundaries(string value) =>
        GluedNameBoundary().Replace(value, " ");

    [GeneratedRegex(@"(?<=[\p{Ll}])(?=[\p{Lu}])", RegexOptions.CultureInvariant)]
    private static partial Regex GluedNameBoundary();

    [GeneratedRegex(@"\s+")]
    private static partial Regex MultiSpace();

    private sealed record PersonTokenPair(int GivenIndex, int SurnameIndex);
}

public sealed record PersonNamePart(
    string DisplayName,
    string NormalizedName,
    string AliasName,
    string AliasNormalizedName);
