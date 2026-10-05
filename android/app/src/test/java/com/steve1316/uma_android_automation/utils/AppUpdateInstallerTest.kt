package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for picking the release APK that matches the device. The download and install run on-device. */
@DisplayName("App update installer")
class AppUpdateInstallerTest {
    private val arm = AppUpdateChecker.ReleaseAsset("v5.8.9-UmaAndroidAutomation-arm64-v8a-release.apk", 1L, "https://github.com/arm.apk")
    private val x86 = AppUpdateChecker.ReleaseAsset("v5.8.9-UmaAndroidAutomation-x86_64-release.apk", 2L, "https://github.com/x86.apk")

    @Test
    @DisplayName("The APK for the device's preferred ABI is picked")
    fun testPickApk() {
        assertEquals(arm, AppUpdateInstaller.pickApk(listOf(x86, arm), listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(x86, AppUpdateInstaller.pickApk(listOf(arm, x86), listOf("x86_64", "x86", "arm64-v8a")))
    }

    @Test
    @DisplayName("A later ABI is used when the first has no APK, and no match gives null")
    fun testPickApkFallback() {
        assertEquals(arm, AppUpdateInstaller.pickApk(listOf(arm), listOf("x86_64", "arm64-v8a")))
        assertNull(AppUpdateInstaller.pickApk(listOf(arm, x86), listOf("armeabi-v7a")))
    }
}
