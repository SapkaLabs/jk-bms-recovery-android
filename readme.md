# JK BMS Recovery for Android

Read device information and access codes from compatible JK battery management systems over Bluetooth.

[Download the APK](https://github.com/SapkaLabs/jk-bms-recovery-android/releases/latest)

## What the app does

- Runs on Android 8.0 and later.
- Filters nearby devices by JK names and Bluetooth advertisement data. You can also show all devices.
- Reads the model, hardware, firmware, manufacture code, serial number and available passcodes.
- Sends a device-information request without changing battery settings.
- Shows connection logs that you can copy or save as text.
- Allows screenshots and screen recording.

The app needs no account and has no Internet permission or analytics.

## Install and use

1. Download `JK-BMS-Recovery-1.0.2-debug.apk` from [Releases](https://github.com/SapkaLabs/jk-bms-recovery-android/releases/latest) and open it on your phone.
2. Wake the BMS, keep the phone nearby, and close other apps connected to it.
3. Tap "Scan for devices" and allow Bluetooth access. Android 11 and earlier also require Location permission and the Location service for scanning.
4. Select your BMS. If it is missing, turn on "Show all BLE devices".
5. Turn on "Show passcodes" after the information appears.

The APK uses a debug signing key. You can install it over the earlier local builds. "Try sample results" shows demo data without connecting to a BMS.

Results clear when you leave the app. Logs remain in memory until you clear them or the app process exits. Raw logs can contain passcodes.

## Screenshots

A user supplied these screenshots after testing a `JK_PB2A16S20P` with hardware `15A` and firmware `15.41`. They show device discovery and recovered information. The passcodes are hidden in the screenshot.

| Device discovery | Recovered information |
| --- | --- |
| <img src="docs/screenshots/device-discovery.jpg" alt="One JK candidate among thirteen nearby BLE devices" width="320"> | <img src="docs/screenshots/recovered-information.jpg" alt="Device information for a JK PB2A16S20P with passcodes hidden" width="320"> |

Other models and firmware may use a different protocol or field layout.

## Connection logs

Tap "View connection log" to see scan results, filter decisions, connection stages, GATT status codes, raw packets, checksums and field offsets. Use "Copy log" or "Save .txt" to export it.

The filter checks service `FFE0` and manufacturer ID `0B65` or `4B4A`, following the [aiobmsble JK matcher](https://github.com/patman15/aiobmsble/blob/main/aiobmsble/bms/jikong_bms.py). It also accepts JK names and labels devices that advertise only `FFE0` as possible matches.

See the [Android guide](android/README.md) for details.

## Build

Install JDK 17, Android SDK Platform 36 and Build Tools 35.0.0. Set `ANDROID_HOME` to the SDK directory, then run:

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Linux or macOS, use `./gradlew` instead of `.\gradlew.bat`. You can also open `android/` in Android Studio.

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

Version 1.0.2 passes 38 unit tests and Android lint with zero errors. The [protocol notes](docs/PORTABILITY.md) describe compatibility and testing limits.

## Credits and license

[MIT](LICENSE). Copyright 2026 SapkaLabs.

The Android app uses the device-information protocol documented by [ech0-py/jkbms_password_retrieve_tool](https://github.com/ech0-py/jkbms_password_retrieve_tool). This repository retains the original Python files and Git history. The upstream tool credits [fl4p/batmon-ha](https://github.com/fl4p/batmon-ha).

See [NOTICE.md](NOTICE.md) for credits and third-party licenses. The [original upstream README](docs/UPSTREAM_README.md) is kept for reference.
