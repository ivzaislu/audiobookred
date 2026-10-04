package com.example.update

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class UpdateStorageException : IOException("Not enough storage for update")
internal class UpdateIncompleteDownloadException(
    val downloadedBytes: Long,
    val expectedBytes: Long,
) : IOException("Update APK download ended early: $downloadedBytes/$expectedBytes bytes")
internal class UpdateResumeRetryException : IOException("Update resume was rejected; retry full download")

internal fun updateDownloadPercent(downloadedBytes: Long, totalBytes: Long): Int =
    if (totalBytes <= 0L) 0
    else ((downloadedBytes.coerceAtLeast(0L) * 100L) / totalBytes)
        .toInt()
        .coerceIn(0, 100)

internal enum class UpdateDownloadResponseAction {
    WRITE_FULL,
    APPEND,
    RETRY_FULL,
    REJECT,
}

internal fun updateDownloadResponseAction(
    statusCode: Int,
    contentRange: String?,
    requestedOffset: Long,
    expectedSizeBytes: Long,
): UpdateDownloadResponseAction {
    if (requestedOffset > 0L) {
        if (statusCode == 200) return UpdateDownloadResponseAction.WRITE_FULL
        if (statusCode == 416) return UpdateDownloadResponseAction.RETRY_FULL
        if (statusCode != 206) return UpdateDownloadResponseAction.REJECT
        return if (
            isValidUpdateContentRange(
                raw = contentRange,
                requestedOffset = requestedOffset,
                expectedSizeBytes = expectedSizeBytes,
            )
        ) {
            UpdateDownloadResponseAction.APPEND
        } else {
            UpdateDownloadResponseAction.RETRY_FULL
        }
    }

    if (statusCode == 200) return UpdateDownloadResponseAction.WRITE_FULL
    if (
        statusCode == 206 &&
        isValidUpdateContentRange(
            raw = contentRange,
            requestedOffset = 0L,
            expectedSizeBytes = expectedSizeBytes,
        )
    ) {
        return UpdateDownloadResponseAction.WRITE_FULL
    }
    return UpdateDownloadResponseAction.REJECT
}

internal fun isValidUpdateContentRange(
    raw: String?,
    requestedOffset: Long,
    expectedSizeBytes: Long,
): Boolean {
    val match = Regex(
        pattern = """bytes\s+(\d+)-(\d+)/(\d+)""",
        option = RegexOption.IGNORE_CASE,
    ).matchEntire(raw?.trim().orEmpty()) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val total = match.groupValues[3].toLongOrNull() ?: return false
    return start == requestedOffset &&
        total == expectedSizeBytes &&
        end >= start &&
        end < total
}

internal fun copyAndVerifyUpdateApk(
    input: InputStream,
    output: OutputStream,
    expectedSizeBytes: Long,
    expectedSha256: String,
    maxSizeBytes: Long,
    initialBytes: Long = 0L,
    digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
    onBytesDownloaded: (Long) -> Unit = {},
): Long {
    var written = initialBytes
    var lastReportedPercent = -1
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        written += read
        if (written > expectedSizeBytes || written > maxSizeBytes) {
            throw UpdateApkVerificationException("Update APK is larger than declared")
        }
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        val percent = updateDownloadPercent(written, expectedSizeBytes)
        if (percent != lastReportedPercent) {
            lastReportedPercent = percent
            onBytesDownloaded(written)
        }
    }

    if (written < expectedSizeBytes) {
        throw UpdateIncompleteDownloadException(
            downloadedBytes = written,
            expectedBytes = expectedSizeBytes,
        )
    }

    val actualSha = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
        throw UpdateApkVerificationException("Update APK checksum mismatch")
    }
    return written
}

/**
 * Owns the resumable APK byte transport and byte-level integrity checks.
 * Package identity/signing validation and PackageInstaller remain outside.
 */
