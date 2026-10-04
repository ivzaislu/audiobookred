package com.example

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.data.image.PosterImageCache
import com.example.data.parser.AndroidLiveParserLocator
import com.example.ui.AbredApp
import com.example.update.AndroidUpdateInfo
import com.example.update.AndroidUpdateManager
import com.example.update.InstallStartResult
import com.example.update.UpdateInstallProgress
import com.example.update.UpdateInstallStage
import com.example.update.updateDownloadPercent
import com.example.update.updateInstallFailureMessage
import com.example.util.runCatchingCancellable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingUpdate: AndroidUpdateInfo? = null
    private var updateCheckJob: Job? = null
    private var deferredStartupJob: Job? = null

    private val unknownSourcesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val update = pendingUpdate ?: return@registerForActivityResult
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            pendingUpdate = null
            requestUpdateInstall(update)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUpdate = savedInstanceState?.pendingUpdate()
        enableEdgeToEdge()
        setContent { AbredApp() }

        // A pending update means the Activity may have been recreated while the
        // system unknown-sources screen was open. Do not show a duplicate prompt;
        // the ActivityResult callback will continue the same install request.
        if (pendingUpdate != null) return

        scheduleDeferredStartupWork()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingUpdate?.let { outState.putSerializable(STATE_PENDING_UPDATE, it) }
        super.onSaveInstanceState(outState)
    }

    internal fun checkForUpdatesManually() {
        deferredStartupJob?.cancel()
        deferredStartupJob = null
        checkForUpdates(manual = true)
    }

    private fun scheduleDeferredStartupWork() {
        deferredStartupJob?.cancel()
        deferredStartupJob = lifecycleScope.launch {
            // None of this is needed to draw the first screen. Device traces show
            // that starting these tasks immediately competes with Compose/Media3
            // startup and Coil's first burst of visible poster requests.
            delay(AUTO_STARTUP_DEFER_DELAY_MS)
            if (pendingUpdate != null || isFinishing || isDestroyed) return@launch

            // Coil initializes DiskLruCache lazily. Warm it once on IO so several
            // visible covers do not all contend on the same initialization lock.
            // The parser locator also constructs its provider hub lazily;
            // warming it here keeps that constructor work off the main thread.
            launch(Dispatchers.IO) {
                runCatching { PosterImageCache.warmUp(applicationContext) }
                runCatching { AndroidLiveParserLocator.instanceOrNull() }
            }

            // Let the real manifest request decide whether connectivity is ready.
            // A pre-flight NET_CAPABILITY_VALIDATED check can be false for a few
            // seconds after launch even though raw.githubusercontent.com is ready.
            checkForUpdates(manual = false)
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) {
            // A manual action should take precedence over a silent startup check.
            updateCheckJob?.cancel()
        } else if (updateCheckJob?.isActive == true) {
            return
        }

        updateCheckJob = lifecycleScope.launch {
            if (manual) showToast("Проверяем обновления…", Toast.LENGTH_SHORT)

            var result = runCatchingCancellable {
                AndroidUpdateManager.checkForUpdate()
            }

            // Startup remains best-effort, but one delayed retry covers the common
            // case where Android/network routing is not fully ready right after launch.
            if (!manual && result.isFailure) {
                delay(AUTO_UPDATE_RETRY_DELAY_MS)
                if (pendingUpdate != null || isFinishing || isDestroyed) return@launch
                result = runCatchingCancellable {
                    AndroidUpdateManager.checkForUpdate()
                }
            }

            if (result.isFailure) {
                if (manual && !isFinishing && !isDestroyed) {
                    showToast("Не удалось проверить обновления. Проверьте интернет и попробуйте ещё раз.")
                }
                return@launch
            }

            val update = result.getOrNull()
            if (update == null) {
                if (manual && !isFinishing && !isDestroyed) {
                    showToast("Установлена последняя версия AudioBookRed", Toast.LENGTH_SHORT)
                }
                return@launch
            }

            if (!isFinishing && !isDestroyed) {
                AndroidUpdateManager.prompt(this@MainActivity, update, ::requestUpdateInstall)
            }
        }
    }

    private fun requestUpdateInstall(update: AndroidUpdateInfo) {
        lateinit var progressUi: UpdateProgressUi
        val installJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = AndroidUpdateManager.startInstall(this@MainActivity, update) { progress ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            renderUpdateProgress(progressUi, progress)
                        }
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                when (result) {
                    InstallStartResult.STARTED -> {
                        pendingUpdate = null
                        progressUi.dialog.dismiss()
                    }
                    InstallStartResult.NEEDS_SOURCE_PERMISSION -> {
                        progressUi.dialog.dismiss()
                        pendingUpdate = update
                        unknownSourcesLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName"),
                            )
                        )
                    }
                    else -> {
                        pendingUpdate = null
                        renderUpdateFailure(
                            ui = progressUi,
                            result = result,
                            update = update,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!isFinishing && !isDestroyed) {
                    progressUi.dialog.dismiss()
                    showToast("Загрузка обновления отменена.", Toast.LENGTH_SHORT)
                }
                throw cancelled
            }
        }
        progressUi = showUpdateProgress(
            canCancel = AndroidUpdateManager.isUpdateDownloadCancelable(update.mandatory),
            onCancel = {
                installJob.cancel(CancellationException("Update download canceled by user"))
            },
        )
        installJob.start()
    }

    private data class UpdateProgressUi(
        val dialog: AlertDialog,
        val progressBar: ProgressBar,
        val message: TextView,
    )

    private fun showUpdateProgress(
        canCancel: Boolean,
        onCancel: () -> Unit,
    ): UpdateProgressUi {
        val density = resources.displayMetrics.density
        val horizontalPadding = (24f * density).toInt()
        val verticalPadding = (12f * density).toInt()
        val gap = (16f * density).toInt()

        val message = TextView(this).apply {
            text = "Начинаем загрузку…"
            textSize = 16f
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val progressParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = gap
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(
                message,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
            addView(progressBar, progressParams)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Обновление AudioBookRed")
            .setView(content)
            // Always create the button so a mandatory update can expose it as
            // "Повторить" after a failed attempt without allowing cancellation
            // while the download itself is active.
            .setNegativeButton("Отмена", null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            val actionButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            if (canCancel) {
                actionButton.visibility = android.view.View.VISIBLE
                actionButton.setOnClickListener {
                    actionButton.isEnabled = false
                    message.text = "Отменяем загрузку…"
                    progressBar.isIndeterminate = true
                    onCancel()
                }
            } else {
                actionButton.visibility = android.view.View.GONE
            }
        }
        dialog.show()
        return UpdateProgressUi(dialog, progressBar, message)
    }

    private fun renderUpdateProgress(ui: UpdateProgressUi, progress: UpdateInstallProgress) {
        when (progress.stage) {
            UpdateInstallStage.DOWNLOADING -> {
                val total = progress.totalBytes
                val downloaded = progress.downloadedBytes.coerceAtLeast(0L)
                if (total > 0L) {
                    val percent = updateDownloadPercent(downloaded, total)
                    ui.progressBar.isIndeterminate = false
                    ui.progressBar.progress = percent
                    ui.message.text =
                        "Скачивание: $percent% · ${formatMegabytes(downloaded)} / ${formatMegabytes(total)} МБ"
                } else {
                    ui.progressBar.isIndeterminate = true
                    ui.message.text = "Скачивание обновления…"
                }
            }
            UpdateInstallStage.VERIFYING -> {
                ui.progressBar.isIndeterminate = true
                ui.message.text = "Проверяем загруженный APK…"
            }
            UpdateInstallStage.PREPARING_INSTALL -> {
                ui.progressBar.isIndeterminate = true
                ui.message.text = "Подготавливаем установку…"
                ui.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
            }
        }
    }

    private fun renderUpdateFailure(
        ui: UpdateProgressUi,
        result: InstallStartResult,
        update: AndroidUpdateInfo,
    ) {
        ui.progressBar.isIndeterminate = false
        ui.progressBar.visibility = android.view.View.GONE
        ui.message.text = updateInstallFailureMessage(result)
        ui.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            visibility = android.view.View.VISIBLE
            text = AndroidUpdateManager.updateFailureActionLabel(update.mandatory)
            isEnabled = true
            setOnClickListener {
                ui.dialog.dismiss()
                if (update.mandatory) {
                    requestUpdateInstall(update)
                }
            }
        }
    }

    private fun formatMegabytes(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))

    private fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        Toast.makeText(this, message, duration).show()
    }

    @Suppress("DEPRECATION")
    private fun Bundle.pendingUpdate(): AndroidUpdateInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializable(STATE_PENDING_UPDATE, AndroidUpdateInfo::class.java)
        } else {
            getSerializable(STATE_PENDING_UPDATE) as? AndroidUpdateInfo
        }

    private companion object {
        const val STATE_PENDING_UPDATE = "pending_android_update"
        const val AUTO_STARTUP_DEFER_DELAY_MS = 2_000L
        const val AUTO_UPDATE_RETRY_DELAY_MS = 4_000L
    }
}
