using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class TitleNormalizerTests
{
    private readonly TitleNormalizer _normalizer = new(new SeriesNameParser());

    [Fact]
    public void Parse_extracts_author_series_position_and_audio_metadata()
    {
        var parsed = _normalizer.Parse(
            "Лукьяненко Сергей - Соглашение 01: Порог [MP3, 128 kbps, 2024]");

        Assert.Equal("Лукьяненко Сергей", parsed.Author);
        Assert.Equal("Порог", parsed.Title);
        Assert.Equal("Соглашение", parsed.Series);
        Assert.Equal(1m, parsed.SeriesPosition);
        Assert.Equal("MP3", parsed.AudioFormat);
        Assert.Equal(128, parsed.BitrateKbps);
        Assert.Equal(2024, parsed.ReleaseYear);
        Assert.Equal("ru", parsed.Language);
    }

    [Theory]
    [InlineData("Автор - Измененные 3: Месяц за Рубиконом MP3", "Измененные", 3, "Месяц за Рубиконом")]
    [InlineData("Автор - Дозоры 4, Последний Дозор MP3", "Дозоры", 4, "Последний Дозор")]
    [InlineData("Автор - Слаживание 1. Поиски утраченного завтра MP3", "Слаживание", 1, "Поиски утраченного завтра")]
    [InlineData("Автор - Небесное воинство 01. Седьмой MP3", "Небесное воинство", 1, "Седьмой")]
    public void Parse_extracts_punctuated_series_fallback(
        string raw,
        string expectedSeries,
        int expectedPosition,
        string expectedTitle)
    {
        var parsed = _normalizer.Parse(raw);

        Assert.Equal(expectedSeries, parsed.Series);
        Assert.Equal(expectedPosition, parsed.SeriesPosition);
        Assert.Equal(expectedTitle, parsed.Title);
        Assert.Equal("MP3", parsed.AudioFormat);
    }

    [Theory]
    [InlineData("Автор - Метро 2033 MP3", "Метро 2033")]
    [InlineData("Автор - Зона 31 MP3", "Зона 31")]
    public void Parse_does_not_treat_plain_number_as_series(string raw, string expectedTitle)
    {
        var parsed = _normalizer.Parse(raw);

        Assert.Null(parsed.Series);
        Assert.Null(parsed.SeriesPosition);
        Assert.Null(parsed.ReleaseYear);
        Assert.Equal(expectedTitle, parsed.Title);
    }

    [Fact]
    public void Normalize_collapses_punctuation_and_yo()
    {
        Assert.Equal("ежик в тумане", _normalizer.Normalize("  Ёжик — в  тумане! "));
    }
}
