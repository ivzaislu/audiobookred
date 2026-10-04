package com.example.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.Serializable
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class AndroidUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val mandatory: Boolean,
    val releaseNotes: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
) : Serializable

internal data class AndroidUpdateEnvelope(
    val schema: String = "",
    @Json(name = "application_id") val applicationId: String = "",
    @Json(name = "version_name") val versionName: String = "",
    @Json(name = "version_code") val versionCode: Int = -1,
    val mandatory: Boolean = false,
    @Json(name = "release_notes") val releaseNotes: String = "",
    @Json(name = "size_bytes") val sizeBytes: Long = -1,
    val sha256: String = "",
    @Json(name = "download_url") val downloadUrl: String = "",
)

internal enum class InstallStartResult {
    STARTED,
    NEEDS_SOURCE_PERMISSION,
    TIMEOUT,
    NETWORK_ERROR,
    NOT_ENOUGH_SPACE,
    APK_VERIFICATION_FAILED,
    DOWNLOAD_ERROR,
    FAILED,
}


internal fun classifyInstallFailure(error: Throwable): InstallStartResult = when (error) {
    is UpdateStorageException -> InstallStartResult.NOT_ENOUGH_SPACE
    is UpdateApkVerificationException -> InstallStartResult.APK_VERIFICATION_FAILED
    is SocketTimeoutException,
    is InterruptedIOException -> InstallStartResult.TIMEOUT
    is UnknownHostException,
    is ConnectException,
    is NoRouteToHostException,
    is SocketException -> InstallStartResult.NETWORK_ERROR
    is IOException -> InstallStartResult.DOWNLOAD_ERROR
    else -> InstallStartResult.FAILED
}

internal fun updateInstallFailureMessage(result: InstallStartResult): String = when (result) {
    InstallStartResult.TIMEOUT ->
        "Сервер обновлений слишком долго не отвечает. Проверьте интернет и попробуйте ещё раз."
    InstallStartResult.NETWORK_ERROR ->
        "Соединение потеряно. Проверьте Wi-Fi или мобильный интернет и повторите загрузку."
    InstallStartResult.NOT_ENOUGH_SPACE ->
        "Недостаточно свободного места для обновления. Освободите место на устройстве и попробуйте снова."
    InstallStartResult.APK_VERIFICATION_FAILED ->
        "APK скачан, но не прошёл проверку целостности, версии или подписи. Установка отменена."
    InstallStartResult.DOWNLOAD_ERROR ->
        "Не удалось скачать APK с сервера обновлений. Попробуйте ещё раз позже."
    else ->
        "Не удалось подготовить обновление к установке. Попробуйте ещё раз."
}

internal enum class UpdateInstallStage {
    DOWNLOADING,
    VERIFYING,
    PREPARING_INSTALL,
}

