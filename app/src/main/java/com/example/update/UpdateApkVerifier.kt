package com.example.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class UpdateApkVerificationException(message: String) : IOException(message)

internal fun signerIdentityAllowsUpdate(
    installedCurrentSigners: Set<String>,
    candidateCurrentSigners: Set<String>,
    candidateSigningHistory: Set<String>,
    installedHasMultipleSigners: Boolean,
    candidateHasMultipleSigners: Boolean,
): Boolean {
    if (installedCurrentSigners.isEmpty() || candidateCurrentSigners.isEmpty()) return false

    // Android does not support proof-of-rotation semantics for multi-signer APKs;
    // in that case every current signer must match exactly.
    if (installedHasMultipleSigners || candidateHasMultipleSigners) {
        return installedCurrentSigners == candidateCurrentSigners
    }

    val effectiveCandidateHistory =
        candidateSigningHistory.ifEmpty { candidateCurrentSigners }
    return installedCurrentSigners.all(effectiveCandidateHistory::contains)
}

/**
 * Verifies that a downloaded APK is an actual update for this installed app.
 * Byte-level SHA/size verification belongs to UpdateApkDownloader.
 */
internal class UpdateApkVerifier {
    fun verifyIdentity(context: Context, apk: File, expectedVersionCode: Int) {
        val packageManager = context.packageManager
        val candidate = archivePackageInfo(packageManager, apk)
            ?: throw UpdateApkVerificationException("Не удалось прочитать пакет обновления")
        if (candidate.packageName != context.packageName) {
            throw UpdateApkVerificationException("Неверный package id обновления")
        }
        if (packageVersionCode(candidate) != expectedVersionCode.toLong()) {
            throw UpdateApkVerificationException("Неверный versionCode обновления")
        }

        val current = installedPackageInfo(packageManager, context.packageName)
            ?: throw UpdateApkVerificationException("Не удалось проверить подпись установленного приложения")
        val candidateIdentity = signingIdentity(candidate)
        val currentIdentity = signingIdentity(current)
        if (
            !signerIdentityAllowsUpdate(
                installedCurrentSigners = currentIdentity.currentSigners,
                candidateCurrentSigners = candidateIdentity.currentSigners,
                candidateSigningHistory = candidateIdentity.signingHistory,
                installedHasMultipleSigners = currentIdentity.hasMultipleSigners,
                candidateHasMultipleSigners = candidateIdentity.hasMultipleSigners,
            )
        ) {
            throw UpdateApkVerificationException("Подпись APK не совпадает с установленным приложением")
        }
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(packageManager: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(packageManager: PackageManager, packageName: String): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private data class SigningIdentity(
        val currentSigners: Set<String>,
        val signingHistory: Set<String>,
        val hasMultipleSigners: Boolean,
    )

    @Suppress("DEPRECATION")
    private fun signingIdentity(info: PackageInfo): SigningIdentity {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val current = signatureDigests(info.signatures.orEmpty())
            return SigningIdentity(
                currentSigners = current,
                signingHistory = current,
                hasMultipleSigners = current.size > 1,
            )
        }

        val signingInfo = info.signingInfo
            ?: return SigningIdentity(emptySet(), emptySet(), false)
        val current = signatureDigests(signingInfo.apkContentsSigners.orEmpty())
        val multiple = signingInfo.hasMultipleSigners()
        val history = if (multiple) {
            current
        } else {
            signatureDigests(signingInfo.signingCertificateHistory.orEmpty())
                .ifEmpty { current }
        }
        return SigningIdentity(
            currentSigners = current,
            signingHistory = history,
            hasMultipleSigners = multiple,
        )
    }

    private fun signatureDigests(
        signatures: Array<out android.content.pm.Signature>,
    ): Set<String> =
        signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
}
