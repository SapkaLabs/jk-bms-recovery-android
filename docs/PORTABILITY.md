# Port assessment

Inspected on 2026-11-07. Upstream: https://github.com/ech0-py/jkbms_password_retrieve_tool

Source revision: `863d97cd5770676606145cbe892b1e4e0be84135`.

## Outcome

Android is feasible and a native Java Android application has been implemented in `../android`.
Windows is also feasible through Bleak's WinRT backend. The repository's statement to avoid Windows explicitly says Windows was not tested; the inspected default execution path does not contain a fundamental Linux/macOS-only dependency.

This is a source-level feasibility conclusion and a built Android port. The agent did not have direct access to a JK BMS. The user subsequently supplied screenshots showing successful discovery and decoded device metadata on a JK_PB2A16S20P, hardware 15A, firmware 15.41; see the hardware-evidence section below.

## How the source works

`jikong.py` calls Bleak discovery, accepts an address, connects, subscribes to `0000ffe1-0000-1000-8000-00805f9b34fb`, and requests device information. The outgoing packet is 20 bytes:

```text
AA 55 90 EB 97 00 00 00 00 00 00 00 00 00 00 00 00 00 00 11
```

It expects a response starting `55 AA EB 90`, with type `03` at byte 4. The checksum is the low 8 bits of the sum of bytes 0 through 298, stored at byte 299. Upstream tolerates a buffer of 300–320 bytes but validates only the first 300; the port decodes 300-byte frames and resynchronizes across any trailing bytes.

Passwords are returned as plaintext fields by compatible firmware. There is no cracking, password derivation, or reset algorithm to port. BLE connection permission/pairing is separate from these application passcodes.

The upstream sequential field widths produce these offsets (zero based):

| Field | Offset | Bytes |
| --- | ---: | ---: |
| Model | 6 | 16 |
| Hardware | 22 | 8 |
| Firmware | 30 | 8 |
| Device name | 46 | 16 |
| Device passcode | 62 | 16 |
| Manufacture code | 78 | 8 |
| Serial number | 86 | 11 |
| Passcode | 97 | 5 |
| Setup passcode | 118 | 16 |

Bytes 38–45 and 102–117 are skipped. Leading zeros in passwords are meaningful and remain strings.

## Windows

Bleak documents a native Windows backend implemented with Python/WinRT. The source's default CLI uses Bleak's cross-platform scan, connect, notify, and write APIs. The optional BlueZ version helper catches a missing `bluetoothctl`; it does not make Linux a requirement. Linux pairing-agent/adapter options are outside the default CLI path.

A Windows validation should use a fresh Python environment, a compatible Bleak version, a BLE-capable adapter and driver, and a real JK BMS. Upstream documents Python 3.9/3.10 with `bleak==0.20.2`; do not assume that every newer Bleak release preserves its connection keyword behavior. A maintained Windows port should pin a tested Python/Bleak pair, pass discovered device objects to the client, use current connection timeout APIs, and test both write modes against the actual device.

No Windows executable or tested Windows hardware recovery is claimed by this delivery. The Android port is the implemented application.

## Android implementation decisions

- Android 8.0+ (API 26), compiled/targeted against API 36, JDK 17, AGP 8.13.0 and Gradle 8.13.
- Native `BluetoothLeScanner`/`BluetoothGatt`; a Python runtime is unnecessary.
- Android 12+ Nearby devices scan/connect permissions; Android 8–11 Location permission and Location-service guidance.
- A finite connection state machine serializes service discovery, CCCD subscription and command write. Callbacks from old GATT sessions are ignored.
- Duplicate FFE1 characteristics are selected by properties within a service, matching the upstream intent while avoiding UUID-only writes.
- Only the information-read command is available; no settings-write commands exist in the app.
- Bounded framing handles split headers, 20-byte notifications, larger notifications, concatenated frames, noise, checksum failure and incomplete previous frames.
- Header, size, type and checksum are validated before exposing fields. Fixed-width strings stop at the first NUL byte. Metadata tolerates unusual text without blocking recovery; malformed passcode text is rejected to avoid silently changing a code.
- Screenshots and recording are allowed for every screen, as requested. Live passcodes are masked by default, are not persisted, and clear when the activity stops. Demo data is explicitly labelled.

## Source limitations addressed or retained

Upstream has no dependency manifest, automated tests, Android interface, or Windows-specific validation. Its notification handler can discard extra frames and assumes headers arrive at notification boundaries. Its future pool does not guard against a repeated `set_result` on a completed future. Those mechanisms were replaced in the Android port rather than copied.

