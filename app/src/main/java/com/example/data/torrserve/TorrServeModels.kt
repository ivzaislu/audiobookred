package com.example.data.torrserve

import com.squareup.moshi.Json
import java.io.IOException

sealed class TorrServeException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class TorrServeConfigurationException(message: String) : TorrServeException(message)

class TorrServeAuthorizationException(message: String) : TorrServeException(message)

class TorrServeAccessDeniedException(message: String) : TorrServeException(message)

class TorrServeConnectionException(
    message: String,
    cause: Throwable? = null,
) : TorrServeException(message, cause)

class TorrServeHttpException(
    val statusCode: Int,
    message: String,
) : TorrServeException(message)

data class TorrServeConnectionInfo(
    val serverUrl: String,
    val version: String,
)

data class TorrServeTorrentFile(
    val id: Int,
    val path: String,
    val length: Long = 0L,
)

enum class TorrServePreparationStage {
    CONNECTING,
    WAITING_FOR_METADATA,
    BUFFERING,
    READY_TO_PLAY,
}

data class TorrServeTorrentStatus(
    val title: String = "",
    val name: String = "",
    val hash: String = "",
    @Json(name = "stat_string") val statString: String = "",
    @Json(name = "torrent_size") val torrentSize: Long = 0L,
    @Json(name = "file_stats") val fileStats: List<TorrServeTorrentFile>? = null,
)
