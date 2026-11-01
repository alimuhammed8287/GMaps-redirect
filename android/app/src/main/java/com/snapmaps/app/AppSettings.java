package com.snapmaps.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

final class AppSettings {
    static final String BIKE = "two-wheeler";
    static final String CAR = "driving";
    private AppSettings() { }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("snap_maps", Context.MODE_PRIVATE);
    }

    static String mode(Context context) {
        return CAR.equals(prefs(context).getString("travel_mode", BIKE)) ? CAR : BIKE;
    }

    static void setMode(Context context, String mode) {
        prefs(context).edit().putString("travel_mode", CAR.equals(mode) ? CAR : BIKE).apply();
    }

    static String captureApp(Context context) {
        return prefs(context).getString("capture_app", "");
    }

    static void setCaptureApp(Context context, String packageName, String label) {
        prefs(context).edit().putString("capture_app", packageName)
                .putString("capture_app_label", label).apply();
    }

    private static ComponentName shortcut(Context context) {
        return new ComponentName(context, "com.snapmaps.app.CaptureLauncher");
    }

    static boolean isShortcutShown(Context context) {
        return context.getPackageManager().getComponentEnabledSetting(shortcut(context))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    static void showShortcut(Context context, boolean enabled) {
        context.getPackageManager().setComponentEnabledSetting(shortcut(context), enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    static void recordStatus(Context context, String message) {
        prefs(context).edit().putString("last_scan_status", message).apply();
    }
}
