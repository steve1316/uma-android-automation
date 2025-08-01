# Uma Musume Automation For Android

![GitHub commit activity](https://img.shields.io/github/commit-activity/m/steve1316/uma-android-automation?logo=GitHub) ![GitHub last commit](https://img.shields.io/github/last-commit/steve1316/uma-android-automation?logo=GitHub) ![GitHub issues](https://img.shields.io/github/issues/steve1316/uma-android-automation?logo=GitHub) ![GitHub pull requests](https://img.shields.io/github/issues-pr/steve1316/uma-android-automation?logo=GitHub) ![GitHub](https://img.shields.io/github/license/steve1316/uma-android-automation?logo=GitHub)

> Discord here: https://discord.gg/5Yv4kqjAbm

> Data last updated November 10, 2021 (Tosen Jordan update)

This Android application written in Kotlin is designed to fully automate a run of Uma Musume Pretty Derby by offering a set of options to customize what event rewards the bot should prioritise, stats to focus on, etc. Building on top of the work done for ![Uma Android Training Helper](https://github.com/steve1316/uma-android-training-helper), this aims to solve the issue of spending too much hands-on time with completing a run for Uma Musume Pretty Derby.

https://user-images.githubusercontent.com/18709555/125517168-61b72aa4-28be-4868-b160-2ff4aa4d73f6.mp4

# Disclaimer

Any usage of this tool is at your own risk. No one will be responsible for anything that happens to you or your own account except for yourself.

# Requirements

-   Android Device or Emulator (Nougat 7.0+)
    -   Tablet needs to be a minimum width of 1600 pixels (like the Galaxy Tab S7 with its 2650x1600 pixel resolution).
    -   Tested emulator was Bluestacks 5. Make sure to have the device be in Portrait Mode BEFORE starting the bot as emulators do not have a way to tell the bot that it rotated.

# Features

-   [x] Able to complete a run from start/midway to its completion.
-   [x] Settings to customize preferences and stat prioritization for Training Events.
-   [x] Handles races, both via skipping and running the race manually.
-   [x] Runs extra races to farm fans when enabled in the settings.

# Instructions

1. Download the .apk file from the `Releases` section on the right and install it on your Android device.
2. Once you have it running, fill out the required section marked with \* in the Settings page of the application. That would be the selection of the Character under the Training Event section.
3. Now go back to the Home page after you have finished customizing the settings. The settings you have selected will be shown to you in the text box below the `Start` button.
4. Now tap on the `Start` button. If this is the first time, it will ask you to give the application `Overlay` permission and starting up the `Accessibility` service.
    1. You are also required to enable `Allow restricted settings` in the `App Info` page of the app in the Android Settings.
5. Once it is enabled, tapping on the `Start` button again will create a popup asking if you want `MediaProjection` to work on `A single app` or `Entire screen`. Select the `Entire screen` option. A floating overlay button will now appear that you can move around the screen.
6. Navigate yourself to the screen below that shows available options like Rest, Train, Buy Skills, Races, etc.

> ![main screen](https://user-images.githubusercontent.com/18709555/125517626-d276cda0-bffa-441d-a511-a222237837a1.jpg)

7. Press the overlay button to start the automation process. It is highly recommended to turn on notifications for the app.
    1. The bot will not start on any other screen than what is shown above.

# For Developers

1. Download and extract the project repository.
2. Go to `https://opencv.org/releases/` and download OpenCV (make sure to download the Android version of OpenCV) and extract it. As of 2025-07-20, the OpenCV version used in this project is 4.12.0.
3. Create a new folder inside the root of the project repository named `opencv` and copy the extracted files in `/OpenCV-android-sdk/sdk/` from Step 2 into it.
4. Open the project repository in `Android Studio`.
5. Open up the `opencv` module's `build.gradle`. At the end of the file, paste the following JVM Toolchain block:

```kotlin
// Explicitly set Kotlin JVM toolchain to Java 17 to match the OpenCV module's Java target.
// Without this, Kotlin defaults to JVM 21 (especially with Kotlin 2.x), which causes a build failure:
// "Inconsistent JVM Target Compatibility Between Java and Kotlin Tasks".
// See: https://kotl.in/gradle/jvm/toolchain for details.
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
```

6. You can now build and run on your Android Device or build your own .apk file.
7. You can set `universalApk` to `true` in the app's `build.gradle` to build a one-for-all .apk file or adjust the `include 'arm64-v8a'` to customize which ABI to build the .apk file for.

# Technologies Used

1. [jpn.traineddata from UmaUmaCruise by @amate](https://github.com/amate/UmaUmaCruise)
2. [MediaProjection - Used to obtain full screenshots](https://developer.android.com/reference/android/media/projection/MediaProjection)
3. [AccessibilityService - Used to dispatch gestures like tapping and scrolling](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
4. [OpenCV Android - Used to template match](https://opencv.org/releases/)
5. [Tesseract4Android - For performing OCR on the screen](https://github.com/adaptech-cz/Tesseract4Android)
6. [string-similarity - For comparing string similarities during text detection](https://github.com/rrice/java-string-similarity)
7. [AppUpdater - For automatically checking and notifying the user for new app updates](https://github.com/javiersantos/AppUpdater)
