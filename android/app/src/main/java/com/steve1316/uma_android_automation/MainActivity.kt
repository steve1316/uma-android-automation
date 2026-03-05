package com.steve1316.uma_android_automation

import expo.modules.ReactActivityDelegateWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.github.javiersantos.appupdater.AppUpdater
import com.github.javiersantos.appupdater.enums.UpdateFrom
import com.steve1316.automation_library.utils.ScreenStateReceiver
import com.steve1316.automation_library.data.SharedData
import org.opencv.android.OpenCVLoader
import java.util.Locale

class MainActivity : ReactActivity() {
	companion object {
		const val loggerTag: String = "UAA"
	}

	// This ViewGroup holds the Lottie animated splash screen that is displayed during app startup and allows for cleanup.
	private var splashViewGroup: ViewGroup? = null
	private val splashDuration = 1500L

	override fun onCreate(savedInstanceState: Bundle?) {
        // State restoration needs to be null to avoid crash with react-native-screens.
        // https://github.com/software-mansion/react-native-screens/issues/17#issuecomment-424704067
		super.onCreate(null)
		ScreenStateReceiver.register(applicationContext)
		
		// Set application locale to combat cases where user's language uses commas instead of decimal points for floating numbers.
		val config: Configuration? = this.getResources().configuration
		val locale = Locale("en")
		Locale.setDefault(locale)
		this.getResources().updateConfiguration(config, this.getResources().displayMetrics)

		// Set up the app updater to check for the latest update from GitHub.
		AppUpdater(this)
			.setUpdateFrom(UpdateFrom.XML)
			.setUpdateXML("https://raw.githubusercontent.com/steve1316/uma-android-automation/refs/heads/master/android/app/update.xml")
			.start();

		// Load OpenCV native library. This will throw a "E/OpenCV/StaticHelper: OpenCV error: Cannot load info library for OpenCV". It is safe to
		// ignore this error. OpenCV functionality is not impacted by this error.
		OpenCVLoader.initDebug()

		// Only show splash screen on first launch, not when resuming from background.
		if (savedInstanceState == null) {
			showSplashScreen()
		}

        // Setup the guidance regions for the floating overlay button.
        SharedData.guidanceRegions = arrayOf(
            intArrayOf(520, 0, 280, 110),
            intArrayOf(200, 2215, 300, 120),
        )
	}

	override fun onDestroy() {
		ScreenStateReceiver.unregister(applicationContext)
		super.onDestroy()
	}

	/**
	 * Returns the name of the main component registered from JavaScript. This is used to schedule
	 * rendering of the component.
	 *
	 * Note: This needs to match with the name declared in app.json!
	 */
	override fun getMainComponentName(): String = "Uma Android Automation"

	/**
	 * Returns the instance of the [com.facebook.react.ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
	 * which allows you to enable New Architecture with a single boolean flags [com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled]
	 */
	override fun createReactActivityDelegate(): ReactActivityDelegate = ReactActivityDelegateWrapper(this, BuildConfig.IS_NEW_ARCHITECTURE_ENABLED, DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled))

	/**
	 * Displays an animated splash screen while the application loads the Javascript bundle.
	 */
	private fun showSplashScreen() {
		// Create a FrameLayout container that will hold the Lottie animation and allows the animation to be positioned and scaled.
		val frameLayout = FrameLayout(this)
		frameLayout.layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT
		)

		// Initialize the Lottie animation view with proper scaling configuration and add it to the FrameLayout.
		// Animation file located in assets/splash.json
		val lottieView = LottieAnimationView(this)
		lottieView.setAnimation("splash.json")
		lottieView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
		lottieView.layoutParams = FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT,
			FrameLayout.LayoutParams.MATCH_PARENT
		)
		frameLayout.addView(lottieView)

		// Display the splash screen visible to the user.
		addContentView(frameLayout, ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT
		))
		splashViewGroup = frameLayout

		// Play the Lottie animation.
		lottieView.playAnimation()

		// Automatically transition to the main UI after the animation is finished.
		Handler(Looper.getMainLooper()).postDelayed({
			hideSplashScreen()
		}, splashDuration)
	}

	/**
	 * Removes the splash screen and performs cleanup.
	 */
	private fun hideSplashScreen() {
		splashViewGroup?.let { view ->
			val parent = view.parent as? ViewGroup
			parent?.removeView(view)
			splashViewGroup = null
		}
	}
}