using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class RutorDetailParserTests
{
    private readonly RutorDetailParser _parser;

    public RutorDetailParserTests()
    {
        var normalizer = new TitleNormalizer(new SeriesNameParser());
        _parser = new RutorDetailParser(
            normalizer,
            new PersonNameParser(normalizer));
    }

    [Fact]
    public void Parses_standard_fields_and_duration_over_24_hours()
    {
        const string html = """
        <html><body>
          <div id="download">
            <a href="magnet:?xt=urn:btih:3175490ca61c3eca29eaf2c2c32ee491a048372e&amp;dn=rutor.info">M</a>
          </div>
          <table id="details"><tr><td></td><td>
            <b>Название: </b>Архитектор душ (9 книг)<br />
            <b>Жанр: </b>Фэнтези, городское, аудиокнига<br />
            <b>Автор: </b>Сергей Карелин, Александр Вольт<br />
            <b>Озвучивает: </b>Константин Суханов<br />
            <b>Год издания книги: </b>2025-2026<br />
            <b>Издательство: </b>ЛитРес<br />
            <b>Продолжительность: </b>88:02:21<br />
            <b>Описание: </b>Описание с двоеточием: не является полем.<br />
            <b>Формат/Кодек: </b>MP3<br />
            <b>Битрейт аудио: </b>128 kbps
          </td></tr></table>
        </body></html>
        """;

        var result = _parser.Parse(
            html,
            "Сергей Карелин, Александр Вольт - Архитектор душ [9 книг] (2025-2026) MP3");

        Assert.Equal(2, RutorDetailParser.CurrentParserVersion);
        Assert.Equal("3175490ca61c3eca29eaf2c2c32ee491a048372e", result.InfoHash);
        Assert.Equal("Архитектор душ (9 книг)", result.Metadata.ParsedTitle.Title);
        Assert.Equal("Сергей Карелин, Александр Вольт", result.Metadata.ParsedTitle.Author);
        Assert.Equal(new[] { "Константин Суханов" }, result.Metadata.ParsedTitle.Narrators);
        Assert.Equal(2026, result.Metadata.ParsedTitle.ReleaseYear);
        Assert.Equal("MP3", result.Metadata.ParsedTitle.AudioFormat);
        Assert.Equal(128, result.Metadata.ParsedTitle.BitrateKbps);
        Assert.Equal(316_941L, result.Metadata.DurationSeconds);
        Assert.Equal(new[] { "Фэнтези", "городское" }, result.Metadata.Genres);
        Assert.Equal("ЛитРес", result.Metadata.Publisher);
    }

    [Fact]
    public void Parses_colon_outside_bold_and_normalizes_cyrillic_mp3()
    {
        const string html = """
        <html><body>
          <div id="download">
            <a href="magnet:?xt=urn:btih:535a71ab484ea3bcb4698eb1d8d2e595f9502078&amp;dn=rutor.info">M</a>
          </div>
          <table id="details"><tr><td></td><td>
            <b>Название</b>: Отношения между эго и бессознательным<br />
            <b>Автор</b>: Карл Густав Юнг<br />
            <b>Год выпуска</b>: 2026<br />
            <b>Жанр</b>: Психология, философия, аудиокнига<br />
            <b>Издательство</b>: АСТ<br />
            <b>Исполнитель</b>: Алексей Воскобойников<br />
            <b>Формат</b>: аудиокнига, МР3, 96 kbps<br />
            <b>Продолжительность</b>: 07:53:00
          </td></tr></table>
        </body></html>
        """;

        var result = _parser.Parse(
            html,
            "Карл Густав Юнг - Отношения между эго и бессознательным (2026) МР3");

        Assert.Equal("Отношения между эго и бессознательным", result.Metadata.ParsedTitle.Title);
        Assert.Equal("Карл Густав Юнг", result.Metadata.ParsedTitle.Author);
        Assert.Equal(new[] { "Алексей Воскобойников" }, result.Metadata.ParsedTitle.Narrators);
        Assert.Equal("MP3", result.Metadata.ParsedTitle.AudioFormat);
        Assert.Equal(96, result.Metadata.ParsedTitle.BitrateKbps);
        Assert.Equal(28_380L, result.Metadata.DurationSeconds);
        Assert.Equal(new[] { "Психология", "философия" }, result.Metadata.Genres);
    }
}
