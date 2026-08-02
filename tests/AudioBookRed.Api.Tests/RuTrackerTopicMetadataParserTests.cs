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

        Assert.Equal(5, result.ParserVersion);
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
        Assert.False(result.ClearSeriesPosition);
        Assert.False(result.ClearPublisher);
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
        Assert.False(result.ClearSeriesPosition);
        Assert.False(result.ClearPublisher);
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
    public void Separates_adjacent_centered_author_and_title_before_first_field()
    {
        var result = _parser.Parse(
            Wrap("""
                <span class="post-align" style="text-align: center;">
                  <span class="post-b"><span style="font-size: 26px">Т. Р. Нэппер</span></span>
                </span><span class="post-align" style="text-align: center;">
                  <span class="post-b"><span style="font-size: 22px">Призрак неонового бога</span></span>
                </span><var class="postImg" title="cover">&#10;</var><span class="post-b">Год выпуска</span>: 2026<br>
                <span class="post-b">Фамилия автора</span>: Нэппер<br>
                <span class="post-b">Имя автора</span>: Т. Р.<br>
                <span class="post-b">Исполнитель</span>: Игорь Князев<br>
                <span class="post-b">Жанр</span>: Зарубежная фантастика, Киберпанк<br>
                <span class="post-b">Издательство</span>: fanzon<br>
                <span class="post-b">Аудиокодек</span>: MP3<br>
                <span class="post-b">Битрейт</span>: 128 kbps<br>
                <span class="post-b">Вид битрейта</span>: постоянный битрейт (CBR)<br>
                <span class="post-b">Частота дискретизации</span>: 44 kHz<br>
                <span class="post-b">Количество каналов (моно-стерео)</span>: Стерео<br>
                <span class="post-b">Время звучания</span>: 17:52:25
                """),
            "Нэппер Т. Р. - Призрак неонового бога [Игорь Князев, 2026, 128 kbps, MP3]");

        Assert.Equal(5, result.ParserVersion);
        Assert.Equal("Призрак неонового бога", result.ParsedTitle.Title);
        Assert.Equal("Нэппер Т. Р.", result.ParsedTitle.Author);
        Assert.Equal(new[] { "Игорь Князев" }, result.ParsedTitle.Narrators);
        Assert.Equal(2026, result.ParsedTitle.ReleaseYear);
        Assert.Equal(new[] { "Зарубежная фантастика", "Киберпанк" }, result.Genres);
        Assert.Equal("fanzon", result.Publisher);
        Assert.Equal("MP3", result.ParsedTitle.AudioFormat);
        Assert.Equal(128, result.ParsedTitle.BitrateKbps);
        Assert.Equal(44_000, result.SampleRateHz);
        Assert.Equal("Стерео", result.AudioChannels);
        Assert.Equal("CBR", result.BitrateMode);
        Assert.Equal(64_345L, result.DurationSeconds);
    }

    [Fact]
    public void Combines_split_title_with_volume_suffix()
    {
        var result = _parser.Parse(
            Wrap("""
                <var class="postImg" title="cover">&#10;</var><hr><hr>
                <span class="post-b">
                  <span class="post-align" style="text-align: center;">
                    <span style="font-size: 24px;">Жизнь Пушкина</span><br>
                    <span style="font-size: 20px;">том 1</span>
                  </span>
                </span><hr><hr>
                <span class="post-b">Год выпуска</span>: 2013 г.<br>
                <span class="post-b">Фамилия автора</span>: Тыркова-Вильямс<br>
                <span class="post-b">Имя автора</span>: Ариадна<br>
                <span class="post-b">Исполнитель</span>: Терновский Евгений<hr><hr>
                <span class="post-b">Жанр</span>: Биографии<br>
                <span class="post-b">Тип издания</span>: нигде не купишь<br>
                <span class="post-b">Категория</span>: аудиокнига<br>
                <span class="post-b">Аудиокодек</span>: MP3<br>
                <span class="post-b">Битрейт</span>: 96 kbps<br>
                <span class="post-b">Время звучания</span>: 24:50:36
                """),
            "Тыркова-Вильямс Ариадна - Жизнь Пушкина (том 1) [Терновский Евгений, 2013 г., 96 kbps, MP3]");

        Assert.Equal(5, result.ParserVersion);
        Assert.Equal("Жизнь Пушкина (том 1)", result.ParsedTitle.Title);
        Assert.Equal("Тыркова-Вильямс Ариадна", result.ParsedTitle.Author);
        Assert.Equal(new[] { "Терновский Евгений" }, result.ParsedTitle.Narrators);
        Assert.Equal(2013, result.ParsedTitle.ReleaseYear);
        Assert.Equal("MP3", result.ParsedTitle.AudioFormat);
        Assert.Equal(96, result.ParsedTitle.BitrateKbps);
        Assert.Equal(89_436L, result.DurationSeconds);
        Assert.Equal(new[] { "Биографии" }, result.Genres);
        Assert.Null(result.Publisher);
        Assert.Equal("нигде не купишь", result.EditionType);
        Assert.Equal("аудиокнига", result.EditionCategory);
    }

    [Fact]
    public void Prefers_fallback_title_and_splits_adjacent_field_labels()
    {
        var result = _parser.Parse(
            Wrap("""
                <span class="post-align" style="text-align: center;">
                  <span class="post-b">
                    <span style="font-size: 24px;">"Сатурн" почти не виден</span><br>
                    Прочитано по изданию: М.Вече, 2008 г.
                  </span>
                </span><hr>
                <var class="postImg" title="cover">&#10;</var>
                <span class="post-b">Год выпуска</span>: 2010 г.<br>
                <span class="post-b">Фамилия автора</span>:
                <span class="post-b">Ардаматский</span>
                <span class="post-b">Имя автора</span>:
                <span class="post-b">Василий</span><br>
                <span class="post-b">Исполнитель</span>: Герасимов Вячеслав<br>
                <span class="post-b">Жанр</span>: Детектив<br>
                <span class="post-b">Издательство</span>: Нигде не купишь<br>
                <span class="post-b">Тип аудиокниги</span>: аудиокнига<br>
                <span class="post-b">Аудио кодек</span>: MP3<br>
                <span class="post-b">Битрейт аудио</span>: 96 kbps, 44 kHz, Mono<br>
                <span class="post-b">Цикл/серия</span>: Сатурн почти не виден<br>
                <span class="post-b">Номер книги</span>: 1 - 2
                """),
            "Ардаматский Василий - \"Сатурн\" почти не виден [Герасимов Вячеслав, 2010 г., 96 kbps, 44 kHz, Mono, MP3]");

        Assert.Equal(5, result.ParserVersion);
        Assert.Equal("\"Сатурн\" почти не виден", result.ParsedTitle.Title);
        Assert.Equal("Ардаматский Василий", result.ParsedTitle.Author);
        Assert.Equal("Сатурн почти не виден", result.ParsedTitle.Series);
        Assert.Null(result.ParsedTitle.SeriesPosition);
        Assert.True(result.ClearSeriesPosition);
        Assert.False(result.ClearPublisher);
        Assert.Equal("Нигде не купишь", result.Publisher);
        Assert.Equal("аудиокнига", result.EditionType);
        Assert.Equal("MP3", result.ParsedTitle.AudioFormat);
        Assert.Equal(96, result.ParsedTitle.BitrateKbps);
        Assert.Equal(44_000, result.SampleRateHz);
        Assert.Equal("Mono", result.AudioChannels);
    }

    [Fact]
    public void Separates_unformatted_first_field_and_accepts_legacy_audio_labels()
    {
        var result = _parser.Parse(
            Wrap("""
                <span style="font-size: 24px;">Али-баба и 40 разбойников</span>
                <var class="postImg" title="cover">&#10;</var>Год выпуска: 1981<br>
                <span class="post-b">Автор</span>: Постановка - В.Смехов<br>
                <span class="post-b">Исполнитель</span>: О.Табаков, Т.Никитина<br>
                <span class="post-b">Жанр</span>: музыкальная сказка<br>
                <span class="post-b">Издательство</span>: © "МЕЛОДИЯ", 1982<br>
                <span class="post-b">Тип</span>: аудиоспектакль<br>
                <span class="post-b">Аудио кодек</span>: MP3<br>
                <span class="post-b">Битрейт аудио</span>: 256 kbps
                """),
            "Постановка - В.Смехов - Али-баба и 40 разбойников [О.Табаков, 1981, 256 kbps]");

        Assert.Equal(5, result.ParserVersion);
        Assert.Equal("Али-баба и 40 разбойников", result.ParsedTitle.Title);
        Assert.Equal("Постановка - В.Смехов", result.ParsedTitle.Author);
        Assert.Equal(1981, result.ParsedTitle.ReleaseYear);
        Assert.Equal("© \"МЕЛОДИЯ\", 1982", result.Publisher);
        Assert.Equal("аудиоспектакль", result.EditionType);
        Assert.Equal("MP3", result.ParsedTitle.AudioFormat);
        Assert.Equal(256, result.ParsedTitle.BitrateKbps);
        Assert.True(result.ParsedTitle.IsDramatized);
    }

    [Fact]
    public void Removes_trailing_contents_noise_from_publisher()
    {
        var result = _parser.Parse(
            Wrap("""
                <span style="font-size: 24px;">Черный пудель, рыжий кот, или Свадьба с препятствиями</span><br>
                <span class="post-b">Год выпуска</span>: 2018<br>
                <span class="post-b">Фамилия автора</span>: Михалкова<br>
                <span class="post-b">Имя автора</span>: Елена<br>
                <span class="post-b">Исполнитель</span>: Юлия Бочанова<br>
                <span class="post-b">Издательство</span>: Аудиокнига Оглавление<br>
                <span class="post-b">Аудиокодек</span>: MP3
                """),
            "Михалкова Елена - Черный пудель, рыжий кот, или Свадьба с препятствиями [MP3]");

        Assert.Equal(5, result.ParserVersion);
        Assert.Equal("Аудиокнига", result.Publisher);
        Assert.False(result.ClearPublisher);
    }

    [Fact]
    public void Rejects_numeric_publisher_identifier()
    {
        var result = _parser.Parse(
            Wrap("""
                <span class="post-align" style="text-align: center;">
                  <span style="font-size: 20px;">Елена Безрукова</span><br>
                  <span class="post-b">Девочка, я тебя присвою 2</span>
                </span><hr>
                <span class="post-b">Год выпуска</span>: 2024<br>
                <span class="post-b">Фамилия автора</span>: Безрукова<br>
                <span class="post-b">Имя автора</span>: Елена<br>
                <span class="post-b">Цикл/серия</span>: Архип + Снежинка<br>
                <span class="post-b">Номер книги</span>: 2<br>
                <span class="post-b">Издательство</span>: 140570025756<br>
                <span class="post-b">Аудиокодек</span>: MP3
                """),
            "Безрукова Елена - Архип+Снежинка #2. Девочка, я тебя присвою 2 [MP3]");

        Assert.Equal(5, result.ParserVersion);
        Assert.Equal("Девочка, я тебя присвою 2", result.ParsedTitle.Title);
        Assert.Null(result.Publisher);
        Assert.True(result.ClearPublisher);
        Assert.False(result.ClearSeriesPosition);
        Assert.Equal(2m, result.ParsedTitle.SeriesPosition);
    }

    [Fact]
    public void Rejects_multi_book_series_position()
    {
        var result = _parser.Parse(
            Wrap("""
                <span style="font-size: 24px;">Цикл "Часодеи"</span><br>
                <span class="post-b">Год выпуска</span>: 2024<br>
                <span class="post-b">Фамилия автора</span>: Щерба<br>
                <span class="post-b">Имя автора</span>: Наталья<br>
                <span class="post-b">Исполнитель</span>: Наталья Терешкова<br>
                <span class="post-b">Цикл/серия</span>: Часодеи<br>
                <span class="post-b">Номер книги</span>: 1,2,3,4,5,6<br>
                <span class="post-b">Издательство</span>: Издательство "Росмэн"<br>
                <span class="post-b">Аудиокодек</span>: MP3
                """),
            "Щерба Наталья - Цикл \"Часодеи\" [Наталья Терешкова, 2024, MP3]");

        Assert.Equal(5, result.ParserVersion);
        Assert.Equal("Цикл \"Часодеи\"", result.ParsedTitle.Title);
        Assert.Equal("Часодеи", result.ParsedTitle.Series);
        Assert.Null(result.ParsedTitle.SeriesPosition);
        Assert.True(result.ClearSeriesPosition);
        Assert.False(result.ClearPublisher);
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
