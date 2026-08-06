using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class RutorHtmlParserTests
{
    [Fact]
    public async Task Parses_audiobooks_and_ignores_text_books()
    {
        const string html = """
        <html><body>
          <a href="/browse/1/11/0/0">101-200</a>
          <a href="/browse/535/11/0/0">53501-53506</a>
          <div id="index"><table>
            <tr class="backgr"><td>Добавлен</td><td>Название</td><td>Размер</td><td>Пиры</td></tr>
            <tr class="gai">
              <td>05 Авг 26</td>
              <td>
                <a href="magnet:?xt=urn:btih:cef6c35262fcd854047ed370a606b55320c7d29b&amp;dn=rutor.info">M</a>
                <a href="/torrent/1101334/example-mp3">Автор - Книга (2026) MP3</a>
              </td>
              <td>1</td><td>66.41 MB</td>
              <td><span class="green">33</span><span class="red">9</span></td>
            </tr>
            <tr class="tum">
              <td>05 Авг 26</td>
              <td>
                <a href="magnet:?xt=urn:btih:66819f43c99ade6bb443531f07ff738718c1cf7d&amp;dn=rutor.info">M</a>
                <a href="/torrent/1101331/example-fb2">Автор - Книга (2026) FB2</a>
              </td>
              <td>0</td><td>11.28 MB</td>
              <td><span class="green">41</span><span class="red">4</span></td>
            </tr>
          </table></div>
        </body></html>
        """;

        Assert.Equal(2, RutorHtmlParser.CurrentParserVersion);

        var parser = new RutorHtmlParser();
        var page = await parser.ParseListingAsync(
            html,
            new Uri("https://rutor.info/browse/0/11/0/0"),
            RutorSourceDefinition.BooksCategoryId,
            1,
            CancellationToken.None);

        var item = Assert.Single(page.Items);
        Assert.Equal(1101334, item.TopicId);
        Assert.Equal("Автор - Книга (2026) MP3", item.Title);
        Assert.Equal("cef6c35262fcd854047ed370a606b55320c7d29b", item.InfoHash);
        Assert.Equal(66_41L * 1024 * 1024 / 100, item.SizeBytes);
        Assert.Equal(33, item.Seeders);
        Assert.Equal(9, item.Leechers);
        Assert.Equal(536, page.TotalPages);
        Assert.Equal(2, page.SourceRows);
    }

    [Theory]
    [InlineData("Автор - Название (2026) MP3", true)]
    [InlineData("Автор - Название (2026) МР3", true)]
    [InlineData("Автор - Название (2026) M4B", true)]
    [InlineData("Сборник радиоспектаклей", true)]
    [InlineData("Автор - Название (2026) FB2", false)]
    public void Classifies_audio_titles(string title, bool expected)
    {
        Assert.Equal(expected, RutorHtmlParser.IsAudiobookTitle(title));
    }

    [Theory]
    [InlineData("МР3", "MP3")]
    [InlineData("MP3", "MP3")]
    [InlineData("МP3", "MP3")]
    [InlineData("MР3", "MP3")]
    public void Normalizes_mixed_cyrillic_mp3_tokens(string value, string expected)
    {
        Assert.Equal(expected, RutorHtmlParser.NormalizeAudioTokens(value));
    }
}
