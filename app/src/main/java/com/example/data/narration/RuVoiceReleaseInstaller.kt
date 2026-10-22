package com.example.data.narration

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.data.api.ApiClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class RuVoiceRelease(val version: String, val size: Long, val sha256: String, val url: String) {
    val filename: String get() = "ruvoice-tts-$version.apk"
}

internal object RuVoiceReleaseMetadata {
    const val PACKAGE_NAME = "ru.kost.ruvoice"
    const val API_URL = "https://api.github.com/repos/kost-t-human/ruvoice-tts/releases/latest"
    private val tagRegex = Regex("""v(\d+\.\d+\.\d+)""")
    private val shaRegex = Regex("[0-9a-fA-F]{64}")

    fun parse(json: String): RuVoiceRelease {
        val root = JSONObject(json)
        require(!root.optBoolean("draft") && !root.optBoolean("prerelease")) { "Нет стабильного релиза RuVoice." }
        val tag = root.getString("tag_name")
        val version = tagRegex.matchEntire(tag)?.groupValues?.get(1)
            ?: throw IOException("Неизвестная версия RuVoice.")
        val filename = "ruvoice-tts-$version.apk"
        val assets = root.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .singleOrNull { it.optString("name") == filename }
            ?: throw IOException("В релизе нет полной версии RuVoice.")
        val size = asset.getLong("size")
        require(size in 1..(400L * 1024L * 1024L)) { "Размер APK недопустим." }
        val digest = asset.getString("digest")
        require(digest.startsWith("sha256:")) { "Нет SHA-256 у APK." }
        val sha = digest.removePrefix("sha256:")
        require(shaRegex.matches(sha)) { "Некорректная SHA-256." }
        val expected = "https://github.com/kost-t-human/ruvoice-tts/releases/download/$tag/$filename"
        val url = asset.getString("browser_download_url")
        require(url == expected) { "APK опубликован не в официальном репозитории." }
        return RuVoiceRelease(version, size, sha.lowercase(), url)
    }

    fun valid(release: RuVoiceRelease): Boolean = runCatching {
        val data = JSONObject().put("tag_name", "v${release.version}")
            .put("assets", JSONArray().put(JSONObject().put("name", release.filename)
                .put("size", release.size).put("digest", "sha256:${release.sha256}")
                .put("browser_download_url", release.url)))
        parse(data.toString()) == release
    }.getOrDefault(false)
}

/**
 * An installer view being opened is not proof of installation. Require an
 * installed package of the exact release, plus evidence that the update
 * happened after this file/download or after the installation was requested.
 */
internal object RuVoiceApkCleanupPolicy {
    fun shouldDelete(
        releaseVersion: String,
        installedVersion: String?,
        installedAtMs: Long,
        apkModifiedAtMs: Long,
        installRequested: Boolean,
        installedBeforeRequestAtMs: Long,
    ): Boolean {
        if (installedVersion != releaseVersion || installedAtMs <= 0L ||
            apkModifiedAtMs <= 0L || installedAtMs < apkModifiedAtMs
        ) return false
        return if (installRequested) installedAtMs > installedBeforeRequestAtMs
        else true // Old ABRed versions did not record installation intent.
    }
}

internal enum class RuVoiceDownloadPhase { IDLE, DOWNLOADING, VERIFYING, FAILED }
internal data class RuVoiceDownloadState(
    val phase: RuVoiceDownloadPhase,
    val version: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String = "",
)

/** The OS owns the long-running transfer. Install still requires Android user approval. */
internal class RuVoiceReleaseInstaller(private val context: Context) {
    private val prefs = context.getSharedPreferences("ruvoice-installer-v1", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun autoInstallArmed(): Boolean = prefs.getBoolean("auto_install", false)

    fun pendingInstallationCleanup(): Boolean = prefs.getBoolean("install_requested", false)

    /** Drop the private downloaded APK only when Android confirms installation. */
    @Synchronized
    fun cleanupInstalledApkIfReady(): Boolean {
        val release = stored() ?: return false
        val apk = location(release)
        val installed = installedInfo() ?: return false
        val canDelete = RuVoiceApkCleanupPolicy.shouldDelete(
            releaseVersion = release.version,
            installedVersion = installed.versionName,
            installedAtMs = installed.lastUpdateTime,
            apkModifiedAtMs = apk.lastModified(),
            installRequested = prefs.getBoolean("install_requested", false),
            installedBeforeRequestAtMs = prefs.getLong("installed_before_update", 0L),
        )
        if (!canDelete) return false
        // DownloadManager owns the original download record; remove it as well
        // as our private file, so its successful download notification is cleared.
        val id = prefs.getLong("download_id", -1L)
        if (id >= 0L) runCatching { manager.remove(id) }
        if (apk.exists() && !apk.delete()) return false
        prefs.edit().clear().apply()
        return true
    }

    private fun location(release: RuVoiceRelease): File {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Нет доступа к загрузкам.")
        return File(downloads, "ruvoice/${release.filename}")
    }

    suspend fun latest(): RuVoiceRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(RuVoiceReleaseMetadata.API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AudioBookRed-RuVoice").header("Cache-Control", "no-cache").build()
        ApiClient.createHttpClient(readTimeoutSeconds = 30L).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("GitHub вернул пустой ответ.")
            require(body.length < 1_000_000) { "Ответ GitHub слишком большой." }
            RuVoiceReleaseMetadata.parse(body)
        }
    }

