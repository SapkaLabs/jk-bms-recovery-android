package org.jkbms.recovery;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Foreground-only BLE transport. All state and listener calls belong to the main thread. */
@SuppressLint("MissingPermission") // Activity gates entry; SecurityException covers revoked permissions.
public final class JkBleClient {
    private static final UUID CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public enum State { IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, READING }

    public static final class NearbyDevice {
        public final String name;
        public final String address;
        public final int rssi;
        public final JkAdvertisement advertisement;
        private final BluetoothDevice device;

        private NearbyDevice(BluetoothDevice device, JkAdvertisement advertisement, int rssi) {
            this.device = device;
            this.advertisement = advertisement;
            this.name = advertisement.name.isEmpty() ? "Unnamed BLE device" : advertisement.name;
            this.address = device.getAddress();
            this.rssi = rssi;
        }
    }

    public interface Listener {
        void onState(State state, String message);
        void onDevice(NearbyDevice device);
        void onInfo(JkProtocol.DeviceInfo info);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final DiagnosticLog log;
    private final Map<String, NearbyDevice> discovered = new LinkedHashMap<>();
    private int scanPackets;
    private int notifications;
    private int receivedBytes;
    private State state = State.IDLE;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private BluetoothGattCharacteristic writeCharacteristic;
    private JkProtocol.Decoder decoder = new JkProtocol.Decoder();
    private final Runnable scanTimeout = this::stopScan;
    private final Runnable operationTimeout = () -> {
        log(DiagnosticLog.Level.WARN, "timeout", "state=" + state + " notifications=" + notifications
                + " receivedBytes=" + receivedBytes + " buffered=" + decoder.bufferedBytes()
                + " checksumFailures=" + decoder.rejectedFrames());
        fail(state == State.READING
                    ? "No valid device information received. Firmware may be unsupported. Close other BMS apps and retry nearby."
                    : "Connection timed out. Move closer, close other BMS apps, then try again.");
    };

    public JkBleClient(Context context, Listener listener, DiagnosticLog log) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.log = log;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        log(DiagnosticLog.Level.INFO, "bluetooth", "adapterAvailable=" + (adapter != null));
    }

    public State state() { return state; }
    public boolean available() { return adapter != null; }
    public boolean enabled() { return adapter != null && adapter.isEnabled(); }

    private void transition(State next, String message) {
        log(DiagnosticLog.Level.INFO, "state", state + " -> " + next + ": " + message);
        state = next;
        listener.onState(next, message);
    }

    public void scan() {
        close();
        discovered.clear();
        scanPackets = 0;
        log(DiagnosticLog.Level.INFO, "scan", "Starting 12-second low-latency scan; advertisement matching runs in app; no MAC-vendor guesses.");
        try {
            scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                fail("Bluetooth is unavailable. Turn Bluetooth on and try again.");
                return;
            }
            scanCallback = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    main.post(() -> {
                        if (scanCallback != this || state != State.SCANNING) return;
                        try {
                            scanPackets++;
                            ScanRecord record = result.getScanRecord();
                            String name = record == null ? null : record.getDeviceName();
                            if (name == null) name = result.getDevice().getName();
                            Set<UUID> services = new HashSet<>();
                            Set<Integer> manufacturerIds = new HashSet<>();
                            if (record != null) {
                                if (record.getServiceUuids() != null) record.getServiceUuids().forEach(uuid -> services.add(uuid.getUuid()));
                                record.getServiceData().keySet().forEach(uuid -> services.add(uuid.getUuid()));
                                for (int i = 0; i < record.getManufacturerSpecificData().size(); i++) {
                                    manufacturerIds.add(record.getManufacturerSpecificData().keyAt(i));
                                }
                            }
                            JkAdvertisement evidence = new JkAdvertisement(name, services, manufacturerIds, result.isConnectable());
                            String address = result.getDevice().getAddress();
                            NearbyDevice previous = discovered.get(address);
                            if (previous != null) evidence = previous.advertisement.merge(evidence);
                            NearbyDevice found = new NearbyDevice(result.getDevice(), evidence, result.getRssi());
                            discovered.put(address, found);
                            if (previous == null || !previous.advertisement.sameEvidence(evidence)) {
                                log(DiagnosticLog.Level.DEBUG, "scan", "address=" + address + " name=" + found.name
                                        + " rssi=" + found.rssi + "dBm connectable=" + evidence.connectable
                                        + " match=" + evidence.match() + " services=" + evidence.services
                                        + " manufacturerIds=" + manufacturerIds(evidence.manufacturerIds));
                                if (record != null) log(DiagnosticLog.Level.DEBUG, "advertisement", address
                                        + " txPower=" + record.getTxPowerLevel() + " raw=" + DiagnosticLog.hex(record.getBytes()));
                            }
                            listener.onDevice(found);
                        } catch (SecurityException e) { permissionLost(e); }
                    });
                }

