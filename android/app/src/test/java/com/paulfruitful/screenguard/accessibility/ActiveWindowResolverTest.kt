package com.paulfruitful.screenguard.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWindowResolverTest {

    @Test
    fun oemScreenshotAndCaptureUtilitiesAreIgnoredAsTransientOverlays() {
        val screenshotPackages = listOf(
            "com.miui.screenshot",
            "com.xiaomi.screenshot",
            "com.samsung.android.app.smartcapture",
            "com.sec.android.app.screenshot",
            "com.oplus.screenshot",
            "com.coloros.screenshot",
            "com.oneplus.screenshot",
            "com.vivo.screenshot",
            "com.bbk.screenshot",
            "com.huawei.screenshot",
            "com.hihonor.screenshot",
            "com.transsion.screenshot",
            "com.miui.screenrecorder",
            "com.android.systemui",
            "com.vendor.custom.screencapture",
            "com.xiaomi.bluetooth",
            "com.android.bluetooth",
            "com.miui.misound",
            "com.miui.audiomonitor"
        )
        for (pkg in screenshotPackages) {
            assertTrue("Expected $pkg to be ignored as transient overlay", ActiveWindowResolver.isTransientSystemOverlay(pkg))
        }
    }

    @Test
    fun transientScreenshotAndToastClassNamesAreIgnored() {
        assertTrue(ActiveWindowResolver.isTransientSystemClassName("com.miui.screenshot.TakeScreenshotService"))
        assertTrue(ActiveWindowResolver.isTransientSystemClassName("com.samsung.android.app.smartcapture.SmartCaptureOverlay"))
        assertTrue(ActiveWindowResolver.isTransientSystemClassName("android.widget.Toast"))
        assertTrue(ActiveWindowResolver.isTransientSystemClassName("com.android.systemui.volume.VolumeDialogImpl"))
        assertFalse(ActiveWindowResolver.isTransientSystemClassName("com.twitter.app.main.MainActivity"))
    }

    @Test
    fun normalAppsAndLaunchersAreNotIgnoredAsTransientOverlays() {
        val realApps = listOf(
            "com.twitter.android",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.android.chrome",
            "com.whatsapp",
            "com.miui.home",
            "com.sec.android.app.launcher"
        )
        for (pkg in realApps) {
            assertFalse("Expected $pkg NOT to be treated as transient overlay", ActiveWindowResolver.isTransientSystemOverlay(pkg))
        }
    }
}
