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

    [Fact]
    public void Normalize_collapses_punctuation_and_yo()
    {
        Assert.Equal("ежик в тумане", _normalizer.Normalize("  Ёжик — в  тумане! "));
    }
}