                @Override public void onScanFailed(int errorCode) {
                    main.post(() -> {
                        if (scanCallback == this) fail("Bluetooth scan failed (" + errorCode + "). Wait a moment, then scan again.");
                    });
                }
            };
            transition(State.SCANNING, "Looking for nearby devices for 12 seconds…");
            scanner.startScan(Collections.emptyList(), new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            main.postDelayed(scanTimeout, 12_000);
        } catch (SecurityException e) { permissionLost(e); }
        catch (IllegalStateException e) {
            logException("scan", e);
            fail("Bluetooth is off. Turn it on and scan again.");
        }
    }

    private void releaseScan() {
        main.removeCallbacks(scanTimeout);
        if (scanner != null && scanCallback != null) {
            log(DiagnosticLog.Level.INFO, "scan", "Stopping scan: packets=" + scanPackets + " uniqueDevices=" + discovered.size()
                    + " JKCandidates=" + discovered.values().stream().filter(device -> device.advertisement.match().candidate).count());
            try { scanner.stopScan(scanCallback); }
            catch (SecurityException | IllegalStateException ignored) { /* Already unavailable. */ }
        }
        scanner = null;
        scanCallback = null;
    }

    public void stopScan() {
        releaseScan();
        if (state == State.SCANNING) transition(State.IDLE, "Scan finished. Select your JK BMS below, or scan again.");
    }

    public void read(NearbyDevice device) {
        close();
        notifications = 0;
        receivedBytes = 0;
        decoder = new JkProtocol.Decoder((frame, calculated, expected) ->
                log(DiagnosticLog.Level.WARN, "checksum", "Rejected 300-byte frame calculated=" + code(calculated)
                        + " expected=" + code(expected) + " raw=" + DiagnosticLog.hex(frame)));
        log(DiagnosticLog.Level.INFO, "connect", "address=" + device.address + " name=" + device.name
                + " rssi=" + device.rssi + "dBm match=" + device.advertisement.match() + " autoConnect=false transport=LE timeout=25000ms");
        transition(State.CONNECTING, "Connecting to " + device.name + "…");
        try {
            gatt = device.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { fail("Could not start the Bluetooth connection. Try again."); return; }
            main.postDelayed(operationTimeout, 25_000);
        } catch (SecurityException e) { permissionLost(e); }
        catch (IllegalArgumentException e) { logException("connect", e); fail("The selected device could not be connected."); }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
            main.post(() -> {
                if (source != gatt) return;
                log(DiagnosticLog.Level.DEBUG, "gatt", "onConnectionStateChange status=" + code(status) + " newState=" + newState);
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Device disconnected (Bluetooth status " + status + "). Close other BMS apps and retry nearby.");
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && state == State.CONNECTING) {
                    transition(State.DISCOVERING, "Connected. Checking device services…");
                    try {
                        if (!source.discoverServices()) fail("Could not discover Bluetooth services. Try again.");
                    } catch (SecurityException e) { permissionLost(e); }
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
            main.post(() -> {
                if (source != gatt || state != State.DISCOVERING) return;
                log(DiagnosticLog.Level.DEBUG, "gatt", "onServicesDiscovered status=" + code(status) + " services=" + source.getServices().size());
                logServices(source);
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Bluetooth service discovery failed (" + status + ")."); return; }
                subscribe(source);
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if (source != gatt || state != State.SUBSCRIBING || !CCCD.equals(descriptor.getUuid())) return;
                log(DiagnosticLog.Level.DEBUG, "gatt", "onDescriptorWrite uuid=" + descriptor.getUuid() + " status=" + code(status));
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Could not subscribe to device responses (" + status + ")."); return; }
                requestInfo(source);
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            main.post(() -> {
                if (source == gatt) log(DiagnosticLog.Level.DEBUG, "gatt", "onCharacteristicWrite uuid=" + characteristic.getUuid()
                        + " instance=" + characteristic.getInstanceId() + " status=" + code(status));
                if (source == gatt && state == State.READING && status != BluetoothGatt.GATT_SUCCESS) {
                    fail("The device-information request failed (" + status + "). Try again.");
                }
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
            receive(source, characteristic, value.clone());
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) receive(source, characteristic, value.clone());
        }
    };

    private void subscribe(BluetoothGatt source) {
        // Select by properties, not UUID alone: some BMS devices expose duplicate FFE1s.
        for (BluetoothGattService service : source.getServices()) {
            BluetoothGattCharacteristic notify = null;
            BluetoothGattCharacteristic write = null;
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (!CHARACTERISTIC.equals(characteristic.getUuid())) continue;
                int properties = characteristic.getProperties();
                if ((properties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notify = characteristic;
                if ((properties & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) write = characteristic;
            }
            if (notify != null && write != null) {
                notifyCharacteristic = notify;
                writeCharacteristic = write;
                break;
            }
        }
        if (notifyCharacteristic == null || writeCharacteristic == null) {
            fail("Unsupported device: the JK BMS Bluetooth characteristic (FFE1) was not found with the required properties.");
            return;
        }
        BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD);
        if (descriptor == null) { fail("Unsupported device: no Bluetooth notification descriptor."); return; }
        transition(State.SUBSCRIBING, "Preparing to receive device information…");
        log(DiagnosticLog.Level.INFO, "gatt", "Selected notify=" + notifyCharacteristic.getUuid() + " instance=" + notifyCharacteristic.getInstanceId()
                + " write=" + writeCharacteristic.getUuid() + " instance=" + writeCharacteristic.getInstanceId());
        try {
            if (!source.setCharacteristicNotification(notifyCharacteristic, true)) {
                fail("Could not enable device notifications."); return;
            }
            boolean notify = (notifyCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            byte[] value = notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            log(DiagnosticLog.Level.DEBUG, "subscribe", "mode=" + (notify ? "notify" : "indicate") + " CCCD=" + DiagnosticLog.hex(value));
            if (!writeDescriptor(source, descriptor, value)) fail("Could not start the notification subscription.");
        } catch (SecurityException e) { permissionLost(e); }
    }

    @SuppressWarnings("deprecation")
    private boolean writeDescriptor(BluetoothGatt source, BluetoothGattDescriptor descriptor, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) return source.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
        descriptor.setValue(value);
        return source.writeDescriptor(descriptor);
    }

    @SuppressWarnings("deprecation")
    private void requestInfo(BluetoothGatt source) {
        transition(State.READING, "Reading device information…");
        main.removeCallbacks(operationTimeout);
        main.postDelayed(operationTimeout, 8_000);
        int type = (writeCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        byte[] command = JkProtocol.deviceInfoRequest();
        log(DiagnosticLog.Level.INFO, "tx", "command=0x97 bytes=" + command.length + " writeType=" + type
                + " timeout=8000ms raw=" + DiagnosticLog.hex(command));
        try {
            boolean started;
            if (Build.VERSION.SDK_INT >= 33) {
                started = source.writeCharacteristic(writeCharacteristic, command, type) == BluetoothStatusCodes.SUCCESS;
            } else {
                writeCharacteristic.setWriteType(type);
                writeCharacteristic.setValue(command);
                started = source.writeCharacteristic(writeCharacteristic);
            }
            if (!started) fail("Could not send the device-information request. Try again.");
        } catch (SecurityException e) { permissionLost(e); }
    }

    private void receive(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] bytes) {
        main.post(() -> {
            if (source != gatt) return;
            log(DiagnosticLog.Level.DEBUG, "rx", "uuid=" + characteristic.getUuid() + " instance=" + characteristic.getInstanceId()
                    + " state=" + state + " bytes=" + bytes.length + " raw=" + DiagnosticLog.hex(bytes));
            if (characteristic != notifyCharacteristic || state != State.READING) {
                log(DiagnosticLog.Level.DEBUG, "rx", "Ignored: not the active characteristic/read phase.");
                return;
            }
            notifications++;
            receivedBytes += bytes.length;
            java.util.List<byte[]> frames = decoder.accept(bytes);
            log(DiagnosticLog.Level.DEBUG, "framing", "notifications=" + notifications + " totalBytes=" + receivedBytes
                    + " buffered=" + decoder.bufferedBytes() + " discarded=" + decoder.discardedBytes()
                    + " checksumFailures=" + decoder.rejectedFrames() + " completeFrames=" + frames.size());
            for (byte[] frame : frames) {
                log(DiagnosticLog.Level.INFO, "frame", "bytes=" + frame.length + " type=" + code(frame[4] & 0xff)
                        + " counter=" + (frame[5] & 0xff) + " checksum=OK raw=" + DiagnosticLog.hex(frame));
                if (frame[4] != 3) {
                    log(DiagnosticLog.Level.DEBUG, "frame", "Ignored: waiting for device-information type 0x03.");
                    continue;
                }
                for (String field : JkProtocol.fieldDiagnostics(frame)) log(DiagnosticLog.Level.DEBUG, "field", field);
                try {
                    JkProtocol.DeviceInfo info = JkProtocol.parseDeviceInfo(frame);
                    log(DiagnosticLog.Level.INFO, "parse", "Device information decoded successfully; model=" + info.value(JkProtocol.Field.MODEL)
                            + " hardware=" + info.value(JkProtocol.Field.HARDWARE) + " firmware=" + info.value(JkProtocol.Field.FIRMWARE));
                    releaseConnection();
                    transition(State.IDLE, "Information read. Bluetooth disconnected.");
                    listener.onInfo(info);
                } catch (IllegalArgumentException e) {
                    logException("parse", e);
                    fail("Unsupported device information: " + e.getMessage());
                }
                return;
            }
        });
    }

    private void permissionLost(SecurityException error) {
        logException("permission", error);
        fail("Bluetooth permission is missing. Tap Scan for devices and allow Nearby devices access.");
    }

    private void fail(String message) {
        log(DiagnosticLog.Level.ERROR, "failure", "state=" + state + " " + message);
        releaseScan();
        releaseConnection();
        transition(State.IDLE, message);
    }

    private void releaseConnection() {
        main.removeCallbacks(operationTimeout);
        BluetoothGatt previous = gatt;
        gatt = null;
        notifyCharacteristic = null;
        writeCharacteristic = null;
        if (previous != null) {
            log(DiagnosticLog.Level.DEBUG, "gatt", "Disconnecting and closing active GATT client.");
            try { previous.disconnect(); }
            catch (SecurityException ignored) { /* close still releases the local client. */ }
            try { previous.close(); }
            catch (SecurityException ignored) { /* Permission may have been revoked while connected. */ }
        }
    }

    public void close() {
        releaseScan();
        releaseConnection();
        transition(State.IDLE, "Ready to scan.");
    }

    private void log(DiagnosticLog.Level level, String area, String message) { log.add(level, area, message); }

    private void logException(String area, Exception error) {
        log(DiagnosticLog.Level.ERROR, area, android.util.Log.getStackTraceString(error));
    }

    private static String code(int value) { return value + " (0x" + Integer.toHexString(value).toUpperCase(java.util.Locale.ROOT) + ")"; }

    private static String manufacturerIds(Set<Integer> ids) {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int id : new java.util.TreeSet<>(ids)) values.add(String.format(java.util.Locale.ROOT, "0x%04X", id));
        return values.toString();
    }

    private void logServices(BluetoothGatt source) {
        for (BluetoothGattService service : source.getServices()) {
            log(DiagnosticLog.Level.DEBUG, "service", "uuid=" + service.getUuid() + " instance=" + service.getInstanceId());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int p = characteristic.getProperties();
                log(DiagnosticLog.Level.DEBUG, "characteristic", "uuid=" + characteristic.getUuid() + " instance=" + characteristic.getInstanceId()
                        + " properties=" + code(p)
                        + " read=" + ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0)
                        + " write=" + ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                        + " writeNoResponse=" + ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                        + " notify=" + ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                        + " indicate=" + ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0));
                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) log(DiagnosticLog.Level.DEBUG, "descriptor", "uuid=" + descriptor.getUuid());
            }
        }
    }
}
