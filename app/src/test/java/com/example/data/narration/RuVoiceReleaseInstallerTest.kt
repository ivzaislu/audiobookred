package com.example.data.narration

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuVoiceReleaseInstallerTest {
    private fun metadata(
        name: String = "ruvoice-tts-1.1.0.apk",
        url: String = "https://github.com/kost-t-human/ruvoice-tts/releases/download/v1.1.0/ruvoice-tts-1.1.0.apk",
        digest: String = "sha256:" + "b".repeat(64),
        tag: String = "v1.1.0",
        size: Long = 243_515_707L,
        draft: Boolean = false,
    ): String = JSONObject()
        .put("tag_name", tag)
        .put("draft", draft)
        .put("prerelease", false)
        .put("assets", JSONArray().put(
            JSONObject().put("name", name).put("size", size)
                .put("digest", digest).put("browser_download_url", url),
        ))
        .toString()

    @Test fun installedApkIsDeletedOnlyAfterConfirmedMatchingPackageUpdate() {
        val policy = RuVoiceApkCleanupPolicy
        val apkTime = 2000L
        val before = 1000L
        assertFalse(policy.shouldDelete("1.1.0", null, 0, apkTime, true, before))
        assertFalse(policy.shouldDelete("1.1.0", "1.0.0", 3000, apkTime, true, before))
        assertFalse(policy.shouldDelete("1.1.0", "1.1.0", before, apkTime, true, before))
        assertFalse(policy.shouldDelete("1.1.0", "1.1.0", 3000, 0, true, before))
        assertTrue(policy.shouldDelete("1.1.0", "1.1.0", 3000, apkTime, true, before))
    }

    @Test fun legacyInstalledApkCleanupRequiresInstallationAfterDownload() {
        val policy = RuVoiceApkCleanupPolicy
        assertTrue(policy.shouldDelete("1.1.0", "1.1.0", 4500, 2500, false, 0))
        assertFalse(policy.shouldDelete("1.1.0", "1.1.0", 1500, 2500, false, 0))
        assertFalse(policy.shouldDelete("1.1.0", "1.0.0", 4500, 2500, false, 0))
    }

    @Test fun officialFullApkIsSelected() {
        val release = RuVoiceReleaseMetadata.parse(metadata())
        assertEquals("1.1.0", release.version)
        assertEquals(243_515_707L, release.size)
        assertEquals("ruvoice-tts-1.1.0.apk", release.filename)
        assertEquals("b".repeat(64), release.sha256)
        assertTrue(RuVoiceReleaseMetadata.valid(release))
    }

    @Test fun tamperedAssetUrlAndForeignOwnerAreRejected() {
        val forged = metadata(
            url = "https://github.com/malicious/ruvoice-tts/releases/download/v1.1.0/ruvoice-tts-1.1.0.apk",
        )
        assertThrows(IllegalArgumentException::class.java) {
            RuVoiceReleaseMetadata.parse(forged)
        }
    }

    @Test fun liteOnlyReleaseCannotBeDownloadedAsFull() {
        val lightName = "ruvoice-tts-1.1.0-lite.apk"
        val lightUrl = "https://github.com/kost-t-human/ruvoice-tts/releases/download/v1.1.0/$lightName"
        assertThrows(java.io.IOException::class.java) {
            RuVoiceReleaseMetadata.parse(metadata(name = lightName, url = lightUrl))
        }
    }

    @Test fun missingChecksumUnstableTagAndDraftAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RuVoiceReleaseMetadata.parse(metadata(digest = ""))
        }
        assertThrows(java.io.IOException::class.java) {
            RuVoiceReleaseMetadata.parse(metadata(tag = "testing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuVoiceReleaseMetadata.parse(metadata(draft = true))
        }
    }

    @Test fun mismatchedVersionAndExcessiveSizeAreRejected() {
        assertThrows(java.io.IOException::class.java) {
            RuVoiceReleaseMetadata.parse(metadata(
                name = "ruvoice-tts-1.0.0.apk",
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuVoiceReleaseMetadata.parse(metadata(size = 500L * 1024L * 1024L))
        }
    }
}
