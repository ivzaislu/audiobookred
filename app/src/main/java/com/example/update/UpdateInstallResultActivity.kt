package com.example.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible PackageInstaller status router.
 *
 * PackageInstaller delivers pending-user-action, success and failure states to
 * the same IntentSender. Keep those callbacks away from MainActivity so a
 * canceled or failed update can never surface the app UI on its own.
 */
class UpdateInstallResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStatus(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStatus(intent)
    }

    private fun handleStatus(statusIntent: Intent?) {
        if (statusIntent?.action != AndroidUpdateManager.ACTION_INSTALL_STATUS) {
            finish()
            return
        }

        val status = statusIntent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (installStatusUiAction(status)) {
            InstallStatusUiAction.REQUEST_USER_ACTION -> {
                val confirmation = statusIntent.pendingInstallConfirmation()
                if (confirmation != null) {
                    startActivity(confirmation)
                } else {
                    showFailure("Android не вернул окно подтверждения установки.")
                }
                finish()
            }

            InstallStatusUiAction.OPEN_MAIN_ACTIVITY -> {
                if (!relaunchUpdatedApp(this)) {
                    showFailure(
                        "Обновление установлено, но Android не разрешил автоматически перезапустить приложение."
                    )
                }
                finish()
            }

            InstallStatusUiAction.FINISH_SILENTLY -> {
                AndroidUpdateManager.installStatusMessage(
                    status,
                    statusIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )?.let(::showFailure)
                finish()
            }
        }
    }

    private fun showFailure(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun Intent.pendingInstallConfirmation(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