The firmware-specific 300-byte layout is intentionally retained. A checksum confirms frame integrity, not device authenticity or a universal JK protocol version. A different firmware using the same size/type could change field meaning; validate returned values on your actual model before relying on them.

No `LICENSE` file was present in the inspected upstream revision. The Python source and upstream provenance remain intact. Redistribution licensing has not been established by this implementation.

## Validation

- Debug APK assembled successfully on Windows using the pinned wrapper and JDK 17.
- Version 1.0.1 has 20 JVM protocol tests, including all 301 possible two-part splits, 20-byte packets, randomized chunking, combined frames, malformed passcode text, leading zeros, full-width/empty passcodes, corruption and stream recovery. Metadata regression cases cover NUL termination, leftover bytes, control characters, malformed UTF-8 and Unicode names.
- Android lint completed with zero errors. Remaining warnings concern English text in Java, newer tool/target versions, a version-gated permission attribute and backup-rule recommendations. They do not constitute hardware validation.
- UI execution was attempted through the shared device tools. Two emulator commands returned `DEVICE_IN_USE` because their devices belonged to another workspace. Neither session was taken over. No successful emulator install/launch or UI smoke test is claimed.
- Real BLE discovery, GATT subscription, hardware recovery and Android 8–11 runtime permissions still need physical-device testing. Synthetic sample data tests the parser/UI code path only when run; it is not hardware evidence.

## Primary references

- [Inspected source](https://github.com/ech0-py/jkbms_password_retrieve_tool/tree/863d97cd5770676606145cbe892b1e4e0be84135)
- [Bleak Windows backend](https://bleak.readthedocs.io/en/stable/backends/windows.html)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android GATT discovery, notifications and data transfer](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)
- [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

## Version 1.0.1 device-name fix

The user reported a successful discovery/connection followed by `Unexpected text in Device name`. Two synthetic, checksummed responses reproduced that exact exception: a NUL-terminated name followed by unused nonzero bytes, and a name containing a non-NUL control byte. Both failed before the fix and passed after it. The actual device payload was not captured, so its precise byte pattern is unknown.

The previous parser stripped only trailing NUL bytes and rejected all control characters across all fields. The fix stops each fixed-width string at its first NUL byte, ignores bytes after that terminator, and formats unusual metadata without rejecting the device-information response. Passcode fields keep strict text validation. No frame-size, checksum, field-offset or BLE command changes were made.

All uses of `FLAG_SECURE` were removed from the activity. Screenshot blocking had been added without a user request; version 1.0.1 permits screenshots throughout the app. A physical BMS retest of this update is still needed.

## Version 1.0.2: discovery evidence and logs

Discovery now uses service UUID `FFE0` plus manufacturer ID `0x0B65`/`0x4B4A` as a strong signature, following the maintained [aiobmsble JK matcher](https://github.com/patman15/aiobmsble/blob/main/aiobmsble/bms/jikong_bms.py). JK-style names and an explicitly labelled FFE0-only fallback cover other advertisements. MAC vendor prefixes are not used as product identification. The official JK app was not reverse engineered; its exact discovery logic is unknown.

The default UI hides non-candidates, shows the reason for each match, and offers an all-devices override. Services and manufacturer identifiers observed in separate advertisement/scan-response packets are merged per address within each scan. Shared FFE0 services and name matches are heuristics; connecting still requires the protocol characteristic and a valid information response.

A bounded process-memory log captures scan evidence and filtering decisions, lifecycle/permissions, GATT callbacks and inventory, raw TX/RX/frame bytes, checksum and framing counters, raw field slices and parser exceptions. It can be viewed live, copied or explicitly saved through Android's document picker. Raw response logs can contain passcodes; they persist independently of the results view until cleared or the process exits. No Internet permission, background service, automatic file logging, Logcat logging or screenshot restriction was added.

Validation for 1.0.2 includes the previous parser tests plus discovery-classification tests (numeric/renamed devices, both manufacturer signatures, shared services, unrelated TVs, non-connectable adverts and evidence merging), bounded-log tests and parser-diagnostic regression tests. Runtime operation on a physical JK BMS remains unverified by the agent.

## User-supplied hardware evidence for publication

The user supplied two screenshots from version 1.0.2 showing one JK advertisement match among thirteen detected BLE devices, followed by recovered device information for `JK_PB2A16S20P`, hardware `15A`, firmware `15.41`. The shared result screenshot has passcodes masked and the optional passcode field is empty. This demonstrates user-reported discovery and device-information parsing on that hardware; it does not independently establish the correctness of the masked code values or compatibility with every JK firmware.

The original screenshots are published in `docs/screenshots/` and displayed in the repository README. No raw device logs or unmasked passcodes were supplied or published.