    private fun stored(): RuVoiceRelease? = runCatching {
        RuVoiceRelease(
            prefs.getString("version", "")!!, prefs.getLong("size", 0L),
            prefs.getString("sha", "")!!, prefs.getString("url", "")!!,
        ).takeIf(RuVoiceReleaseMetadata::valid)
    }.getOrNull()

    fun start(release: RuVoiceRelease, autoInstall: Boolean = true): Long {
        require(RuVoiceReleaseMetadata.valid(release)) { "Недопустимый APK." }
        val oldId = prefs.getLong("download_id", -1L)
        if (oldId >= 0 && stored() == release &&
            status().phase in setOf(RuVoiceDownloadPhase.DOWNLOADING, RuVoiceDownloadPhase.VERIFYING)) {
            if (autoInstall) prefs.edit().putBoolean("auto_install", true).apply()
            return oldId
        }
        if (oldId >= 0) manager.remove(oldId)
        val target = location(release)
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) throw IOException("Не удалось удалить прежний APK.")
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("RuVoice TTS ${release.version}")
            .setDescription("Полная офлайн-версия с голосами")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, "ruvoice/${release.filename}",
            )
        val id = manager.enqueue(request)
        prefs.edit().putLong("download_id", id).putString("version", release.version)
            .putLong("size", release.size).putString("sha", release.sha256)
            .putString("url", release.url)
            .putBoolean("auto_install", autoInstall)
            .putBoolean("install_requested", false)
            .remove("installed_before_update").apply()
        return id
    }

    fun status(): RuVoiceDownloadState {
        // Also clears old APKs left behind by previous ABRed versions,
        // if RuVoice was successfully installed after that file was downloaded.
        cleanupInstalledApkIfReady()
        val release = stored() ?: return RuVoiceDownloadState(RuVoiceDownloadPhase.IDLE)
        val id = prefs.getLong("download_id", -1L)
        if (id < 0) return RuVoiceDownloadState(RuVoiceDownloadPhase.IDLE)
        return manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use RuVoiceDownloadState(
                RuVoiceDownloadPhase.FAILED, release.version, message = "Загрузка не найдена.")
            fun field(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
            val loaded = field(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0L)
            when (field(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_SUCCESSFUL -> RuVoiceDownloadState(
                    RuVoiceDownloadPhase.VERIFYING, release.version, release.size, release.size)
                DownloadManager.STATUS_FAILED -> RuVoiceDownloadState(
                    RuVoiceDownloadPhase.FAILED, release.version, loaded, release.size,
                    "Не удалось загрузить APK. Проверьте сеть.")
                else -> RuVoiceDownloadState(
                    RuVoiceDownloadPhase.DOWNLOADING, release.version, loaded, release.size)
            }
        } ?: RuVoiceDownloadState(RuVoiceDownloadPhase.FAILED, message = "Недоступна служба загрузок.")
    }

    suspend fun verifiedApk(): File = withContext(Dispatchers.IO) {
        val release = stored() ?: throw IOException("Нет сведений о скачанном APK.")
        require(status().phase == RuVoiceDownloadPhase.VERIFYING) { "Загрузка не закончена." }
        val file = location(release)
        require(file.isFile && file.length() == release.size) { "Размер APK отличается от релиза." }
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                hash.update(bytes, 0, count)
            }
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it) }
        require(digest == release.sha256) { "Контрольная сумма APK не совпала." }
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageArchiveInfo(
            file.absolutePath, PackageManager.PackageInfoFlags.of(0L))
        else context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        require(info?.packageName == RuVoiceReleaseMetadata.PACKAGE_NAME &&
            info.versionName == release.version) { "Неверный пакет RuVoice." }
        file
    }

    fun installationIntent(file: File): Intent {
        val release = stored() ?: throw IOException("Нет сведений об APK.")
        require(file.canonicalFile == location(release).canonicalFile) { "Неверный путь APK." }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.diagnostics.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Record the installed state *before* Android opens its confirmation UI.
        // A cancelled or failed install must never delete the source APK.
        prefs.edit()
            .putBoolean("install_requested", true)
            .putBoolean("auto_install", false)
            .putLong("installed_before_update", installedInfo()?.lastUpdateTime ?: 0L)
            .apply()
        RuVoiceApkCleanupWorker.schedule(context)
        return intent
    }

    fun needsPermission(): Boolean = Build.VERSION.SDK_INT >= 26 &&
        !context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    private fun installedInfo(): android.content.pm.PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(
            RuVoiceReleaseMetadata.PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0L))
        else context.packageManager.getPackageInfo(RuVoiceReleaseMetadata.PACKAGE_NAME, 0)
    }.getOrNull()

    fun installedVersion(): String? = installedInfo()?.versionName
}
