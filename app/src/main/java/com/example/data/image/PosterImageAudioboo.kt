package com.example.data.image

import okhttp3.Request

private const val AUDIOBOO_COVER_ORIGIN = "https://audioboo.org/"
private const val AUDIOBOO_IMAGE_ACCEPT =
    "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
private const val AUDIOBOO_ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en;q=0.7"

internal fun isAudiobooCoverHost(host: String): Boolean =
    host.equals("audioboo.org", ignoreCase = true) ||
        host.endsWith(".audioboo.org", ignoreCase = true)

internal fun applyAudiobooCoverHeaders(
    request: Request,
    userAgent: String,
    cookieHeader: String,
): Request {
    if (!isAudiobooCoverHost(request.url.host)) return request

    val builder = request.newBuilder()
        .header("Accept", AUDIOBOO_IMAGE_ACCEPT)
        .header("Accept-Language", AUDIOBOO_ACCEPT_LANGUAGE)
        .header("Referer", AUDIOBOO_COVER_ORIGIN)
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", "same-origin")
        .removeHeader("Upgrade-Insecure-Requests")
        .removeHeader("Sec-Fetch-User")

    userAgent.trim()
        .takeIf(String::isNotBlank)
        ?.let { builder.header("User-Agent", it) }

    cookieHeader.trim()
        .takeIf(String::isNotBlank)
        ?.let { builder.header("Cookie", it) }
        ?: builder.removeHeader("Cookie")

    return builder.build()
}
