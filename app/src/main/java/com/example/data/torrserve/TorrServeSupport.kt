package com.example.data.torrserve

import com.example.data.settings.ServiceCredentials
import java.util.concurrent.TimeUnit

internal fun buildTorrServeHttpClient(
    readTimeoutSeconds: Long,
): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
    .connectTimeout(15L, TimeUnit.SECONDS)
    .readTimeout(readTimeoutSeconds.coerceAtLeast(30L), TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal fun infoHashFromMagnet(magnet: String): String? {
    val match = Regex(
        """(?:[?&]xt=urn:btih:)([A-Za-z0-9]{32,40})(?:&|$)""",
        RegexOption.IGNORE_CASE,
    ).find(magnet) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
}

internal fun isTransientStreamStatus(statusCode: Int): Boolean =
    statusCode == 404 ||
        statusCode == 409 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode in 500..599

internal fun torrServeAuthorizationMessage(credentials: ServiceCredentials): String = when {
    credentials.login.isBlank() && credentials.password.isBlank() ->
        "TorrServe требует авторизацию. Укажите логин и пароль в настройках."
    credentials.login.isBlank() || credentials.password.isBlank() ->
        "Для TorrServe заполнены не все данные авторизации. Укажите и логин, и пароль."
    else ->
        "TorrServe отклонил авторизацию. Проверьте сохранённые логин и пароль."
}
