using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class RutorAuthorHotfixTests
{
    [Fact]
    public void Normalizes_glued_explicit_authors_before_metadata_is_saved()
    {
        var normalizer = new TitleNormalizer(new SeriesNameParser());
        var parser = new RutorDetailParser(
            normalizer,
            new PersonNameParser(normalizer));
        const string html = """
        <html><body>
          <div id="download">
            <a href="magnet:?xt=urn:btih:88ca1f92f80a4ca0e5b3fa4882702916d4148ae3&amp;dn=rutor.info">M</a>
          </div>
          <table id="details"><tr><td></td><td>
            <b>Название: </b>Куликовская сеча<br />
            <b>Автор: </b>РоманЗлотников, Даниил Калинин<br />
            <b>Озвучивает: </b>Макс Радман<br />
            <b>Год выпуска: </b>2024<br />
            <b>Формат: </b>MP3, 128 kbps<br />
            <b>Продолжительность: </b>10:00:00
          </td></tr></table>
        </body></html>
        """;

        var result = parser.Parse(
            html,
            "Роман Злотников, Даниил Калинин - Князь Фёдор 1, Куликовская сеча MP3");

        Assert.Equal(3, RutorDetailParser.CurrentParserVersion);
        Assert.Equal(
            "Роман Злотников, Даниил Калинин",
            result.Metadata.ParsedTitle.Author);
    }
}
