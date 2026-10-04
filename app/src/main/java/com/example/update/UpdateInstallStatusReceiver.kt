package com.example.update

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.MainActivity

class UpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // STATUS_SUCCESS is the primary relaunch path. Give it a short head
            // start so the package-replaced fallback cannot open AudioBookRed a
            // second time immediately after a successful installer callback.
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    try {
                        relaunchUpdatedApp(appContext)
                    } finally {
                        pendingResult.finish()
                    }
                },
                PACKAGE_REPLACED_RELAUNCH_DELAY_MS,
            )
            return
        }
        if (intent.action != AndroidUpdateManager.ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )

        when (installStatusUiAction(status)) {
            InstallStatusUiAction.REQUEST_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }

            InstallStatusUiAction.OPEN_MAIN_ACTIVITY -> {
                // Compatibility path for an update session created by an older APK,
                // where PackageInstaller still targets this receiver.
                relaunchUpdatedApp(context)
            }

            InstallStatusUiAction.FINISH_SILENTLY -> {
                AndroidUpdateManager.installStatusMessage(
                    status,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        }
    }

}

internal const val PACKAGE_REPLACED_RELAUNCH_DELAY_MS = 1_500L

internal fun shouldAutoRelaunchUpdatedApp(
    currentVersionCode: Long,
    lastRelaunchedVersionCode: Long,
): Boolean = currentVersionCode > 0L && currentVersionCode != lastRelaunchedVersionCode

private object UpdateRelaunchGuard {
    private const val PREFS_NAME = "update_relaunch_guard"
    private const val KEY_LAST_RELAUNCHED_VERSION = "last_relaunched_version"

    private val lock = Any()

    fun relaunchOncePerVersion(context: Context): Boolean = synchronized(lock) {
        val appContext = context.applicationContext
        val versionCode = installedVersionCode(appContext)
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastRelaunchedVersion = prefs.getLong(KEY_LAST_RELAUNCHED_VERSION, -1L)

        if (!shouldAutoRelaunchUpdatedApp(versionCode, lastRelaunchedVersion)) {
            return@synchronized false
        }

        val launched = startUpdatedAppTask(appContext)
        if (launched) {
            // commit() is deliberate: STATUS_SUCCESS and MY_PACKAGE_REPLACED can
            // race in the same process, and the guard must be durable before the
            // other callback is allowed to make its decision.
            prefs.edit()
                .putLong(KEY_LAST_RELAUNCHED_VERSION, versionCode)
                .commit()
        }
        launched
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        }.getOrDefault(-1L)

    private fun startUpdatedAppTask(context: Context): Boolean {
        val restartIntent = Intent.makeRestartActivityTask(
            ComponentName(context, MainActivity::class.java),
        )

        if (runCatching { context.startActivity(restartIntent) }.isSuccess) {
            return true
        }

        // OEM launchers occasionally customize task recreation. Fall back to the
        // package's launcher intent while still clearing any stale pre-update task.
        val launcherIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return false

        return runCatching {
            context.startActivity(launcherIntent)
        }.isSuccess
    }
}

internal fun relaunchUpdatedApp(context: Context): Boolean =
    UpdateRelaunchGuard.relaunchOncePerVersion(context)
