package com.example.data.image

import com.example.data.api.ApiClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val FASTPIC_SIGNED_EXPIRY_SKEW_SECONDS = 60L

internal fun fastPicCanonicalImageUrl(raw: String): String? {
    val url = ApiClient.coverImageUrl(raw)?.toHttpUrlOrNull() ?: return null
    val hostMatch = Regex("""^i(\d+)\.fastpic\.org$""", RegexOption.IGNORE_CASE)
        .matchEntire(url.host)
        ?: return null
    val segments = url.pathSegments
    if (segments.size != 5) return null
    if (segments[0] != "big" && segments[0] != "thumb") return null
    if (!segments[1].matches(Regex("""\d{4}"""))) return null
    if (!segments[2].matches(Regex("""\d{4}"""))) return null
    if (!segments[3].matches(Regex("""[0-9a-fA-F]{2}"""))) return null
    if (segments[4].isBlank()) return null

    return url.newBuilder()
        .scheme("https")
        .host("i" + hostMatch.groupValues[1] + ".fastpic.org")
        .query(null)
        .fragment(null)
        .build()
        .toString()
}

internal fun fastPicViewerUrls(raw: String): List<String> {
    val source = fastPicCanonicalImageUrl(raw)?.toHttpUrlOrNull() ?: return emptyList()
    val shard = Regex("""^i(\d+)\.fastpic\.org$""", RegexOption.IGNORE_CASE)
        .matchEntire(source.host)
        ?.groupValues
        ?.getOrNull(1)
        ?: return emptyList()
    val segments = source.pathSegments
    val tail = shard + "/" + segments[1] + "/" + segments[2] + "/" + segments[4]
    return listOf(
        "https://fastpic.org/fullview/" + tail,
        "https://fastpic.org/view/" + tail + ".html",
    )
}

internal fun fastPicFullviewUrl(raw: String): String? =
    fastPicViewerUrls(raw).firstOrNull()

internal fun fastPicSignedUrlFromHtml(
    sourceUrl: String,
    html: String,
): String? {
    val source = fastPicCanonicalImageUrl(sourceUrl)?.toHttpUrlOrNull() ?: return null
    val candidates = buildList {
        FASTPIC_LOADING_IMG_REGEX.findAll(html).forEach { match ->
            match.groupValues.getOrNull(1)?.let(::add)
        }
        FASTPIC_IMAGE_SRC_REGEX.findAll(html).forEach { match ->
            match.groupValues.getOrNull(1)?.let(::add)
        }
    }

    for (raw in candidates) {
        val normalized = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .let { value -> if (value.startsWith("//")) "https:" + value else value }
        val candidate = normalized.toHttpUrlOrNull() ?: continue
        if (candidate.scheme != "https" || candidate.port != 443) continue
        if (candidate.username.isNotBlank() || candidate.password.isNotBlank()) continue
        if (candidate.fragment != null) continue
        if (!candidate.host.equals(source.host, ignoreCase = true)) continue
        if (candidate.encodedPath != source.encodedPath) continue
        val expiry = fastPicSignedExpirySeconds(candidate.toString()) ?: continue
        if (expiry <= currentEpochSeconds() + FASTPIC_SIGNED_EXPIRY_SKEW_SECONDS) continue
        return candidate.toString()
    }
    return null
}

internal fun fastPicSignedExpirySeconds(raw: String): Long? {
    val url = raw.toHttpUrlOrNull() ?: return null
    val md5 = url.queryParameter("md5").orEmpty()
    val expires = url.queryParameter("expires")?.toLongOrNull() ?: return null
    return expires.takeIf { md5.isNotBlank() }
}

internal fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1000L

private val FASTPIC_LOADING_IMG_REGEX = Regex(
    """loading_img\s*=\s*['"]([^'"]+)['"]""",
    RegexOption.IGNORE_CASE,
)

private val FASTPIC_IMAGE_SRC_REGEX = Regex(
    """<img\b[^>]*\bclass=['"][^'"]*\bimage\b[^'"]*['"][^>]*\bsrc=['"]([^'"]+)['"]""",
    RegexOption.IGNORE_CASE,
)
