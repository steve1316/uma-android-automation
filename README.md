# Uma Musume Automation For Android

![GitHub commit activity](https://img.shields.io/github/commit-activity/m/steve1316/uma-android-automation?logo=GitHub) ![GitHub last commit](https://img.shields.io/github/last-commit/steve1316/uma-android-automation?logo=GitHub) ![GitHub issues](https://img.shields.io/github/issues/steve1316/uma-android-automation?logo=GitHub) ![GitHub pull requests](https://img.shields.io/github/issues-pr/steve1316/uma-android-automation?logo=GitHub) ![GitHub](https://img.shields.io/github/license/steve1316/uma-android-automation?logo=GitHub)

> **Discord:** https://discord.gg/5Yv4kqjAbm

This Android application written in Kotlin is designed to fully automate a run of Uma Musume Pretty Derby by offering a comprehensive set of options to customize event rewards, stat prioritization, race scheduling, automatic skill point buying, and more. Featuring a robust modern frontend built on React Native (Expo) and an extensive computer-vision driven backend, this app aims to solve the issue of spending too much hands-on time completing runs into something you can set and forget.

> [!TIP]
> For a detailed explanation of how the bot works — including the decision engine, training scoring, racing system, item management, and scenario-specific logic — see [HOW_IT_WORKS.md](HOW_IT_WORKS.md).

https://github.com/user-attachments/assets/962adaf4-5e78-4807-8c25-e39be9a68fb4

# Disclaimer

This project is purely for educational purposes to learn about Android automation and computer vision - basically a fun way to practice coding skills. Any usage is at your own risk. No one will be responsible for anything that happens to you or your own account except for yourself.

# Requirements

- Android Device or Emulator (Nougat 7.0+)
    - Hard requirement for Android phones is 1080p and 240 DPI or 450 DPI (for Samsung phones). If your device do not meet these, you can try the `Basic Template Matching Test` in the Settings under the `Debug Tests` section to determine the best scale to use in the `Custom Scale for Template Matching` setting. If not, then you can also try the [To set the phone's resolution to 1080p (faster and more accurate)](#to-set-the-phones-resolution-to-1080p-faster-and-more-accurate) section to forcibly set the display resolution and DPI of your Android phone. Note that may come with the side-effect of your device UI being scrunched in or zoomed out.
    - Tested emulators are Bluestacks 5 (Pie 64-bit, but other versions should work) and MuMu. The following setup is required:
        - Portrait Mode needs to be forced on always.
        - Bluestacks itself needs to be updated to the latest version to avoid Uma Musume crashing.
        - In the Bluestacks Settings > Phone, the predefined profile needs to be set to a modern high-end phone like the Samsung Galaxy S22.
        - Setup for both BlueStacks and MuMu:
            - 6 CPU Cores
            - 6GB Memory
            - 1080 x 1920 (width x height)
            - 240 DPI (This is important)
            - Disable the equivalent of "Keep alive in background" in the emulator settings to prevent the overlay button from not showing up.
            - Specific to MuMu: the `App running` emulator setting also needs to be disabled for the same issue.

> [!WARNING]
> If you change the display resolution while the overlay button is still active, you will need to restart the app in order for the display changes to persist to the `MediaProjection` service.

> [!IMPORTANT]
> The in-game graphics need to be set to `Standard` instead of `Basic`.

# Features

- [x] Completes a full run from start/midway to its conclusion.
- [x] Supports multiple scenarios including **URA Finale**, **Unity Cup**, and those in the future to come.
- [x] Advanced OpenCV template matching for real-time gamestate awareness.
- [x] YOLOv8-powered real-time stat gain detection for improved accuracy.
- [x] Tesseract OCR integrated with rapid fuzzy string matching.
- [x] Modern user interface built using React Native, Typescript and Expo for full configurability.
- [x] Remote Log Viewer to monitor real-time automation progress from any browser on the same network.
- [x] Screen recording for debugging to easily capture and review issues.
- [x] Import/export settings as JSON, alongside customizable skill point buying plans and training configurations.
- [x] Smart racing plan that dynamically schedules extra races based on current stats and fan requirements.
- [x] Training Event customization per event for fine-grained control over choices.
- [x] Load and manage profiles for the Training Settings to easily swap between different builds.
- [x] A multitude of settings to configure including setting preferred stat targets per distance.
- [x] Optional **Ask the Docs** on-device chatbot. Answers questions about the app grounded in `README.md`, `HOW_IT_WORKS.md`, in-app option descriptions, and the Kotlin source code via MiniLM embeddings + a downloaded GGUF model running fully offline through `llama.rn`. Hidden behind an opt-in toggle on the LLM Settings page; once enabled the page reveals model download, active-model selection (also switchable on the fly from the chat page itself), and generation tuning (max output tokens, per-citation context cap, model context window).

# Instructions

1. Download the latest `.apk` file from the `Releases` section on the right of this page and install it on your Android device.
2. Open the application. Upon launching, navigate through the user-friendly frontend to select your desired scenario (URA Finale, Unity Cup, etc.) and configure your training priorities, races, and other settings.
3. You can review your loaded settings and configurations directly on the Home page.
4. Tap the `Start` button. If this is the first time, you will be prompted to grant `Overlay` permissions and enable the `Accessibility` service.

> [!NOTE]
> On newer Android versions, you're required to enable `Allow restricted settings` in the app's `App Info` settings.

> [!WARNING]
> Disable the system `Accessibility shortcut` (the floating Accessibility button or volume-key shortcut) before starting a run. Leaving it enabled and hanging out on the screen has caused problems in the past, including covering UI elements that the bot needs to read at the edges of the screen.

5. Once enabled, tapping `Start` will request `MediaProjection` access (select `Entire screen` if prompted). A floating overlay button will appear that you can drag around the screen.
6. Follow the guidance overlay when you drag the overlay button for the places on the screen to safely leave the button at to avoid covering important UI elements.

> [!CAUTION]
> Placing the overlay button over important UI elements will interfere with template matching and OCR detection.

7. Navigate to the main training menu in Uma Musume (where Rest, Train, Buy Skills, Races, etc. are visible).

> <img width="270" height="585" alt="main screen" src="https://github.com/user-attachments/assets/05239856-878e-4e49-a325-db60013d7c75" />

8. Tap the overlay button to start automation.

> [!TIP]
> Use minimal or deactivated notifications to prevent interference with OCR scanning the top of the screen.

## To view Logs in Real-time

1. Install `Android Studio` and create any new project or open an existing one in order for the `Logcat` console to appear at the bottom.
2. Connect your Android device to your computer:
   - **USB Connection:** Enable `Developer Options` and `USB Debugging` on your device, then connect via USB cable.
   - **Wireless Connection:** In Developer Options, enable `Wireless debugging` and pair your device using the pairing code or QR code.
   - **Bluestacks or other emulators:** In the emulator settings, there is usually an option to allow ADB wireless connection on `127.0.0.1:5555`. Enabling that option should be enough, but if Android Studio still does not see it, you can open up a terminal like `cmd` and type `adb connect 127.0.0.1:5555` and it should say `connected to 127.0.0.1:5555`.

> [!TIP]
> You may need to type `adb disconnect` to disconnect all ADB connections beforehand for a fresh slate.

3. In Android Studio's Logcat console at the bottom of the window, select your connected device from the device dropdown menu.
4. Filter the logs by typing `package:com.steve1316.uma_android_automation [UAA]` or just `[UAA]` in the search box to see only the logs from this app.
5. Run the app - you'll now see all of its logs appear in real-time as it runs.

## To set the phone's resolution to 1080p (faster and more accurate)

> [!NOTE]
> This only works when downscaling. If your device's official resolution is lower than 1080p it will most likely not work.

1. Install the [**aShell You**](https://github.com/DP-Hridayan/aShellYou) app. This allows you to run adb commands locally on your Android device, but requires [**Shizuku**](https://github.com/RikkaApps/Shizuku).
2. Install [**Shizuku**](https://github.com/RikkaApps/Shizuku), then start it by following [these instructions](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging).
3. With **Shizuku** started, you can then use **aShell You** to send the following adb commands:
   - **Change resolution to 1080p:** `wm size 1080x1920 && wm density 240`
   - **Revert to original:** `wm size reset && wm density reset`

    You can also bookmark the commands for your own convenience.

Alternatively, you can do the same on a computer if you cannot get the above to work out.
1. Install [**adb**](https://developer.android.com/tools/releases/platform-tools). You will also to add the file path to the folder to `PATH` via the `Environment Variable` setting under `View advanced system settings` so that the terminal will know what the `adb` command should do. You may need to restart your computer to have your terminal pick up the changes.
2. Open up a new terminal anywhere (cmd, Powershell, etc).
3. Plug in your Android device via USB. If all goes well, then executing `adb devices` will show your connected device when `Settings > Developer options > USB Debugging` is enabled. There may be a popup on your Android device beforehand asking you to give permission to connect to ADB. Wirelessly connecting to ADB is also available via the Android `Settings > Developer options > Wireless debugging`
4. Execute the following commands individually to forcibly set your display resolution to 1080p and DPI to 240:
    - **Change resolution to 1080p:** `adb shell wm size 1080x1920` and `adb shell wm density 240`
    - **Revert changes:** `adb shell wm size reset` and `adb shell wm density reset`

> [!WARNING]
> If your home button disappears, reset the DPI back to default.

> [!TIP]
> Use 1.0 scaling and an 80% confidence threshold for best results in 1080p natively.

# For Developers

This project is separated into a React Native frontend configured via Expo and an extensive Kotlin/OpenCV backend.

1. Download and extract the repository.
2. Download OpenCV for Android (v4.12.0) from `https://opencv.org/releases/`. Create `/android/opencv` and copy the extracted `/OpenCV-android-sdk/sdk/` contents into it.
3. The project uses a YOLOv8 model for stat gain detection. Ensure the `best.onnx` model file is present in the `android/app/src/main/assets/` directory.

> [!IMPORTANT]
> Without the ONNX model file, the YOLO stat detection feature will not work. Template matching will still function as a fallback.

4. The project utilizes Expo. Run `yarn install` from the root directory to install frontend dependencies.
5. The dev environment is ready. Run `yarn start` or `npx expo start` to run the Metro HTTP server.
6. To ensure code consistency, developers should format and lint the codebase using the following commands:
    - `yarn format`: Formats both TypeScript/TSX files (via **Prettier**) and Kotlin files (via **Ktlint**).
    - `yarn format:tsx`: Formats only TypeScript and TSX files using **Prettier** (following settings in [.prettierrc](./.prettierrc), with exclusions in [.prettierignore](./.prettierignore)).
    - `yarn format:kt`: Formats only Kotlin files using **Ktlint** (following settings in [android/.editorconfig](./android/.editorconfig)).
7. To test Android builds, execute `yarn android` to compile and install the application directly on your device. Use `yarn build` for release APK generation.

> [!NOTE]
> Do not run the React Native shell app directly from Android Studio. Always rely on the Expo Metro bundler for correct bridging.

# Technologies Used

1. [eng.traineddata from tessdata](https://github.com/tesseract-ocr/tessdata)
2. [MediaProjection - Used to obtain full screenshots](https://developer.android.com/reference/android/media/projection/MediaProjection)
3. [AccessibilityService - Used to dispatch gestures like tapping and scrolling](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
4. [OpenCV Android - Used to template match](https://opencv.org/releases/)
5. [Tesseract4Android - For performing OCR on the screen](https://github.com/adaptech-cz/Tesseract4Android)
6. [string-similarity - For comparing string similarities during text detection](https://github.com/rrice/java-string-similarity)
7. [React Native - Used as the frontend](https://reactnative.dev/)
8. [Expo - Modern modular frontend](https://expo.dev/)
9. [SQLite - Local database via expo-sqlite](https://docs.expo.dev/versions/latest/sdk/sqlite/)
10. [Ktor - For the Remote Log Viewer](https://ktor.io/)
11. [YOLOv8 - Object detection](https://github.com/ultralytics/ultralytics)
12. [ONNX Runtime - Lightweight engine for executing the YOLOv8 model](https://onnxruntime.ai/)
13. [llama.rn - On-device GGUF LLM inference for the Ask the Docs chatbot](https://github.com/mybigday/llama.rn)
14. [sentence-transformers/all-MiniLM-L6-v2 - Embedding model powering retrieval over the docs and Kotlin source](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
15. [Qwen 2.5 Instruct GGUF - Default chat model presets for the Ask the Docs chatbot](https://huggingface.co/Qwen)
16. [react-native-marked - Renders the chatbot's Markdown answers and citations](https://github.com/gmsgowtham/react-native-marked)