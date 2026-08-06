using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class PersonNameParserHotfixTests
{
    private readonly PersonNameParser _parser;

    public PersonNameParserHotfixTests()
    {
        var normalizer = new TitleNormalizer(new SeriesNameParser());
        _parser = new PersonNameParser(normalizer);
    }

    [Fact]
    public void Splits_glued_given_name_and_surname_before_comma_grouping()
    {
        var authors = _parser.ParseAuthors("РоманЗлотников, Даниил Калинин");

        Assert.Equal(
            new[] { "Роман Злотников", "Даниил Калинин" },
            authors.Select(author => author.DisplayName));
    }

    [Fact]
    public void Splits_three_authors_from_surname_and_given_name_blocks()
    {
        var authors = _parser.ParseAuthors(
            "Злотников, Волков, Минаков Роман, Алексей, Игорь");

        Assert.Equal(
            new[] { "Роман Злотников", "Алексей Волков", "Игорь Минаков" },
            authors.Select(author => author.DisplayName));
    }

    [Theory]
    [InlineData("Карл Густав Юнг", "Карл Густав Юнг")]
    [InlineData("Жан-Клод Ван Дамм", "Жан-Клод Ван Дамм")]
    [InlineData("Макс Фрай", "Макс Фрай")]
    public void Keeps_regular_single_person_names_as_one_value(string raw, string expected)
    {
        var authors = _parser.ParseAuthors(raw);

        var author = Assert.Single(authors);
        Assert.Equal(expected, author.DisplayName);
    }
}
