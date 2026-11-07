package org.jkbms.recovery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends Activity implements JkBleClient.Listener {
    private static final int PERMISSIONS = 10;
    private static final int ENABLE_BLUETOOTH = 11;
    private static final int SAVE_LOG = 12;
    private static final int INK = Color.rgb(24, 43, 68);
    private static final int MUTED = Color.rgb(80, 99, 119);
    private static final int BLUE = Color.rgb(25, 85, 165);
    private final Map<String, JkBleClient.NearbyDevice> devices = new LinkedHashMap<>();
    private final Map<String, Button> deviceButtons = new LinkedHashMap<>();
    private JkBleClient client;
    private DiagnosticLog log;
    private TextView status;
    private TextView empty;
    private ProgressBar progress;
    private Button scan;
    private Button cancel;
    private Button sample;
    private LinearLayout deviceList;
    private LinearLayout result;
    private Switch showAll;
    private TextView deviceCount;
    private Button logButton;
    private AlertDialog logDialog;
    private TextView logView;
    private ScrollView logScroll;
    private String pendingLogExport;
    private boolean logRefreshScheduled;
    private final Handler logHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshLog = this::refreshLogView;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log = ((RecoveryApplication) getApplication()).log();
        client = new JkBleClient(this, this, log);
        log.add(DiagnosticLog.Level.INFO, "app", "Activity created; default filter=JK candidates.");
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(242, 245, 248));
        LinearLayout root = column();
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setSaveFromParentEnabled(false);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom);
            } else {
                root.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(24) + insets.getSystemWindowInsetTop(),
                        dp(24) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(scroll);

        TextView brand = text("JK BMS Recovery", 16, BLUE);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(brand);
        TextView title = text("Your battery.\nYour access.", 34, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(root, title, 16);
        add(root, text("Read the access codes stored on your JK battery management system over Bluetooth.", 16, MUTED), 12);

        LinearLayout note = column();
        note.setPadding(dp(16), dp(14), dp(16), dp(14));
        note.setBackground(background(Color.WHITE, 8));
        TextView noteTitle = text("Read-only recovery", 16, INK);
        noteTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        note.addView(noteTitle);
        add(note, text("Use with your own BMS. Keep it nearby and close other BMS apps before connecting. Battery settings stay unchanged.", 14, MUTED), 6);
        add(root, note, 24);

        add(root, heading("Find your BMS"), 28);
        showAll = new Switch(this);
        showAll.setText("Show all BLE devices");
        showAll.setTextSize(16);
        showAll.setTextColor(INK);
        showAll.setMinHeight(dp(48));
        showAll.setOnCheckedChangeListener((view, all) -> {
            log.add(DiagnosticLog.Level.INFO, "filter", all ? "Showing all BLE devices." : "Showing JK candidates only.");
            updateDeviceVisibility();
        });
        add(root, showAll, 8);
        add(root, text("JK names and advertisement signatures are shown by default. Enable all devices if your BMS has a custom name.", 13, MUTED), 4);
        deviceCount = text("No devices detected yet.", 13, MUTED);
        add(root, deviceCount, 8);
        scan = button("Scan for devices", true);
        scan.setOnClickListener(view -> {
            if (client.state() == JkBleClient.State.SCANNING) client.stopScan();
            else beginScan();
        });
        add(root, scan, 12);
        sample = button("Try sample results", false);
        sample.setOnClickListener(view -> {
            log.add(DiagnosticLog.Level.INFO, "ui", "Sample results requested; no hardware connection.");
            client.close();
            showInfo(JkProtocol.sample(), true);
            status.setText("Sample results only. No device was connected.");
        });
        add(root, sample, 6);

        LinearLayout statusBox = column();
        statusBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        statusBox.setBackground(background(Color.rgb(226, 235, 247), 8));
        status = text("Ready. Scan to find your nearby JK BMS.", 14, INK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusBox.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        add(statusBox, progress, 6);
        cancel = button("Cancel connection", false);
        cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(view -> {
            log.add(DiagnosticLog.Level.INFO, "ui", "Connection cancelled by user.");
            client.close();
        });
        statusBox.addView(cancel);
        add(root, statusBox, 16);
        logButton = button("View connection log", false);
        logButton.setOnClickListener(view -> showLog());
        add(root, logButton, 8);

        empty = text("Nearby devices will appear here. Select your JK BMS to read its information.", 14, MUTED);
        add(root, empty, 16);
        deviceList = column();
        add(root, deviceList, 8);
        result = column();
        add(root, result, 20);
        add(root, text("Compatibility depends on BMS firmware. This app supports the device-information format used by the original JK retrieval tool. Results clear when you leave the app.", 12, MUTED), 24);
    }

    private String[] requiredPermissions() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void beginScan() {
        log.add(DiagnosticLog.Level.INFO, "ui", "Scan requested; permissionsGranted=" + hasPermissions());
        clearInfo();
        if (!client.available()) { status.setText("This device has no Bluetooth adapter. Try this app on an Android phone with Bluetooth LE."); return; }
        if (!hasPermissions()) {
            log.add(DiagnosticLog.Level.INFO, "permission", "Requesting " + java.util.Arrays.toString(requiredPermissions()));
            requestPermissions(requiredPermissions(), PERMISSIONS);
            return;
        }
        try {
            if (!client.enabled()) {
                log.add(DiagnosticLog.Level.INFO, "bluetooth", "Adapter disabled; requesting Bluetooth enable dialog.");
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH);
                return;
            }
        } catch (SecurityException e) {
            log.add(DiagnosticLog.Level.ERROR, "permission", e.toString());
            status.setText("Bluetooth permission is missing. Allow Nearby devices in the app settings.");
            return;
        }
        if (Build.VERSION.SDK_INT <= 30) {
            LocationManager location = getSystemService(LocationManager.class);
            boolean enabled = Build.VERSION.SDK_INT >= 28 ? location != null && location.isLocationEnabled()
                    : Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != 0;
            if (!enabled) {
                log.add(DiagnosticLog.Level.WARN, "location", "Location services disabled on API " + Build.VERSION.SDK_INT + "; scan cannot start.");
                status.setText("On Android 11 and earlier, Bluetooth scanning needs Location to be turned on. Enable it, then scan again.");
                new AlertDialog.Builder(this).setTitle("Enable Location for scanning")
                        .setMessage("This Android version requires Location for Bluetooth discovery. The app does not collect your location.")
                        .setPositiveButton("Open Location settings", (dialog, which) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton("Cancel", null).show();
                return;
            }
        }
        devices.clear();
        deviceButtons.clear();
        deviceList.removeAllViews();
        deviceCount.setText("No devices detected yet.");
        empty.setVisibility(View.VISIBLE);
        empty.setText("Scanning… If your BMS does not appear, wake it up and close any other app connected to it.");
        client.scan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSIONS) return;
        log.add(DiagnosticLog.Level.INFO, "permission", "result permissions=" + java.util.Arrays.toString(permissions)
                + " grantResults=" + java.util.Arrays.toString(results) + " allGranted=" + hasPermissions());
        if (hasPermissions()) beginScan();
        else {
            status.setText("Permission was denied. Allow Bluetooth access to scan and connect.");
            new AlertDialog.Builder(this).setTitle("Bluetooth access needed")
                    .setMessage(Build.VERSION.SDK_INT >= 31
                            ? "Allow Nearby devices in app settings, then return and scan again."
                            : "Allow Location in app settings so this Android version can scan for Bluetooth devices.")
                    .setPositiveButton("App settings", (dialog, which) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Cancel", null).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_LOG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingLogExport != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (output == null) throw new IOException("The selected document could not be opened.");
                    output.write(pendingLogExport.getBytes(StandardCharsets.UTF_8));
                    log.add(DiagnosticLog.Level.INFO, "export", "Log saved to the selected document.");
                    Toast.makeText(this, "Log saved", Toast.LENGTH_SHORT).show();
                } catch (IOException | SecurityException e) {
                    log.add(DiagnosticLog.Level.ERROR, "export", e.toString());
                    Toast.makeText(this, "Could not save log: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingLogExport = null;
            return;
        }
        if (requestCode == ENABLE_BLUETOOTH) {
            log.add(DiagnosticLog.Level.INFO, "bluetooth", "Enable dialog result=" + resultCode);
            if (resultCode == RESULT_OK) beginScan();
            else status.setText("Bluetooth remains off. Turn it on to find your BMS.");
        }
    }

    @Override public void onState(JkBleClient.State state, String message) {
        if (status == null) return;
        status.setText(message);
        boolean connecting = state != JkBleClient.State.IDLE && state != JkBleClient.State.SCANNING;
        scan.setEnabled(!connecting);
        scan.setText(state == JkBleClient.State.SCANNING ? "Stop scan" : "Scan for devices");
        sample.setEnabled(!connecting);
        cancel.setVisibility(connecting ? View.VISIBLE : View.GONE);
        progress.setVisibility(state == JkBleClient.State.IDLE ? View.GONE : View.VISIBLE);
        updateDeviceVisibility();
    }

    @Override public void onDevice(JkBleClient.NearbyDevice device) {
        devices.put(device.address, device);
        Button row = deviceButtons.get(device.address);
        if (row == null) {
            row = button("", false);
            row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(background(Color.WHITE, 8));
            row.setOnClickListener(view -> {
                clearInfo();
                client.read(devices.get(device.address));
            });
            deviceButtons.put(device.address, row);
            add(deviceList, row, 8);
        }
        row.setText(device.name + "\n" + device.address + "  /  " + device.rssi + " dBm\n" + device.advertisement.match().description);
        row.setContentDescription("Read information from " + device.name + ", " + device.address);
        updateDeviceVisibility();
    }

    private void updateDeviceVisibility() {
        if (deviceList == null) return;
        int candidates = 0;
        int visible = 0;
        boolean busy = client.state() != JkBleClient.State.IDLE && client.state() != JkBleClient.State.SCANNING;
        for (Map.Entry<String, Button> entry : deviceButtons.entrySet()) {
            JkBleClient.NearbyDevice device = devices.get(entry.getKey());
            boolean candidate = device.advertisement.match().candidate;
            if (candidate) candidates++;
            boolean shown = showAll.isChecked() || candidate;
            entry.getValue().setVisibility(shown ? View.VISIBLE : View.GONE);
            entry.getValue().setEnabled(!busy && device.advertisement.connectable);
            if (shown) visible++;
        }
        deviceCount.setText(candidates + " JK candidates / " + devices.size() + " devices detected / " + (devices.size() - visible) + " hidden");
        empty.setVisibility(visible == 0 ? View.VISIBLE : View.GONE);
        if (visible == 0) empty.setText(showAll.isChecked()
                ? "No devices listed yet. Wake your BMS, move closer and scan again."
                : "No JK candidates listed yet. Wake your BMS and scan, or enable Show all BLE devices for an unusual name or advertisement.");
    }

    private void showLog() {
        LinearLayout panel = column();
        panel.setPadding(dp(16), 0, dp(16), 0);
        panel.addView(text("Includes MAC addresses and raw responses that may contain passcodes. Copy or save to keep the log after the app process exits.", 13, MUTED));
        Button clearLog = button("Clear log", false);
        clearLog.setOnClickListener(view -> {
            log.clear();
            log.add(DiagnosticLog.Level.INFO, "app", "Log cleared by user.");
        });
        add(panel, clearLog, 8);
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.WHITE);
        logView = text(log.snapshot(), 12, INK);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setSaveEnabled(false);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logScroll.addView(logView);
        panel.addView(logScroll, new LinearLayout.LayoutParams(-1, dp(300)));
        logDialog = new AlertDialog.Builder(this).setTitle("Connection log")
                .setView(panel).setPositiveButton("Copy log", null)
                .setNeutralButton("Save .txt", (dialog, which) -> exportLog())
                .setNegativeButton("Close", null).create();
        logDialog.setOnShowListener(dialog -> logDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("JK BMS connection log", log.snapshot()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        }));
        logDialog.setOnDismissListener(dialog -> {
            logDialog = null;
            logView = null;
            logScroll = null;
        });
        logDialog.show();
    }

    private void exportLog() {
        pendingLogExport = log.snapshot();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "jkbms-log-" + System.currentTimeMillis() + ".txt");
        try { startActivityForResult(intent, SAVE_LOG); }
        catch (android.content.ActivityNotFoundException e) {
            pendingLogExport = null;
            log.add(DiagnosticLog.Level.ERROR, "export", "No document picker available: " + e);
            Toast.makeText(this, "No document picker available. Use Copy log instead.", Toast.LENGTH_LONG).show();
        }
    }

    private void scheduleLogRefresh() {
        if (!logRefreshScheduled) {
            logRefreshScheduled = true;
            logHandler.postDelayed(refreshLog, 250);
        }
    }

    private void refreshLogView() {
        logRefreshScheduled = false;
        logButton.setText("View connection log (" + log.size() + ")");
        if (logView == null || logScroll == null) return;
        int y = logScroll.getScrollY();
        boolean atBottom = logView.getHeight() - y - logScroll.getHeight() <= dp(24);
        logView.setText(log.snapshot());
        ScrollView current = logScroll;
        current.post(() -> { if (atBottom) current.fullScroll(View.FOCUS_DOWN); else current.scrollTo(0, y); });
    }

    @Override public void onInfo(JkProtocol.DeviceInfo info) { showInfo(info, false); }

    private void showInfo(JkProtocol.DeviceInfo info, boolean demo) {
        clearInfo();
        result.addView(heading(demo ? "Sample results" : "Recovered information"));
        if (demo) add(result, text("Demo data. These codes were not read from your battery.", 14, BLUE), 8);
        Switch reveal = new Switch(this);
        reveal.setText("Show passcodes");
        reveal.setTextColor(INK);
        reveal.setTextSize(16);
        reveal.setMinHeight(dp(48));
        reveal.setSaveEnabled(false);
        add(result, reveal, 12);
        Map<JkProtocol.Field, TextView> secretViews = new LinkedHashMap<>();
        for (JkProtocol.Field field : JkProtocol.Field.values()) {
            LinearLayout line = column();
            line.setPadding(0, dp(12), 0, dp(12));
            line.addView(text(field.label, 13, MUTED));
            String value = info.value(field);
            TextView content = text(value.isEmpty() ? "Not provided" : field.secret ? "••••••" : value, 18, INK);
            content.setSaveEnabled(false);
            content.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            add(line, content, 4);
            if (field.secret) secretViews.put(field, content);
            result.addView(line);
            View divider = new View(this);
            divider.setBackgroundColor(Color.rgb(215, 223, 233));
            result.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        reveal.setOnCheckedChangeListener((button, shown) -> secretViews.forEach((field, view) -> {
            String value = info.value(field);
            view.setText(value.isEmpty() ? "Not provided" : shown ? value : "••••••");
        }));
        Button clear = button("Clear results", false);
        clear.setOnClickListener(view -> clearInfo());
        add(result, clear, 12);
        result.post(() -> result.getParent().requestChildFocus(result, result));
    }

    private void clearInfo() {
        result.removeAllViews();
    }

    @Override protected void onStart() {
        super.onStart();
        log.setOnChanged(this::scheduleLogRefresh);
        scheduleLogRefresh();
    }

    @Override protected void onStop() {
        log.add(DiagnosticLog.Level.INFO, "app", "Activity stopped; closing Bluetooth and clearing displayed results. Connection log retained in memory.");
        client.close();
        clearInfo();
        log.setOnChanged(null);
        logHandler.removeCallbacks(refreshLog);
        logRefreshScheduled = false;
        if (logDialog != null) logDialog.dismiss();
        super.onStop();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.05f);
        return view;
    }

    private TextView heading(String value) {
        TextView view = text(value, 22, INK);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setTextColor(primary ? Color.WHITE : BLUE);
        button.setBackgroundTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(201, 211, 223), primary ? BLUE : Color.rgb(230, 237, 246)}));
        return button;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