internal class UpdateApkDownloader(
    private val maxApkBytes: Long,
    private val storageHeadroomBytes: Long,
) {
    suspend fun downloadVerifiedApk(
        context: Context,
        info: AndroidUpdateInfo,
        onBytesDownloaded: (Long) -> Unit = {},
    ): File {
        val dir = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val file = File(
            dir,
            "update-${info.versionCode}-${info.sha256.take(12)}.apk.part",
        )
        dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith("update-${info.versionCode}") &&
                    it.name.endsWith(".apk.part") &&
                    it != file
            }
            ?.forEach { it.delete() }

        var requestedOffset = resumableUpdateBytes(file, info.sizeBytes)
        if (requestedOffset == info.sizeBytes) {
            if (updateFileMatches(file, info.sizeBytes, info.sha256)) {
                runCatching { onBytesDownloaded(requestedOffset) }
                return file
            }
            file.delete()
            requestedOffset = 0L
        }

        var retriedWithoutRange = false
        val client = PublicUpdateClient()

        while (true) {
            currentCoroutineContext().ensureActive()
            val remainingBytes = (info.sizeBytes - requestedOffset).coerceAtLeast(0L)
            ensureUpdateStorageAvailable(
                dir,
                remainingBytes + info.sizeBytes + storageHeadroomBytes,
            )
            if (requestedOffset > 0L) {
                runCatching { onBytesDownloaded(requestedOffset) }
            }

            var written = requestedOffset
            try {
                client.consumeDownload(
                    downloadUrl = info.downloadUrl,
                    resumeFromBytes = requestedOffset,
                ) { response ->
                    when (
                        updateDownloadResponseAction(
                            statusCode = response.code,
                            contentRange = response.header("Content-Range"),
                            requestedOffset = requestedOffset,
                            expectedSizeBytes = info.sizeBytes,
                        )
                    ) {
                        UpdateDownloadResponseAction.RETRY_FULL ->
                            throw UpdateResumeRetryException()
                        UpdateDownloadResponseAction.REJECT ->
                            throw IOException("Update APK download failed: HTTP ${response.code}")
                        UpdateDownloadResponseAction.WRITE_FULL -> {
                            requestedOffset = 0L
                            written = 0L
                        }
                        UpdateDownloadResponseAction.APPEND -> Unit
                    }

                    val body = response.body
                        ?: throw IOException("Update server returned an empty APK")
                    val append = requestedOffset > 0L
                    val digest = MessageDigest.getInstance("SHA-256")
                    if (append) {
                        updateDigestFromFile(file, digest)
                    }
                    body.byteStream().use { input ->
                        FileOutputStream(file, append).use { output ->
                            written = copyAndVerifyUpdateApk(
                                input = input,
                                output = output,
                                expectedSizeBytes = info.sizeBytes,
                                expectedSha256 = info.sha256,
                                maxSizeBytes = maxApkBytes,
                                initialBytes = requestedOffset,
                                digest = digest,
                                onBytesDownloaded = { bytes ->
                                    written = bytes
                                    runCatching { onBytesDownloaded(bytes) }
                                },
                            )
                            output.fd.sync()
                        }
                    }
                }
                return file
            } catch (cancelled: CancellationException) {
                if (file.length() > info.sizeBytes) file.delete()
                throw cancelled
            } catch (retry: UpdateResumeRetryException) {
                if (retriedWithoutRange) {
                    file.delete()
                    throw IOException("Update server repeatedly rejected download resume", retry)
                }
                file.delete()
                requestedOffset = 0L
                retriedWithoutRange = true
            } catch (error: IOException) {
                if (error is UpdateApkVerificationException) {
                    file.delete()
                    throw error
                }
                val persistedBytes = resumableUpdateBytes(file, info.sizeBytes)
                val stillNeeded = (info.sizeBytes - persistedBytes).coerceAtLeast(0L)
                if (
                    availableUpdateStorageBytes(dir) <
                    stillNeeded + info.sizeBytes + storageHeadroomBytes
                ) {
                    file.delete()
                    throw UpdateStorageException()
                }
                if (file.length() > info.sizeBytes) file.delete()
                throw error
            }
        }
    }
}

internal fun resumableUpdateBytes(file: File, expectedSizeBytes: Long): Long {
    if (!file.isFile) return 0L
    val length = file.length()
    if (length !in 1..expectedSizeBytes) {
        file.delete()
        return 0L
    }
    return length
}

internal fun updateFileMatches(
    file: File,
    expectedSizeBytes: Long,
    expectedSha256: String,
): Boolean {
    if (!file.isFile || file.length() != expectedSizeBytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    updateDigestFromFile(file, digest)
    val actualSha = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    return actualSha.equals(expectedSha256, ignoreCase = true)
}

private fun updateDigestFromFile(file: File, digest: MessageDigest) {
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
        }
    }
}

internal fun availableUpdateStorageBytes(directory: File): Long =
    runCatching { StatFs(directory.absolutePath).availableBytes }
        .getOrElse { directory.usableSpace }

internal fun ensureUpdateStorageAvailable(directory: File, requiredBytes: Long) {
    val availableBytes = availableUpdateStorageBytes(directory)
    if (availableBytes in 0 until requiredBytes) {
        throw UpdateStorageException()
    }
}
