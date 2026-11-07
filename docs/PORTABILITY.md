# Protocol and compatibility

The Android app uses the device-information format in
[ech0-py/jkbms_password_retrieve_tool](https://github.com/ech0-py/jkbms_password_retrieve_tool/tree/863d97cd5770676606145cbe892b1e4e0be84135).
The inspected revision is `863d97cd5770676606145cbe892b1e4e0be84135`.

## Platform support

The repository contains a native Java app for Android 8.0 and later.
A user supplied screenshots of discovery and recovered information on a `JK_PB2A16S20P` with hardware `15A` and firmware `15.41`.

A Windows port is feasible through [Bleak's WinRT backend](https://bleak.readthedocs.io/en/stable/backends/windows.html). The original README says Windows was not tested. Its normal command-line path uses Bleak's cross-platform scan, connect, notify and write APIs. The optional BlueZ version helper catches a missing `bluetoothctl`.

No Windows executable has been built or tested for this project. The upstream instructions use Python 3.9 or 3.10 and `bleak==0.20.2`. A Windows port needs a tested Python and Bleak version pair, a BLE adapter and a real BMS. Newer Bleak versions may handle the source's connection arguments differently.

## Device-information request

The original `jikong.py` scans for devices, accepts an address, connects and subscribes to characteristic `0000ffe1-0000-1000-8000-00805f9b34fb`. It sends this 20-byte request:

```text
AA 55 90 EB 97 00 00 00 00 00 00 00 00 00 00 00 00 00 00 11
```

The command byte is `0x97`. The expected response starts with `55 AA EB 90` and has type `03` at byte 4. Byte 299 holds the low eight bits of the sum of bytes 0 through 298.

The original code accepts buffers of 300 through 320 bytes but checks only the first 300. The Android parser reads 300-byte frames and searches for the next header after extra or damaged bytes.

Compatible firmware returns the passcodes as text fields. The app reads these values. Bluetooth pairing and connection permissions are separate from the application passcodes.

## Field layout

Offsets start at zero. Each field has a fixed width.

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

The parser skips bytes 38 through 45 and 102 through 117. It keeps passcodes as strings to preserve leading zeros.

A valid checksum confirms the bytes arrived intact. It does not confirm the device model or field layout. Firmware that changes the layout needs a matching parser update.

## Android connection flow

The app scans with `BluetoothLeScanner` and connects with `BluetoothGatt`. Android 12 and later use Nearby devices permissions. Android 8 through 11 need Location permission and the Location service for scanning.

The connection states run in sequence. The app discovers services, selects FFE1 characteristics by their properties, enables notifications or indications, waits for the descriptor write to finish, then sends the request. This handles devices with separate notification and write characteristics that share a UUID. The app ignores callbacks from closed connections.

The decoder has a bounded buffer. It handles split headers, 20-byte notifications, larger notifications, combined frames, noise, failed checksums and truncated earlier frames. The app sends only the information-read command.

The original Python tool has no dependency manifest or automated tests. Its notification handler can discard extra frames and expects headers at notification boundaries. Its future pool can set a result twice on the same future. The Android app replaces those parts with its connection state machine and stream decoder.

## Device-name parsing fix

A user reported `Unexpected text in Device name` after connecting. Two synthetic responses with valid checksums reproduced that error. One had a NUL-terminated name followed by unused nonzero bytes. The other had a control byte inside the name.

Version 1.0.1 stops each field at its first NUL byte. It replaces malformed UTF-8 in metadata and shows control characters as escapes. Passcode fields keep strict text checks. Both regression cases pass after the fix. The original device packet was not captured, so its exact byte pattern is unknown.

Version 1.0.1 also removed the screenshot restriction. Screenshots and recording remain available on every screen.

## Discovery and logs

Version 1.0.2 checks service `FFE0` with manufacturer ID `0B65` or `4B4A`, following the [aiobmsble JK matcher](https://github.com/patman15/aiobmsble/blob/main/aiobmsble/bms/jikong_bms.py). JK names and labelled FFE0-only devices provide fallbacks. A switch shows all detected devices.

The app combines service and manufacturer data from packets with the same address during each scan. It shows the reason for each match. Advertisement matches help narrow the list, but the app still checks the characteristic and information response after connecting. The official JK app's filtering code has not been inspected.

The in-memory log records advertisements, filter decisions, permissions, GATT callbacks, raw packets, checksums, field slices and parser errors. It holds up to 1,500 entries and 300,000 characters. Users can copy it or save it through Android's document picker. Raw logs can contain passcodes.

## Validation

Version 1.0.2 builds on Windows and passes 38 unit tests. Android lint reports zero errors. The tests cover:

- All 301 two-part splits of a response, 20-byte packets, random chunk sizes and combined frames.
- Noise, checksum failures, truncated frames and decoder recovery.
- Leading zeros, empty and full-width passcodes, NUL termination, unusual metadata and Unicode names.
- JK names, both manufacturer signatures, shared services, unrelated devices and advertisement merging.
- Log limits, escaped control characters, checksum diagnostics and field dumps after a parser error.

Earlier emulator attempts stopped with `DEVICE_IN_USE` because other sessions owned the devices. No emulator smoke test has completed. Android 8 through 11 permission handling still needs a device test.

The supplied screenshots show version 1.0.2 finding one JK device among thirteen nearby BLE devices. The result screen shows `JK_PB2A16S20P`, hardware `15A`, firmware `15.41`. The passcodes are hidden and the optional passcode field is empty. The screenshots confirm discovery and metadata parsing on that hardware. They do not verify the hidden code values or other firmware versions.

The screenshots are in `docs/screenshots/`. No unmasked passcodes or raw device logs were supplied for publication.

## References

- [Original Python tool](https://github.com/ech0-py/jkbms_password_retrieve_tool/tree/863d97cd5770676606145cbe892b1e4e0be84135)
- [Bleak Windows backend](https://bleak.readthedocs.io/en/stable/backends/windows.html)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android GATT discovery and data transfer](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)
- [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

## License

The project uses the [MIT License](../LICENSE). [NOTICE.md](../NOTICE.md) lists credits and third-party licenses.
