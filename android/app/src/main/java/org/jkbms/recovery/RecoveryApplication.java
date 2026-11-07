package org.jkbms.recovery;

import android.app.Application;
import android.os.Build;

public final class RecoveryApplication extends Application {
    private DiagnosticLog log;

    @Override public void onCreate() {
        super.onCreate();
        log = new DiagnosticLog("JK BMS Recovery " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " / " + Build.MANUFACTURER + " " + Build.MODEL);
        log.add(DiagnosticLog.Level.INFO, "app", "Process started; screenshots allowed; logs stay in memory until cleared or the process exits.");
    }

    public DiagnosticLog log() { return log; }
}
