package com.paulfruitful.screenguard.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun isNewerVersion_detectsMajorMinorPatchUpgrades() {
        assertTrue(AppUpdateManager.isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.1.1", "1.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.1.0", "1.1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.9", "1.1.0"))
    }
}
