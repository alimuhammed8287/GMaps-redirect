package com.snapmaps.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Gesture entry point; the optional launcher alias points here without changing the main icon. */
public final class CaptureActivity extends Activity {
    public static final String ACTION_SCAN = "com.snapmaps.app.action.SCAN_TO_MAPS";
    private boolean handled;

    @Override protected void onCreate(Bundle state) { super.onCreate(state); }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || handled) return;
        handled = true;
        if (Build.VERSION.SDK_INT < 30) {
            setup("Screen capture needs Android 11 or newer. Screenshot sharing still works.");
            return;
        }
        CaptureService service = CaptureService.connected();
        if (service == null) {
            setup("Enable Scan to Maps in Accessibility settings to use the capture shortcut.");
            return;
        }
        boolean drawer = getComponentName().getClassName().endsWith(".CaptureLauncher");
        String target = drawer ? AppSettings.captureApp(this) : service.visibleAppBehindShortcut();
        if (target.isEmpty()) target = AppSettings.captureApp(this);
        if (target.isEmpty()) { setup("Choose your ride app before using the drawer capture icon."); return; }
        String error = service.requestCapture(target);
        if (error != null) { Toast.makeText(this, error, Toast.LENGTH_LONG).show(); finish(); return; }
        // A drawer launch has replaced the ride screen. Bring back its existing task; never
        // send Back/Home gestures or clear the ride activity's task.
        if (drawer || !target.equals(service.visibleAppBehindShortcut())) {
            Intent ride = getPackageManager().getLaunchIntentForPackage(target);
            if (ride == null) {
                service.cancelCapture("The selected ride app is unavailable. Choose it again in Snap to Maps.");
                finish();
                return;
            }
            try {
                ride.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(ride);
            } catch (RuntimeException unavailable) {
                service.cancelCapture("Could not return to the ride app. Open it and use the activity shortcut.");
            }
        }
        finish();
    }

    private void setup(String message) {
        AppSettings.recordStatus(this, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
