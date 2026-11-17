package com.example.ui.viewmodel

import com.example.data.textsource.TextImportStorageException
import com.example.data.textsource.TextImportStorageFailure
import com.example.data.textsource.TextSourceContentException
import com.example.data.textsource.TextSourceContentFailure
import com.example.data.textsource.TextSourceHttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

internal enum class TextCatalogOperation { CATALOG, BOOK, IMPORT, LIBRARY }

/** Fixed user-facing copy: URLs, response bodies and exception messages stay out of the UI. */
internal fun textCatalogError(error: Throwable, operation: TextCatalogOperation): String {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(16).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    // Local storage failures must retain partial-success semantics even if their cause is network IO.
    causes.filterIsInstance<TextImportStorageException>().firstOrNull()?.let {
        return when (it.reason) {
            TextImportStorageFailure.PERMISSION -> "Разрешите приложению запись в хранилище и повторите добавление книги."
            TextImportStorageFailure.PUBLICATION -> "Книга уже добавлена в библиотеку, но оригинал не сохранён в Books. Проверьте свободное место и доступ к хранилищу, затем повторите загрузку."
            TextImportStorageFailure.SOURCE_LINK -> "Книга уже добавлена в библиотеку, но связь с источником не сохранена. Откройте книгу в библиотеке или повторите её открытие в каталоге."
        }
    }
    val challenge = "Сайт требует проверку браузера. Загрузка в приложении сейчас недоступна. Попробуйте позже или выберите другую книгу."
    causes.filterIsInstance<TextSourceHttpException>().firstOrNull()?.let {
        return when {
            it.browserChallenge -> challenge
            it.status == 404 || it.status == 410 -> if (operation == TextCatalogOperation.IMPORT) {
                "Файл книги больше недоступен на сайте. Выберите другой формат или другую книгу."
            } else if (operation == TextCatalogOperation.BOOK) {
                "Книга больше недоступна на сайте. Вернитесь в каталог и выберите другую книгу."
            } else "Страница больше недоступна на сайте. Вернитесь в каталог или измените запрос."
            it.status == 401 || it.status == 403 -> "Сайт ограничил доступ. Повторите позже или выберите другую книгу."
            it.status == 429 -> "Слишком много запросов к сайту. Подождите немного и повторите."
            it.status == 408 || it.status == 504 -> "Сайт не ответил вовремя. Проверьте соединение и повторите."
            it.status in 500..599 -> "На сайте временный сбой. Повторите позже."
            else -> "Сайт не смог выполнить запрос. Повторите позже или выберите другую книгу."
        }
    }
    causes.filterIsInstance<TextSourceContentException>().firstOrNull()?.let {
        return when (it.reason) {
            TextSourceContentFailure.BROWSER_CHALLENGE -> challenge
            TextSourceContentFailure.TOO_LARGE -> if (operation == TextCatalogOperation.IMPORT) {
                "Файл книги слишком большой. Выберите другой формат или другую книгу."
            } else "Сайт вернул слишком большую страницу. Попробуйте открыть другую страницу."
            TextSourceContentFailure.FILE -> "Сайт вернул неподдерживаемый или повреждённый файл книги. Выберите другой формат или другую книгу."
            TextSourceContentFailure.PAGE -> "Не удалось прочитать страницу сайта. Возможно, её формат изменился. Повторите позже; если ошибка остаётся, отправьте отчёт через настройки диагностики."
        }
    }
    return when {
        operation == TextCatalogOperation.LIBRARY -> "Не удалось проверить книгу в библиотеке. Повторите открытие книги."
        causes.any { it is SocketTimeoutException } -> "Сайт не ответил вовремя. Проверьте соединение и повторите."
        causes.any { it is UnknownHostException || it is ConnectException } -> "Не удалось подключиться к сайту. Проверьте интернет-соединение и повторите."
        causes.any { it is SSLException } -> "Не удалось установить защищённое соединение с сайтом. Проверьте дату и время устройства или повторите позже."
        operation == TextCatalogOperation.IMPORT -> "Не удалось добавить книгу. Проверьте соединение, свободное место и доступ к хранилищу, затем повторите. Можно выбрать другой формат книги."
        causes.any { it is IOException } -> "Соединение с сайтом прервалось. Проверьте интернет и повторите."
        else -> "Не удалось загрузить данные сайта. Повторите позже; если ошибка остаётся, отправьте отчёт через настройки диагностики."
    }
}

/** Paging keeps the original cause while existing refresh/footer controls receive safe copy. */
internal class TextCatalogLoadException(cause: Throwable) : Exception(
    textCatalogError(cause, TextCatalogOperation.CATALOG), cause,
)
