package com.example.update

import android.content.pm.PackageInstaller
import com.example.BuildConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUpdateManagerTest {
    private fun manifest(
        versionCode: Int = BuildConfig.VERSION_CODE + 1,
        versionName: String = "next",
        applicationId: String = BuildConfig.APPLICATION_ID,
        schema: String = AndroidUpdateManager.UPDATE_SCHEMA,
        downloadUrl: String = "https://github.com/ivzaislu/audiobookred/releases/download/android-vnext/abred-android-next.apk",
        notes: String = "test",
        sizeBytes: Long = 123L,
    ): String = """{
      "schema": "$schema",
      "application_id": "$applicationId",
      "version_name": "$versionName",
      "version_code": $versionCode,
      "mandatory": false,
      "release_notes": "$notes",
      "size_bytes": $sizeBytes,
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "download_url": "$downloadUrl"
    }"""

    @Test
    fun parseUpdateAcceptsNewerValidatedMetadata() {
        val nextCode = BuildConfig.VERSION_CODE + 1
        val info = AndroidUpdateManager.parseUpdate(manifest(versionCode = nextCode))
        requireNotNull(info)
        assertEquals("next", info.versionName)
        assertEquals(nextCode, info.versionCode)
        assertEquals(123L, info.sizeBytes)
    }

    @Test
    fun parseUpdateBoundsReleaseNotesStoredForPermissionRetry() {
        val notes = "x".repeat(AndroidUpdateManager.MAX_RELEASE_NOTES_CHARS + 500)
        val info = AndroidUpdateManager.parseUpdate(manifest(notes = notes))
        requireNotNull(info)
        assertEquals(AndroidUpdateManager.MAX_RELEASE_NOTES_CHARS, info.releaseNotes.length)
    }

    @Test
    fun parseUpdateRejectsCurrentOrOlderVersion() {
        assertNull(AndroidUpdateManager.parseUpdate(manifest(versionCode = BuildConfig.VERSION_CODE)))
        assertNull(AndroidUpdateManager.parseUpdate(manifest(versionCode = BuildConfig.VERSION_CODE - 1)))
    }

    @Test
    fun parseUpdateRejectsWrongSchemaOrApplicationId() {
        assertNull(AndroidUpdateManager.parseUpdate(manifest(schema = "other/v1")))
        assertNull(AndroidUpdateManager.parseUpdate(manifest(applicationId = "example.wrong")))
    }

    @Test
    fun parseUpdateRejectsInvalidChecksumOrSize() {
        val invalidSha = manifest().replace(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "not-a-sha",
        )
        assertNull(AndroidUpdateManager.parseUpdate(invalidSha))
        assertNull(AndroidUpdateManager.parseUpdate(manifest(sizeBytes = 0L)))
        assertNull(
            AndroidUpdateManager.parseUpdate(
                manifest(sizeBytes = AndroidUpdateManager.MAX_UPDATE_APK_BYTES + 1L)
            )
        )
    }

    @Test
    fun updateInstallFailuresAreClassifiedForUserFacingMessages() {
        listOf(
            SocketTimeoutException("timeout"),
            InterruptedIOException("interrupted"),
        ).forEach { error ->
            assertEquals(InstallStartResult.TIMEOUT, classifyInstallFailure(error))
        }

        listOf(
            UnknownHostException("offline"),
            ConnectException("refused"),
            NoRouteToHostException("no route"),
            SocketException("connection reset"),
        ).forEach { error ->
            assertEquals(InstallStartResult.NETWORK_ERROR, classifyInstallFailure(error))
        }

        assertEquals(InstallStartResult.NOT_ENOUGH_SPACE, classifyInstallFailure(UpdateStorageException()))
        assertEquals(
            InstallStartResult.APK_VERIFICATION_FAILED,
            classifyInstallFailure(UpdateApkVerificationException("bad apk")),
        )
        assertEquals(InstallStartResult.DOWNLOAD_ERROR, classifyInstallFailure(IOException("http failure")))
        assertEquals(InstallStartResult.FAILED, classifyInstallFailure(IllegalStateException("unexpected")))
    }

    @Test
    fun updateFailureMessagesAreSpecificAndActionable() {
        assertTrue(updateInstallFailureMessage(InstallStartResult.TIMEOUT).contains("долго не отвечает"))
        assertTrue(updateInstallFailureMessage(InstallStartResult.NETWORK_ERROR).contains("Соединение потеряно"))
        assertTrue(updateInstallFailureMessage(InstallStartResult.NOT_ENOUGH_SPACE).contains("Недостаточно свободного места"))
        assertTrue(updateInstallFailureMessage(InstallStartResult.APK_VERIFICATION_FAILED).contains("не прошёл проверку"))
        assertTrue(updateInstallFailureMessage(InstallStartResult.DOWNLOAD_ERROR).contains("Не удалось скачать APK"))
    }

    @Test
    fun signerIdentityAcceptsSameSignerAndLegitimateRotation() {
        assertTrue(
            signerIdentityAllowsUpdate(
                installedCurrentSigners = setOf("old"),
                candidateCurrentSigners = setOf("old"),
                candidateSigningHistory = setOf("old"),
                installedHasMultipleSigners = false,
                candidateHasMultipleSigners = false,
            )
        )
        assertTrue(
            signerIdentityAllowsUpdate(
                installedCurrentSigners = setOf("old"),
                candidateCurrentSigners = setOf("new"),
                candidateSigningHistory = setOf("old", "new"),
                installedHasMultipleSigners = false,
                candidateHasMultipleSigners = false,
            )
        )
    }

    @Test
    fun signerIdentityRejectsUnrelatedOrInvalidMultiSignerUpdate() {
        assertFalse(
            signerIdentityAllowsUpdate(
                installedCurrentSigners = setOf("old"),
                candidateCurrentSigners = setOf("attacker"),
                candidateSigningHistory = setOf("attacker"),
                installedHasMultipleSigners = false,
                candidateHasMultipleSigners = false,
            )
        )
        assertFalse(
            signerIdentityAllowsUpdate(
                installedCurrentSigners = setOf("a", "b"),
                candidateCurrentSigners = setOf("a", "c"),
                candidateSigningHistory = setOf("a", "b", "c"),
                installedHasMultipleSigners = true,
                candidateHasMultipleSigners = true,
            )
        )
        assertTrue(
            signerIdentityAllowsUpdate(
                installedCurrentSigners = setOf("a", "b"),
                candidateCurrentSigners = setOf("a", "b"),
                candidateSigningHistory = setOf("a", "b"),
                installedHasMultipleSigners = true,
                candidateHasMultipleSigners = true,
            )
        )
    }

    @Test
    fun downloadPercentTracksAndClampsProgress() {
        assertEquals(0, updateDownloadPercent(0L, 100L))
        assertEquals(50, updateDownloadPercent(50L, 100L))
        assertEquals(100, updateDownloadPercent(100L, 100L))
        assertEquals(100, updateDownloadPercent(150L, 100L))
        assertEquals(0, updateDownloadPercent(-10L, 100L))
        assertEquals(0, updateDownloadPercent(50L, 0L))
    }

    @Test
    fun downloadResponsePolicyHandlesResumeAndServerFallbacks() {
        assertEquals(
            UpdateDownloadResponseAction.APPEND,
            updateDownloadResponseAction(
                statusCode = 206,
                contentRange = "bytes 100-999/1000",
                requestedOffset = 100L,
                expectedSizeBytes = 1000L,
            ),
        )
        assertEquals(
            UpdateDownloadResponseAction.WRITE_FULL,
            updateDownloadResponseAction(
                statusCode = 200,
                contentRange = null,
                requestedOffset = 100L,
                expectedSizeBytes = 1000L,
            ),
        )
        assertEquals(
            UpdateDownloadResponseAction.RETRY_FULL,
            updateDownloadResponseAction(
                statusCode = 416,
                contentRange = null,
                requestedOffset = 100L,
                expectedSizeBytes = 1000L,
            ),
        )
        assertEquals(
            UpdateDownloadResponseAction.RETRY_FULL,
            updateDownloadResponseAction(
                statusCode = 206,
                contentRange = "bytes 99-999/1000",
                requestedOffset = 100L,
                expectedSizeBytes = 1000L,
            ),
        )
        assertEquals(
            UpdateDownloadResponseAction.REJECT,
            updateDownloadResponseAction(
                statusCode = 500,
                contentRange = null,
                requestedOffset = 100L,
                expectedSizeBytes = 1000L,
            ),
        )
    }

    @Test
    fun copyAndVerifyUpdateApkCanResumeFromExistingPrefix() {
        val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val split = 9_000
        val prefix = payload.copyOfRange(0, split)
        val suffix = payload.copyOfRange(split, payload.size)
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(prefix)
        }
        val output = ByteArrayOutputStream().apply {
            write(prefix)
        }
        val progress = mutableListOf<Long>()

        val written = copyAndVerifyUpdateApk(
            input = ByteArrayInputStream(suffix),
            output = output,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = expectedSha,
            maxSizeBytes = payload.size.toLong(),
            initialBytes = prefix.size.toLong(),
            digest = digest,
            onBytesDownloaded = progress::add,
        )

        assertEquals(payload.size.toLong(), written)
        assertTrue(payload.contentEquals(output.toByteArray()))
        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size.toLong(), progress.last())
    }

    @Test
    fun copyAndVerifyUpdateApkAcceptsValidPayloadAndReportsProgress() {
        val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()

        val written = copyAndVerifyUpdateApk(
            input = ByteArrayInputStream(payload),
            output = output,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = expectedSha,
            maxSizeBytes = payload.size.toLong(),
            onBytesDownloaded = progress::add,
        )

        assertEquals(payload.size.toLong(), written)
        assertTrue(payload.contentEquals(output.toByteArray()))
        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size.toLong(), progress.last())
    }

    @Test
    fun truncatedPayloadIsResumableInsteadOfApkVerificationFailure() {
        val payload = "truncated".toByteArray()

        val error = runCatching {
            copyAndVerifyUpdateApk(
                input = ByteArrayInputStream(payload),
                output = ByteArrayOutputStream(),
                expectedSizeBytes = payload.size.toLong() + 5L,
                expectedSha256 = "00".repeat(32),
                maxSizeBytes = 1024L,
            )
        }.exceptionOrNull()

        assertTrue(error is UpdateIncompleteDownloadException)
        val incomplete = error as UpdateIncompleteDownloadException
        assertEquals(payload.size.toLong(), incomplete.downloadedBytes)
        assertEquals(payload.size.toLong() + 5L, incomplete.expectedBytes)
        assertEquals(
            InstallStartResult.DOWNLOAD_ERROR,
            classifyInstallFailure(incomplete),
        )
    }

    @Test(expected = UpdateApkVerificationException::class)
    fun copyAndVerifyUpdateApkRejectsPayloadLargerThanManifest() {
        val payload = ByteArray(128) { 1 }
        copyAndVerifyUpdateApk(
            input = ByteArrayInputStream(payload),
            output = ByteArrayOutputStream(),
            expectedSizeBytes = 64L,
            expectedSha256 = "00".repeat(32),
            maxSizeBytes = 1024L,
        )
    }

    @Test(expected = UpdateApkVerificationException::class)
    fun copyAndVerifyUpdateApkRejectsWrongChecksum() {
        val payload = "valid size but wrong digest".toByteArray()
        copyAndVerifyUpdateApk(
            input = ByteArrayInputStream(payload),
            output = ByteArrayOutputStream(),
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = "00".repeat(32),
            maxSizeBytes = 1024L,
        )
    }

    @Test
    fun partialFileSizeIsReusedForResume() {
        val dir = Files.createTempDirectory("abred-update-test").toFile()
        try {
            val part = java.io.File(dir, "update.part")
            part.writeBytes(ByteArray(321) { 7 })

            assertEquals(
                321L,
                resumableUpdateBytes(part, expectedSizeBytes = 1_000L),
            )
            assertTrue(part.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizedPartialFileIsDiscardedBeforeResume() {
        val dir = Files.createTempDirectory("abred-update-test").toFile()
        try {
            val part = java.io.File(dir, "update.part")
            part.writeBytes(ByteArray(1_001) { 7 })

            assertEquals(
                0L,
                resumableUpdateBytes(part, expectedSizeBytes = 1_000L),
            )
            assertFalse(part.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fullyDownloadedPartialMustMatchExpectedShaBeforeReuse() {
        val dir = Files.createTempDirectory("abred-update-test").toFile()
        try {
            val payload = "complete apk bytes".toByteArray()
            val part = java.io.File(dir, "update.part")
            part.writeBytes(payload)
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }

            assertTrue(
                updateFileMatches(
                    file = part,
                    expectedSizeBytes = payload.size.toLong(),
                    expectedSha256 = sha,
                )
            )
            assertFalse(
                updateFileMatches(
                    file = part,
                    expectedSizeBytes = payload.size.toLong(),
                    expectedSha256 = "00".repeat(32),
                )
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mandatoryUpdateCannotBeCanceledBeforeOrDuringDownload() {
        assertFalse(AndroidUpdateManager.isUpdatePromptCancelable(mandatory = true))
        assertFalse(AndroidUpdateManager.isUpdateDownloadCancelable(mandatory = true))
        assertTrue(AndroidUpdateManager.isUpdatePromptCancelable(mandatory = false))
        assertTrue(AndroidUpdateManager.isUpdateDownloadCancelable(mandatory = false))
        assertEquals("Повторить", AndroidUpdateManager.updateFailureActionLabel(mandatory = true))
        assertEquals("Закрыть", AndroidUpdateManager.updateFailureActionLabel(mandatory = false))
    }

    @Test
    fun installStatusReportsBlockedFailureWithSystemDetail() {
        assertEquals(
            "Установка обновления заблокирована: blocked by verifier",
            AndroidUpdateManager.installStatusMessage(
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                "blocked by verifier",
            ),
        )
    }

    @Test
    fun installStatusMessagesCoverInstallerFailures() {
        assertTrue(
            AndroidUpdateManager.installStatusMessage(PackageInstaller.STATUS_FAILURE_STORAGE, null)
                ?.contains("Недостаточно места") == true
        )
        assertTrue(
            AndroidUpdateManager.installStatusMessage(PackageInstaller.STATUS_FAILURE_INVALID, null)
                ?.contains("недействителен") == true
        )
        assertTrue(
            AndroidUpdateManager.installStatusMessage(PackageInstaller.STATUS_FAILURE_ABORTED, null)
                ?.contains("отменена") == true
        )
        assertTrue(
            AndroidUpdateManager.installStatusMessage(PackageInstaller.STATUS_FAILURE_TIMEOUT, null)
                ?.contains("Истекло время ожидания") == true
        )
    }

    @Test
    fun installStatusIgnoresSuccessAndPendingConfirmation() {
        assertNull(AndroidUpdateManager.installStatusMessage(PackageInstaller.STATUS_SUCCESS, null))
        assertNull(AndroidUpdateManager.installStatusMessage(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
    }

    @Test
    fun postUpdateRelaunchIsAllowedOnlyOncePerInstalledVersion() {
        assertTrue(
            shouldAutoRelaunchUpdatedApp(
                currentVersionCode = 89L,
                lastRelaunchedVersionCode = 88L,
            )
        )
        assertFalse(
            shouldAutoRelaunchUpdatedApp(
                currentVersionCode = 89L,
                lastRelaunchedVersionCode = 89L,
            )
        )
        assertFalse(
            shouldAutoRelaunchUpdatedApp(
                currentVersionCode = -1L,
                lastRelaunchedVersionCode = 88L,
            )
        )
    }

    @Test
    fun installStatusUiOpensMainActivityOnlyAfterSuccess() {
        assertEquals(
            InstallStatusUiAction.OPEN_MAIN_ACTIVITY,
            installStatusUiAction(PackageInstaller.STATUS_SUCCESS),
        )
        assertEquals(
            InstallStatusUiAction.REQUEST_USER_ACTION,
            installStatusUiAction(PackageInstaller.STATUS_PENDING_USER_ACTION),
        )

        listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            PackageInstaller.STATUS_FAILURE_TIMEOUT,
        ).forEach { status ->
            assertEquals(
                "status=$status must not open MainActivity",
                InstallStatusUiAction.FINISH_SILENTLY,
                installStatusUiAction(status),
            )
        }
    }
}
