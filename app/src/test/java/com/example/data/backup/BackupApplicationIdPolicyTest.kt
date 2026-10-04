package com.example.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupApplicationIdPolicyTest {
    private val productionId = "com.aistudio.audiobookred.player"
    private val debugId = "$productionId.debug"

    @Test
    fun exactApplicationIdIsAlwaysAccepted() {
        assertTrue(backupApplicationIdIsAccepted(productionId, productionId, debugBuild = false))
        assertTrue(backupApplicationIdIsAccepted(debugId, debugId, debugBuild = true))
    }

    @Test
    fun debugBuildAcceptsCorrespondingProductionBackup() {
        assertTrue(backupApplicationIdIsAccepted(productionId, debugId, debugBuild = true))
    }

    @Test
    fun productionBuildRejectsDebugBackup() {
        assertFalse(backupApplicationIdIsAccepted(debugId, productionId, debugBuild = false))
    }

    @Test
    fun unrelatedApplicationIdIsRejected() {
        assertFalse(backupApplicationIdIsAccepted("com.example.other", debugId, debugBuild = true))
        assertFalse(backupApplicationIdIsAccepted("com.example.other", productionId, debugBuild = false))
    }
}