internal data class UpdateInstallProgress(
    val stage: UpdateInstallStage,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

internal enum class InstallStatusUiAction {
    REQUEST_USER_ACTION,
    OPEN_MAIN_ACTIVITY,
    FINISH_SILENTLY,
}

internal fun installStatusUiAction(status: Int): InstallStatusUiAction = when (status) {
    PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallStatusUiAction.REQUEST_USER_ACTION
    PackageInstaller.STATUS_SUCCESS -> InstallStatusUiAction.OPEN_MAIN_ACTIVITY
    else -> InstallStatusUiAction.FINISH_SILENTLY
}

internal object AndroidUpdateManager {
    val ACTION_INSTALL_STATUS: String = "${BuildConfig.APPLICATION_ID}.UPDATE_INSTALL_STATUS"
    internal const val UPDATE_SCHEMA = "abred.android.update/v1"
    internal const val MAX_RELEASE_NOTES_CHARS = 1_200
    internal const val MAX_UPDATE_APK_BYTES = 500L * 1024L * 1024L
    private const val UPDATE_STORAGE_HEADROOM_BYTES = 16L * 1024L * 1024L

    private val updateAdapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(AndroidUpdateEnvelope::class.java)

    private val apkDownloader = UpdateApkDownloader(
        maxApkBytes = MAX_UPDATE_APK_BYTES,
        storageHeadroomBytes = UPDATE_STORAGE_HEADROOM_BYTES,
    )
    private val apkVerifier = UpdateApkVerifier()
    private val packageInstallerGateway = PackageInstallerGateway(
        actionInstallStatus = ACTION_INSTALL_STATUS,
        storageHeadroomBytes = UPDATE_STORAGE_HEADROOM_BYTES,
    )

    fun isUpdateConfigured(): Boolean =
        PublicUpdateClient.normalizedManifestUrl(BuildConfig.UPDATE_MANIFEST_URL) != null

    suspend fun checkForUpdate(): AndroidUpdateInfo? =
        withContext(Dispatchers.IO) {
            if (!isUpdateConfigured()) return@withContext null
            val client = PublicUpdateClient()
            val raw = client.fetchManifest()
            val info = parseUpdate(raw) ?: return@withContext null
            if (!client.acceptsDownloadUrl(info.downloadUrl)) return@withContext null
            info
        }

    fun prompt(activity: Activity, info: AndroidUpdateInfo, onInstallRequested: (AndroidUpdateInfo) -> Unit) {
        val message = buildString {
            append("Доступна версия ${info.versionName}.")
            if (info.releaseNotes.isNotBlank()) {
                append("\n\n")
                append(info.releaseNotes.take(MAX_RELEASE_NOTES_CHARS))
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("Обновление AudioBookRed")
            .setMessage(message)
            .setCancelable(isUpdatePromptCancelable(info.mandatory))
            .setPositiveButton("Обновить") { _, _ -> onInstallRequested(info) }
        if (!info.mandatory) builder.setNegativeButton("Позже", null)
        builder.show()
    }

    suspend fun startInstall(
        activity: Activity,
        info: AndroidUpdateInfo,
        onProgress: (UpdateInstallProgress) -> Unit = {},
    ): InstallStartResult = withContext(Dispatchers.IO) {
        if (!isUpdateConfigured() || info.versionCode <= BuildConfig.VERSION_CODE) {
            return@withContext InstallStartResult.FAILED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            return@withContext InstallStartResult.NEEDS_SOURCE_PERMISSION
        }

        fun report(progress: UpdateInstallProgress) {
            runCatching { onProgress(progress) }
        }

        var apk: File? = null
        try {
            report(
                UpdateInstallProgress(
                    stage = UpdateInstallStage.DOWNLOADING,
                    downloadedBytes = 0L,
                    totalBytes = info.sizeBytes,
                )
            )
            apk = apkDownloader.downloadVerifiedApk(activity, info) { downloadedBytes ->
                report(
                    UpdateInstallProgress(
                        stage = UpdateInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = info.sizeBytes,
                    )
                )
            }
            currentCoroutineContext().ensureActive()
            report(
                UpdateInstallProgress(
                    stage = UpdateInstallStage.VERIFYING,
                    downloadedBytes = info.sizeBytes,
                    totalBytes = info.sizeBytes,
                )
            )
            apkVerifier.verifyIdentity(activity, apk, info.versionCode)
            currentCoroutineContext().ensureActive()
            report(
                UpdateInstallProgress(
                    stage = UpdateInstallStage.PREPARING_INSTALL,
                    downloadedBytes = info.sizeBytes,
                    totalBytes = info.sizeBytes,
                )
            )
            currentCoroutineContext().ensureActive()
            packageInstallerGateway.commit(activity, apk)
            InstallStartResult.STARTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            classifyInstallFailure(error)
        } finally {
            apk?.delete()
        }
    }

    internal fun installStatusMessage(status: Int, systemDetail: String?): String? {
        if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_PENDING_USER_ACTION) return null
        val prefix = when (status) {
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Установка обновления заблокирована"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка обновления отменена"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт при установке обновления"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Обновление несовместимо с устройством"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Пакет обновления недействителен"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостаточно места для установки обновления"
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "Истекло время ожидания установки обновления"
            PackageInstaller.STATUS_FAILURE -> "Установка обновления не удалась"
            else -> "Установка обновления не удалась (status=$status)"
        }
        val detail = systemDetail?.trim()?.takeIf { it.isNotEmpty() }
        return if (detail == null) prefix else "$prefix: $detail"
    }

    internal fun isUpdatePromptCancelable(mandatory: Boolean): Boolean = !mandatory

    internal fun isUpdateDownloadCancelable(mandatory: Boolean): Boolean = !mandatory

    internal fun updateFailureActionLabel(mandatory: Boolean): String =
        if (mandatory) "Повторить" else "Закрыть"

    internal fun parseUpdate(json: String): AndroidUpdateInfo? {
        val root = try { updateAdapter.fromJson(json) } catch (_: Exception) { null } ?: return null
        if (root.schema != UPDATE_SCHEMA) return null
        if (root.applicationId != BuildConfig.APPLICATION_ID) return null
        val sha = root.sha256.lowercase()
        if (
            root.versionName.isBlank() ||
            root.versionCode <= BuildConfig.VERSION_CODE ||
            root.sizeBytes !in 1..MAX_UPDATE_APK_BYTES
        ) return null
        if (!sha.matches(Regex("[0-9a-f]{64}")) || root.downloadUrl.isBlank()) return null
        return AndroidUpdateInfo(
            root.versionName,
            root.versionCode,
            root.mandatory,
            root.releaseNotes.take(MAX_RELEASE_NOTES_CHARS),
            root.sizeBytes,
            sha,
            root.downloadUrl,
        )
    }
}
