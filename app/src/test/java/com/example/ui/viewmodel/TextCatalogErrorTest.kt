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
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TextCatalogErrorTest {
    private fun message(error: Throwable, operation: TextCatalogOperation = TextCatalogOperation.BOOK) =
        textCatalogError(error, operation)

    @Test fun networkFailuresOfferConnectionOrTimeoutRecoveryWithoutExposingHostnames() {
        assertTrue(message(UnknownHostException("private-host.invalid")).contains("интернет-соединение"))
        assertTrue(message(ConnectException("secret address")).contains("подключиться"))
        assertTrue(message(IOException("wrapper", SocketTimeoutException("secret"))).contains("вовремя"))
        assertTrue(message(SSLException("certificate contents")).contains("дату и время"))
    }

    @Test fun unavailableFileAndBookHaveDifferentRecoveryInstructions() {
        for (status in listOf(404, 410)) {
            assertTrue(message(TextSourceHttpException(status)).contains("Вернитесь в каталог"))
            assertTrue(message(TextSourceHttpException(status), TextCatalogOperation.IMPORT).contains("другой формат"))
            assertTrue(message(TextSourceHttpException(status), TextCatalogOperation.CATALOG).contains("измените запрос"))
        }
    }

    @Test fun forbiddenDoesNotClaimThereIsABrowserChallenge() {
        val forbidden = message(TextSourceHttpException(403))
        assertTrue(forbidden.contains("ограничил доступ"))
        assertFalse(forbidden.contains("проверку браузера"))
        assertTrue(message(TextSourceHttpException(403, browserChallenge = true)).contains("проверку браузера"))
        assertTrue(message(TextSourceContentException(TextSourceContentFailure.BROWSER_CHALLENGE)).contains("сейчас недоступна"))
    }

    @Test fun rateLimitServerErrorsAndHttpTimeoutsHaveDistinctAdvice() {
        assertTrue(message(TextSourceHttpException(429)).contains("Подождите"))
        assertTrue(message(TextSourceHttpException(503)).contains("временный сбой"))
        assertTrue(message(TextSourceHttpException(504)).contains("вовремя"))
        assertFalse(message(TextSourceHttpException(401)).contains("интернет"))
    }

    @Test fun contentErrorsSuggestFormatOrDiagnosticsWithoutConfusingThemWithConnectivity() {
        assertTrue(message(TextSourceContentException(TextSourceContentFailure.FILE)).contains("другой формат"))
        assertTrue(message(TextSourceContentException(TextSourceContentFailure.PAGE)).contains("диагностики"))
        val large = TextSourceContentException(TextSourceContentFailure.TOO_LARGE)
        assertTrue(message(large, TextCatalogOperation.IMPORT).contains("Файл книги"))
        assertTrue(message(large, TextCatalogOperation.CATALOG).contains("страницу"))
    }

    @Test fun partialSuccessIsPreservedDespiteWrappedIoFailure() {
        val partial = TextImportStorageException(TextImportStorageFailure.PUBLICATION, UnknownHostException("secret"))
        val result = message(IOException("wrapper", partial), TextCatalogOperation.IMPORT)
        assertTrue(result.contains("уже добавлена"))
        assertTrue(result.contains("оригинал не сохранён"))
        assertFalse(result.contains("подключиться"))
        assertTrue(message(TextImportStorageException(TextImportStorageFailure.SOURCE_LINK)).contains("уже добавлена"))
    }

    @Test fun permissionAndLocalLibraryErrorsDoNotBlameTheWebsite() {
        assertTrue(message(TextImportStorageException(TextImportStorageFailure.PERMISSION)).contains("Разрешите"))
        assertEquals("Не удалось проверить книгу в библиотеке. Повторите открытие книги.",
            message(IOException("database path"), TextCatalogOperation.LIBRARY))
    }

    @Test fun unknownImportFailureOffersStorageAndFormatRecovery() {
        val result = message(IllegalArgumentException("sensitive parser data"), TextCatalogOperation.IMPORT)
        assertTrue(result.contains("свободное место"))
        assertTrue(result.contains("другой формат"))
    }

    @Test fun arbitraryExceptionTextAndFakeHttpCodesNeverBecomeUiCopy() {
        val result = message(IllegalStateException("HTTP 403 https://secret.test/?token=secret"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("HTTP"))
        assertFalse(result.contains("ограничил доступ"))
        assertTrue(result.contains("диагностики"))
    }

    @Test fun directAndWrappedCancellationAreRethrownUnchanged() {
        val cancelled = CancellationException("cancel")
        for (error in listOf(cancelled, IOException("wrapper", cancelled))) {
            try { message(error); fail("Cancellation became a user error") }
            catch (actual: CancellationException) { assertSame(cancelled, actual) }
        }
    }

    @Test fun cyclicCausesDoNotLoopForever() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        first.initCause(second)
        second.initCause(first)
        assertTrue(message(first).contains("диагностики"))
    }

    @Test fun pagingPresentationKeepsTheOriginalFailureForDiagnostics() {
        val original = UnknownHostException("secret")
        val failure = TextCatalogLoadException(original)
        assertSame(original, failure.cause)
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertTrue(failure.message.orEmpty().contains("интернет-соединение"))
    }
}
