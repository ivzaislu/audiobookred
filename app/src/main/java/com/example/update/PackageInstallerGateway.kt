package com.example.update

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Android PackageInstaller boundary for the already verified update APK.
 *
 * The caller owns download and package/signing verification. This gateway keeps
 * the existing user-confirmation and callback-routing semantics unchanged.
 */
internal class PackageInstallerGateway(
    private val actionInstallStatus: String,
    private val storageHeadroomBytes: Long,
) {
    suspend fun commit(context: Context, apk: File) {
        ensureUpdateStorageAvailable(
            File(context.cacheDir, "app-updates"),
            apk.length() + storageHeadroomBytes,
        )
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Self-update is always a user-visible flow. Requiring confirmation
                // explicitly keeps PackageInstaller on the STATUS_PENDING_USER_ACTION
                // path instead of relying on platform/OEM defaults.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                        }
                        currentCoroutineContext().ensureActive()
                        session.fsync(output)
                    }
                }
                currentCoroutineContext().ensureActive()
                // PackageInstaller reports every state through this same
                // IntentSender, including pending confirmation, success, aborts and
                // failures. Route through an invisible activity so only STATUS_SUCCESS
                // is allowed to open MainActivity; failure/cancel paths just finish.
                val intent = Intent(context, UpdateInstallResultActivity::class.java)
                    .setAction(actionInstallStatus)
                    // A PendingIntent activity is launched by PackageInstaller, not
                    // directly from our Activity context, so give it its own task.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val activityOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ActivityOptions.makeBasic().apply {
                        // Android 15+ no longer delegates the creator's background
                        // activity-launch privilege to a PendingIntent by default.
                        // PackageInstaller is the only recipient of this explicit,
                        // mutable PendingIntent and needs to launch our status router
                        // immediately so it can surface EXTRA_INTENT confirmation.
                        setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                    }.toBundle()
                } else {
                    null
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    sessionId,
                    intent,
                    flags,
                    activityOptions,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }
}
