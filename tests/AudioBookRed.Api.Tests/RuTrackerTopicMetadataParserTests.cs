using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class RuTrackerTopicMetadataParserTests
{
    private readonly RuTrackerTopicMetadataParser _parser =
        new(new TitleNormalizer(new SeriesNameParser()));

    [Fact]
    public void Parses_combined_authors_and_separate_audio_fields()
    {
        var result = _parser.Parse(
            Wrap("""
                <span style="font-size:24px">Мастер Рун. Книга 7</span><br>
                <span class="post-b">Год выпуска</span>: 2026<br>
                <span class="post-b">Авторы</span>: Сластин Артем Вячеславич, ПолуЁж<br>
                <span class="post-b">Исполнитель</span>: Саня БтрЪ (Студия BTR_FM)<br>
                <span class="post-b">Цикл/серия</span>: Мастер Рун<br>
                <span class="post-b">Номер книги</span>: 7<br>
                <span class="post-b">Жанр</span>: Попаданцы, Боевое фэнтези<br>
                <span class="post-b">Издательство</span>: Аудио от автора<br>
                <span class="post-b">Аудиокодек</span>: MP3<br>
                <span class="post-b">Битрейт</span>: 96 kbps<br>
                <span class="post-b">Вид битрейта</span>: постоянный битрейт (CBR)<br>
                <span class="post-b">Частота дискретизации</span>: 32kHz<br>
                <span class="post-b">Количество каналов (моно-стерео)</span>: Стерео<br>
                <span class="post-b">Время звучания</span>: 08:51:00
                """),
            "Сластин Артем Вячеславич, ПолуЁж - Мастер Рун 7 [MP3]");

        Assert.Equal(1, result.ParserVersion);
        Assert.Equal("Мастер Рун. Книга 7", result.ParsedTitle.Title);
        Assert.Equal(
            "Сластин Артем Вячеславич, ПолуЁж",
            result.ParsedTitle.Author);
        Assert.Equal(new[] { "Саня БтрЪ (Студия BTR_FM)" }, result.ParsedTitle.Narrators);
        Assert.Equal("Мастер Рун", result.ParsedTitle.Series);
        Assert.Equal(7m, result.ParsedTitle.SeriesPosition);
        Assert.Equal(2026, result.ParsedTitle.ReleaseYear);
        Assert.Equal("MP3", result.ParsedTitle.AudioFormat);
        Assert.Equal(96, result.ParsedTitle.BitrateKbps);
        Assert.Equal(31_860L, result.DurationSeconds);
        Assert.Equal(new[] { "Попаданцы", "Боевое фэнтези" }, result.Genres);
        Assert.Equal("Аудио от автора", result.Publisher);
        Assert.Equal(32_000, result.SampleRateHz);
        Assert.Equal("Стерео", result.AudioChannels);
        Assert.Equal("CBR", result.BitrateMode);
    }

    [Fact]
    public void Combines_separate_author_name_fields()
    {
        var result = _parser.Parse(
            Wrap("""
                <span style="font-size:24px">Моё пространственное королевство. Том 1</span><br>
                <span class="post-b">Год выпуска</span>: 2025<br>
                <span class="post-b">Фамилия автора</span>: Дорничев<br>
                <span class="post-b">Имя автора</span>: Дмитрий<br>
                <span class="post-b">Исполнитель</span>: Денис Борисов<br>
                <span class="post-b">Цикл/серия</span>: Королевство<br>
                <span class="post-b">Номер книги</span>: 01<br>
                <span class="post-b">Жанр</span>: Боевое фэнтези, Попаданцы<br>
                <span class="post-b">Издательство</span>: ЛитРес: чтец , автор<br>
                <span class="post-b">Аудиокодек</span>: MP3<br>
                <span class="post-b">Битрейт</span>: 48 kbps<br>
                <span class="post-b">Время звучания</span>: 08:43:02
                """),
            "Дорничев Дмитрий - Королевство 01 [MP3]");

        Assert.Equal(
            "Моё пространственное королевство. Том 1",
            result.ParsedTitle.Title);
        Assert.Equal("Дорничев Дмитрий", result.ParsedTitle.Author);
        Assert.Equal(new[] { "Денис Борисов" }, result.ParsedTitle.Narrators);
        Assert.Equal("Королевство", result.ParsedTitle.Series);
        Assert.Equal(1m, result.ParsedTitle.SeriesPosition);
        Assert.Equal("ЛитРес: чтец , автор", result.Publisher);
        Assert.Equal(31_382L, result.DurationSeconds);
    }

    [Fact]
    public void Leaves_series_empty_for_old_topic_without_series_fields()
    {
        var result = _parser.Parse(
            Wrap("""
                <span style="font-size:24px">Господин с трикотажной фабрики</span><br>
                <span class="post-b">Год выпуска</span>: 2020<br>
                <span class="post-b">Фамилия автора</span>: Хьюман<br>
                <span class="post-b">Имя автора</span>: Дэми<br>
                <span class="post-b">Исполнитель</span>: Булдаков Олег<br>
                <span class="post-b">Жанр</span>: мистика<br>
                <span class="post-b">Тип издания</span>: аудиокнига своими руками<br>
                <span class="post-b">Категория</span>: аудиокнига<br>
                <span class="post-b">Аудиокодек</span>: MP3<br>
                <span class="post-b">Битрейт</span>: 320 kbps<br>
                <span class="post-b">Музыкальное сопровождение</span>: присутствует постоянно<br>
                <span class="post-b">Время звучания</span>: 00:28:32
                """),
            "Хьюман Дэми - Господин с трикотажной фабрики [MP3]");

        Assert.Equal(
            "Господин с трикотажной фабрики",
            result.ParsedTitle.Title);
        Assert.Equal("Хьюман Дэми", result.ParsedTitle.Author);
        Assert.Null(result.ParsedTitle.Series);
        Assert.Null(result.ParsedTitle.SeriesPosition);
        Assert.Equal("аудиокнига своими руками", result.EditionType);
        Assert.Equal("аудиокнига", result.EditionCategory);
        Assert.Equal("присутствует постоянно", result.Music);
        Assert.Equal(1_712L, result.DurationSeconds);
    }

    [Fact]
    public void Parses_composite_quality_and_ignores_series_heading_as_title()
    {
        var result = _parser.Parse(
            Wrap("""
                <span class="post-align">
                  <span class="post-b">
                    <span style="font-size:20px">Диптаун 03</span><br>
                    -=-=-=-=-=-=-=-=-=-=-=-=-<br>
                    <span style="font-size:24px">Прозрачные витражи</span>
                  </span>
                </span><hr>
                <span class="post-b">Год выпуска</span>: 2020 г.<br>
                <span class="post-b">Фамилия автора</span>: Лукьяненко<br>
                <span class="post-b">Имя автора</span>: Сергей<br>
                <span class="post-b">Исполнитель:</span> Князев Игорь<br>
                <span class="post-b">Цикл/серия:</span> Диптаун<br>
                <span class="post-b">Номер книги:</span> 03<br>
                <span class="post-b">Жанр:</span> фантастика, киберпанк<br>
                <span class="post-b">Издательство:</span> Аудиокнига ООО<br>
                <span class="post-b">Качество:</span> mp3, 128 kbps, 44 kHz, Joint Stereo<br>
                <span class="post-b">Длительность:</span> 03:19:33
                """),
            "Лукьяненко Сергей - Диптаун 03. Прозрачные витражи [MP3]");

        Assert.Equal("Прозрачные витражи", result.ParsedTitle.Title);
        Assert.Equal("Лукьяненко Сергей", result.ParsedTitle.Author);
        Assert.Equal("Диптаун", result.ParsedTitle.Series);
        Assert.Equal(3m, result.ParsedTitle.SeriesPosition);
        Assert.Equal("MP3", result.ParsedTitle.AudioFormat);
        Assert.Equal(128, result.ParsedTitle.BitrateKbps);
        Assert.Equal(44_000, result.SampleRateHz);
        Assert.Equal("Joint Stereo", result.AudioChannels);
        Assert.Equal(11_973L, result.DurationSeconds);
    }

    [Fact]
    public void Falls_back_to_topic_title_when_author_post_is_missing()
    {
        const string raw =
            "Лукьяненко Сергей - Соглашение 01: Порог [MP3, 128 kbps, 2024]";
        var result = _parser.Parse(
            "<html><body><div class=\"post_body\">Ответ пользователя</div></body></html>",
            raw);

        var expected = new TitleNormalizer(new SeriesNameParser()).Parse(raw);
        Assert.Equal(0, result.ParserVersion);
        Assert.Equal(expected.Title, result.ParsedTitle.Title);
        Assert.Equal(expected.Author, result.ParsedTitle.Author);
        Assert.Equal(expected.Series, result.ParsedTitle.Series);
        Assert.Equal(expected.SeriesPosition, result.ParsedTitle.SeriesPosition);
        Assert.Equal(expected.Narrators, result.ParsedTitle.Narrators);
        Assert.Equal(expected.ReleaseYear, result.ParsedTitle.ReleaseYear);
        Assert.Equal(expected.AudioFormat, result.ParsedTitle.AudioFormat);
        Assert.Equal(expected.BitrateKbps, result.ParsedTitle.BitrateKbps);
        Assert.Empty(result.Genres);
    }

    private static string Wrap(string postBody) =>
        $"""
        <html>
          <body>
            <table id="topic_main">
              <tbody id="post_1" class="row1">
                <tr>
                  <td class="poster_info">
                    <p class="nick nick-author">author</p>
                  </td>
                  <td class="message">
                    <div class="post_body">{postBody}</div>
                  </td>
                </tr>
              </tbody>
              <tbody id="post_2" class="row2">
                <tr>
                  <td><p class="nick">reader</p></td>
                  <td><div class="post_body">Ложный ответ: не метаданные</div></td>
                </tr>
              </tbody>
            </table>
          </body>
        </html>
        """;
}
