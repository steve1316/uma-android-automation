package com.steve1316.uma_android_automation

import android.app.Application
import android.content.res.Configuration
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.common.ReleaseLevel
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.steve1316.automation_library.utils.GameTarget
import com.steve1316.uma_android_automation.bot.UMA_GAME_NAME
import com.steve1316.uma_android_automation.bot.UMA_GAME_PACKAGES
import expo.modules.ApplicationLifecycleDispatcher
import expo.modules.ExpoReactHostFactory

class MainApplication : Application(), ReactApplication {
    override val reactHost: ReactHost by lazy {
        ExpoReactHostFactory.getDefaultReactHost(
            context = applicationContext,
            packageList =
                PackageList(this).packages.apply {
                    // Packages that cannot be autolinked yet can be added manually here.
                    add(StartPackage())
                },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Tell the library which game this app plays, so a run never starts while the game is missing or another app is in front.
        GameTarget.configure(UMA_GAME_NAME, UMA_GAME_PACKAGES)
        DefaultNewArchitectureEntryPoint.releaseLevel =
            try {
                ReleaseLevel.valueOf(BuildConfig.REACT_NATIVE_RELEASE_LEVEL.uppercase())
            } catch (e: IllegalArgumentException) {
                ReleaseLevel.STABLE
            }
        loadReactNative(this)
        ApplicationLifecycleDispatcher.onApplicationCreate(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ApplicationLifecycleDispatcher.onConfigurationChanged(this, newConfig)
    }
}
