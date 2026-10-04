package com.example.data.backup

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataBackupTest {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(UserDataBackup::class.java)

    @Test
    fun backupJsonIsVersionedAndDoesNotExposeDownloadTables() {
        val json = adapter.toJson(
            UserDataBackup(
                applicationId = "com.aistudio.audiobookred.player",
                versionName = "0.5.3.8.1",
                versionCode = 76,
                exportedAtMs = 1L,
            )
        )

        assertTrue(json.contains("\"schema\":\"$USER_DATA_BACKUP_SCHEMA\""))
        assertTrue(json.contains("\"downloaded_audio\":true"))
        assertTrue(json.contains("\"download_records\":true"))
        assertFalse(json.contains("\"download_books\""))
        assertFalse(json.contains("\"download_files\""))
        assertFalse(json.contains("\"local_path\""))
        assertFalse(json.contains("\"download_url\""))
    }

    @Test
    fun backupV1KeepsHistoricalProgressIntervalField() {
        val backup = UserDataBackup(
            applicationId = "com.aistudio.audiobookred.player",
            versionName = "test",
            versionCode = 1,
            exportedAtMs = 1L,
        )

        val json = adapter.toJson(backup)
        assertTrue(json.contains("\"save_progress_interval_seconds\":10"))

        val legacyJson = json.replace(
            "\"save_progress_interval_seconds\":10",
            "\"save_progress_interval_seconds\":45",
        )
        val parsed = adapter.fromJson(legacyJson)
        assertNotNull(parsed)
        assertEquals(45, parsed!!.settings.saveProgressIntervalSeconds)
    }

    @Test
    fun backupV1KeepsHistoricalSeriesVisibilityFields() {
        val backup = UserDataBackup(
            applicationId = "com.aistudio.audiobookred.player",
            versionName = "test",
            versionCode = 1,
            exportedAtMs = 1L,
        )

        val json = adapter.toJson(backup)
        assertTrue(json.contains("\"show_continue_series\":true"))
        assertTrue(json.contains("\"show_series_navigation\":true"))

        val legacyJson = json
            .replace("\"show_continue_series\":true", "\"show_continue_series\":false")
            .replace("\"show_series_navigation\":true", "\"show_series_navigation\":false")
        val parsed = adapter.fromJson(legacyJson)
        assertNotNull(parsed)
        assertFalse(parsed!!.settings.showContinueSeries)
        assertFalse(parsed.settings.showSeriesNavigation)
    }

    @Test
    fun previousProductionV1BackupIsReadableByDebugBuild() {
        // 0.5.3.8.4.2 used the same v1 envelope. Keep this regression fixture
        // intentionally small: all user-data sections already have v1 defaults,
        // so older v1 files remain readable even if a section is absent.
        val json = """
            {
              "schema": "abred.user-data-backup/v1",
              "application_id": "com.aistudio.audiobookred.player",
              "version_name": "0.5.3.8.4.2",
              "version_code": 82,
              "exported_at_ms": 1
            }
        """.trimIndent()

        val backup = adapter.fromJson(json)
        assertNotNull(backup)
        backup!!
        assertEquals(USER_DATA_BACKUP_SCHEMA, backup.schema)
        assertEquals("0.5.3.8.4.2", backup.versionName)
        assertEquals(82, backup.versionCode)
        assertTrue(backup.library.favorites.isEmpty())
        assertTrue(backup.bookmarks.isEmpty())
        assertTrue(backup.progress.isEmpty())
        assertTrue(backup.playbackCheckpoints.isEmpty())
        assertTrue(
            backupApplicationIdIsAccepted(
                backupApplicationId = backup.applicationId,
                currentApplicationId = "com.aistudio.audiobookred.player.debug",
                debugBuild = true,
            )
        )
    }
}
