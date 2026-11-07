# Android guide

JK BMS Recovery reads device information and access codes over Bluetooth Low Energy.
It supports the information format used by the [original Python tool](https://github.com/ech0-py/jkbms_password_retrieve_tool).

## Install and use

1. Download `JK-BMS-Recovery-1.0.2-debug.apk` from [GitHub Releases](https://github.com/SapkaLabs/jk-bms-recovery-android/releases/latest).
2. Open the APK on a phone with Android 8.0 or later. Allow installation from that source if Android asks.
3. Wake the BMS and close other apps connected to it.
4. Tap "Scan for devices" and allow Bluetooth access.
5. On Android 11 and earlier, also allow Location and turn on the Location service. Android requires these for Bluetooth scanning on those versions.
6. Select the BMS by its name and address. Turn on "Show all BLE devices" if it is missing.
7. After the information appears, turn on "Show passcodes".

The APK uses the same debug signing key as the earlier local builds. You can install it over them.
"Try sample results" shows demo data without connecting to a device.

## Device filtering

A scan runs for 12 seconds. The default list includes connectable devices that meet one of these conditions:

- The device advertises service `FFE0` and manufacturer ID `0B65` or `4B4A`. This follows the [aiobmsble JK matcher](https://github.com/patman15/aiobmsble/blob/main/aiobmsble/bms/jikong_bms.py).
- The name starts with a JK form such as `JK_`, `JK-`, `JKBMS` or `Jikong`. The filter also accepts `JK` and names that start with `JK BMS`.
- The device advertises `FFE0` without a known manufacturer ID. The app labels it as a possible match because other products use this service too.

The app combines advertisement data from packets with the same address during a scan. Each row shows its match reason. The counters show the candidate count, total device count and hidden device count.

"Show all BLE devices" reveals the other results without another scan. Devices that do not accept connections have disabled rows.

The app checks advertisement data rather than guessing from a MAC vendor prefix. The official JK app's filtering code has not been inspected. A match in the list does not confirm protocol compatibility.

## What the app reads

The app enables notifications or indications on characteristic `FFE1`. After the subscription succeeds, it sends the 20-byte `0x97` device-information request. It then checks the response header, type and checksum before reading the fields.

The results include the model, hardware, firmware, device name, manufacture code, serial number and available passcodes. Empty passcode fields show "Not provided".

The app disconnects after the read, an error, cancellation or leaving the foreground. It sends no battery-setting or password-reset commands.

## Connection logs

Tap "View connection log" below the status box. The log updates during scans and connections. It records:

- The app version, Android version, phone model, UTC timestamps and elapsed milliseconds.
- Permission results, Bluetooth state, Location checks and cancellation.
- Device names, addresses, signal strength, service UUIDs, manufacturer IDs, raw advertisements and match reasons.
- Scan packet totals and device counts. The log counts repeated advertisements with unchanged identity data instead of printing each one.
- Connection stages, GATT status codes, services, characteristics, descriptors and characteristic properties.
- Subscription results, the sent command and raw response bytes.
- Frame types, checksums, rejected frames and buffered or discarded byte counts.
- Field offsets, widths, first NUL positions and raw bytes, even if text parsing fails.
- Parser errors and the decoded model, hardware and firmware.

"Copy log" copies the current log. "Save .txt" opens Android's document picker. "Clear log" removes the in-memory history.

The log holds up to 1,500 entries and 300,000 characters. It reports when the limit removes older entries. It survives activity recreation while the app process remains alive. It can contain passcodes in raw responses.

## Data and screenshots

The app has no Internet permission, analytics, background service or automatic clipboard copying. It keeps logs in memory and writes a file only when you choose "Save .txt".

The app hides passcodes until you reveal them. It allows screenshots and recording on every screen.
Results clear when the activity stops, including rotation or leaving the app. Logs remain until you clear them or the process exits. Copied and saved logs remain in the clipboard or selected file.

## Build

Use JDK 17, Android SDK Platform 36 and Build Tools 35.0.0. Set `ANDROID_HOME` to the SDK directory, or set `sdk.dir` in a local `local.properties` file.

Run from this directory on Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Linux or macOS:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The project pins Gradle 8.13 and Android Gradle Plugin 8.13.0. The wrapper checks the Gradle download checksum. The app has no third-party runtime dependencies. Tests use JUnit 4.13.2.

| Output | Path |
| --- | --- |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Test report | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint report | `app/build/reports/lint-results-debug.html` |

You can open this directory in Android Studio. The application ID is `org.jkbms.recovery`.

## Troubleshooting

If no device appears, wake the BMS, move closer and close other BMS apps. Check Bluetooth and permissions. On Android 11 and earlier, also check Location. Try "Show all BLE devices" for a custom name or unusual advertisement.

If the connection disconnects or times out, close any other app connected to the BMS. Wait briefly, then scan again.

An unsupported-device error means the app could not find notification or indication and write characteristics with UUID `FFE1` in one service. Check that you selected the BMS.

The app accepts 300-byte responses with a valid checksum. Different firmware may use another size, characteristic or field layout. See the [protocol notes](../docs/PORTABILITY.md).

Text fields end at the first NUL byte. The parser ignores unused bytes after it. For device metadata, it replaces malformed UTF-8 and shows control characters as escapes such as `\u0001`. Passcode text uses stricter checks so the parser does not silently change a code.

## Tested hardware

The [screenshots](../readme.md#screenshots) show discovery and recovered information on a `JK_PB2A16S20P` with hardware `15A` and firmware `15.41`. The user supplied these screenshots with passcodes hidden.

The upstream tool also lists `JK_B2A24S15P`, hardware `11.XW`, firmware `11.26`. That example comes from its README.

## Version history

Version 1.0.1 removed screenshot blocking and fixed device-name parsing for NUL termination and unusual metadata bytes.

Version 1.0.2 added device filtering and connection logs. It passes 38 unit tests and Android lint with zero errors.

## License

[MIT](../LICENSE). See [NOTICE.md](../NOTICE.md) for credits and third-party licenses.
