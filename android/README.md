# JK BMS Recovery for Android

Native Android app for the BLE device-information protocol in
[ech0-py/jkbms_password_retrieve_tool](https://github.com/ech0-py/jkbms_password_retrieve_tool).
The original Python files remain unchanged in the parent directory.

## Install and use

1. Download `JK-BMS-Recovery-1.0.2-debug.apk` from [GitHub Releases](https://github.com/SapkaLabs/jk-bms-recovery-android/releases/latest) and copy it to an Android phone running Android 8.0 or later. A local build is also available under `../artifacts/` in the development checkout.
2. Open the APK and allow installation from that source if Android asks. This is a debug-signed local build, not a Play Store release.
3. Wake up your JK BMS and close the manufacturer's app and any other app connected to it.
4. Open **JK BMS Recovery**, tap **Scan for devices**, and grant the requested Bluetooth permissions.
5. On Android 11 and earlier, also allow Location and turn the phone's Location service on. Android requires these for BLE scanning on those versions. The app does not collect location.
6. The list initially shows JK candidates, with their match reasons. Enable **Show all BLE devices** if your BMS is missing. Select your BMS by its advertised name/address to start the information read.
7. After a successful read, scroll to **Recovered information** and turn on **Show passcodes**.

**Try sample results** works without a BMS. It shows explicitly labelled synthetic data and does not validate your hardware.

## What it does

- Scans nearby BLE devices for 12 seconds and shows likely JK devices by default. A switch reveals all detected devices without rescanning. Each row explains why it matched.
- Uses the FFE1 characteristic, selecting notification and write characteristics by their properties.
- Enables notifications or indications, waits for subscription confirmation, then sends the 20-byte `0x97` information request.
- Reassembles notifications, checks the 300-byte response checksum, and accepts device-information response type `0x03`.
- Displays model, hardware/firmware, device name, manufacture code, serial number, and the three passcode fields present in the upstream layout.
- Disconnects after success, failure, cancellation, or leaving the foreground.

This reads the information exposed by compatible firmware. It does not calculate unknown codes, brute-force passwords, reset a BMS, or change its battery settings. Empty password fields appear as **Not provided**.

## Privacy

The app has no Internet permission, analytics, background logging, or automatic clipboard copying. Diagnostic logs stay in app-process memory; saving a log writes only to a document you select. Passcodes are hidden until revealed. Screenshots and screen recording are allowed, including live results. Results are removed from the UI when the activity stops, including rotation or leaving the app; Android view-state saving is disabled for the result fields. The connection log retains raw response bytes, including passcodes, until you clear it or the app process exits. Copied/saved logs remain in the clipboard or selected document. Values live in managed memory until garbage collection; this is not a memory-zeroization guarantee.

## Build

Use JDK 17, Android SDK Platform 36 and Build Tools 35.0.0. Set `ANDROID_HOME` to your SDK, or create a gitignored `local.properties` with `sdk.dir`.

From this directory on Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Linux/macOS:

```sh
sh gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The wrapper downloads Gradle 8.13 and verifies its distribution checksum. Android Gradle Plugin is pinned to 8.13.0. There are no third-party app runtime dependencies; local tests use JUnit 4.13.2.

Build output: `app/build/outputs/apk/debug/app-debug.apk`.
Test report: `app/build/reports/tests/testDebugUnitTest/index.html`.
Lint report: `app/build/reports/lint-results-debug.html`.

Open this `android` directory in Android Studio to develop the app. Package ID: `org.jkbms.recovery`.

## Compatibility and troubleshooting

- The known upstream example is `JK_B2A24S15P`, hardware `11.XW`, firmware `11.26`. This is an upstream example, not a hardware test performed for this port.
- Other JK devices may use a different frame size, characteristic, encoding, encrypted transport, or password layout. Such firmware needs a protocol extension based on an actual capture/specification.
- **Nothing found:** wake the BMS, move closer, check Bluetooth and permissions, and close competing BMS apps. On older Android versions, check Location too.
- **Disconnected or timed out:** another app may hold the BMS connection. Close it, wait briefly, then rescan. No automatic retry loop runs against the device.
- **Unsupported device:** verify that you selected the JK BMS. This app requires notification/indication and write FFE1 characteristics within one GATT service.
- **No valid response:** the app accepts only checksummed 300-byte frames. A newer/different firmware layout is not treated as a recovered password.
- **Text fields:** fields end at their first NUL byte. Unused bytes after the terminator are ignored. Device metadata tolerates malformed UTF-8 (shown as a replacement character) and displays control characters as visible escapes such as `\u0001`; an unusual device name does not block passcode recovery. Passcode text remains strict to avoid silently changing a recovered code.

See [the port assessment](../docs/PORTABILITY.md) for source findings and the limits of validation.

## Version 1.0.1

- Removed the screenshot and recording restriction.
- Fixed `Unexpected text in Device name` by handling NUL-terminated fields and tolerating unusual metadata bytes.
- Added regression tests for NUL termination, leftover bytes, control characters, malformed metadata and Unicode names.

Install the 1.0.1 APK over 1.0.0; the application ID and debug signing key are unchanged.

## Version 1.0.2: device filtering and diagnostic logs

JK candidates are shown by default using these advertisement signals:

- **JK advertisement signature:** a connectable device advertises service `FFE0` plus manufacturer ID `0B65` or `4B4A`, matching the maintained [aiobmsble JK driver's discovery rule](https://github.com/patman15/aiobmsble/blob/main/aiobmsble/bms/jikong_bms.py).
- **JK device name:** a connectable device advertises a JK-style name such as `JK_...`, `JK-...`, `JKBMS...` or `Jikong...`.
- **Possible JK: FFE0 service:** a connectable device advertises `FFE0` without the known manufacturer IDs. This is a fallback for renamed devices; other products can use that service too.

The app combines service/name/manufacturer evidence across packets from the same address during a scan, so a sparse scan response does not erase an earlier match. Non-connectable advertisements and unrelated devices are hidden by default. **Show all BLE devices** reveals them; non-connectable rows are disabled. The counters show how many candidates, total devices and hidden devices were detected.

A MAC prefix identifies a Bluetooth module vendor rather than the JK protocol, so the app does not guess device type from the MAC. Advertisement matching is a discovery aid; the actual protocol and checksum are checked after selecting a device. No claim is made that this reproduces the official app's private filtering algorithm.

Tap **View connection log** below the status box at any time. The viewer updates while scans and connections run, and includes:

- App version, Android version, phone model, UTC timestamps and elapsed milliseconds.
- Permission/Location/Bluetooth-enable outcomes and lifecycle/cancellation events.
- Each first-seen or changed device advertisement: address, name, RSSI, connectability, service UUIDs, manufacturer IDs, raw advertising bytes and match reason. Unchanged repeated advertisements are counted rather than dumped repeatedly.
- Scan packet totals, unique devices and JK candidate counts.
- Connection state transitions, numeric/hex GATT status codes, service/characteristic/descriptor inventory and characteristic properties/instance IDs.
- Notification subscription mode and CCCD write result.
- The transmitted command and raw received notification bytes.
- Complete frames, frame types/counters, checksum failures and rejected frame bytes, buffered/discarded byte counts.
- Every device-information field's offset, width, first NUL position and raw bytes, including when text parsing fails.
- Parser exceptions and successful model/hardware/firmware decoding.

**Copy log** puts the current log on the clipboard. **Save .txt** opens Android's document picker; choose a destination to keep it. Logs include raw responses and can contain recovered passcodes. **Clear log** removes the in-memory history. The log is bounded to 1,500 entries and 300,000 characters, with a notice if older entries were removed. It survives activity recreation/leaving the foreground while the process remains alive. It is not automatically written to disk or sent to Logcat or a server.

Install the 1.0.2 APK over the existing version; the application ID and signing key are unchanged. Screenshots remain allowed on all screens.

## User-supplied hardware evidence

The [repository screenshots](../readme.md#screenshots) show discovery and recovered metadata on `JK_PB2A16S20P`, hardware `15A`, firmware `15.41`. Passcodes are masked in the screenshots. This is user-reported hardware evidence; the agent did not directly operate that BMS.
