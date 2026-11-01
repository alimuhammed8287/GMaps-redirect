package com.snapmaps.app;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.List;

/** Idle until CaptureActivity receives a user-triggered launch; never scans on window events. */
public final class CaptureService extends AccessibilityService {
    private static CaptureService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScanCoordinator.Request request;
    private String targetPackage = "";
    private String requestedMode;
    private long startedAt, stableSince;
    private int stableWindow = -1;
    private boolean sourceSeen, reading;
    private String launcherPackage = "";

    static CaptureService connected() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (home != null && home.activityInfo != null) launcherPackage = home.activityInfo.packageName;
    }

    String visibleAppBehindShortcut() {
        Window window = topApplication(true);
        return window == null || excluded(window.packageName) ? "" : window.packageName;
    }

    String requestCapture(String target) {
        if (Build.VERSION.SDK_INT < 30) return "Screen capture needs Android 11 or newer.";
        if (locked()) return "Unlock the phone before capturing an address.";
        if (excluded(target)) return "Open your ride app before capturing an address.";
        ScanCoordinator.Request next = ScanCoordinator.begin();
        if (next == null) return "Another scan is already running. Please wait.";
        request = next;
        targetPackage = target;
        requestedMode = AppSettings.mode(this);
        startedAt = SystemClock.uptimeMillis();
        stableSince = 0;
        stableWindow = -1;
        sourceSeen = false;
        reading = false;
        handler.postDelayed(poll, 350);
        return null;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (request == null) return;
            if (!request.isCurrent()) { reset(); return; }
            if (locked()) { cancelCapture("Capture cancelled because the phone was locked."); return; }
            long now = SystemClock.uptimeMillis();
            Window window = topApplication(false);
            boolean sourceVisible = window != null && targetPackage.equals(window.packageName);
            if (!sourceVisible) {
                if (sourceSeen) { cancelCapture("Capture cancelled because you left the ride app."); return; }
                if (now - startedAt > 8000) { cancelCapture("The ride app did not become visible. Open its address screen and try again."); return; }
                stableSince = 0;
                stableWindow = -1;
            } else {
                sourceSeen = true;
                if (reading && stableWindow != window.id) {
                    cancelCapture("Capture cancelled because the ride window changed. Try again on the address screen.");
                    return;
                }
                if (now - startedAt > 90000) { cancelCapture("Reading the address timed out. Try sharing the screenshot instead."); return; }
                if (!reading) {
                    if (stableWindow != window.id) { stableWindow = window.id; stableSince = now; }
                    if (now - stableSince >= 900) {
                        reading = true;
                        capture(window);
                    }
                }
            }
            if (request != null) handler.postDelayed(this, 180);
        }
    };

    @android.annotation.TargetApi(30)
    private void capture(Window window) {
        final ScanCoordinator.Request owner = request;
        if (owner == null || !owner.isCurrent() || Build.VERSION.SDK_INT < 30) return;
        TakeScreenshotCallback callback = new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                Bitmap hardware = null;
                Bitmap image = null;
                try {
                    if (request != owner || !owner.isCurrent()) return;
                    hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("No screenshot pixels");
                    image = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (image == null) throw new IllegalStateException("No readable screenshot");
                } catch (RuntimeException failure) {
                    cancelCapture("Could not capture this screen. Share a screenshot instead.");
                    return;
                } finally {
                    if (hardware != null) hardware.recycle();
                    buffer.close();
                }
                final Bitmap captured = image;
                Toast.makeText(CaptureService.this, "Reading bottom address…", Toast.LENGTH_SHORT).show();
                owner.read(CaptureService.this, () -> captured, new ScanCoordinator.Listener() {
                    @Override public void onAddress(String address) {
                        if (request != owner) return;
                        Window visible = topApplication(false);
                        if (locked() || visible == null || visible.id != stableWindow
                                || !targetPackage.equals(visible.packageName)) {
                            cancelCapture("Capture cancelled because you left the ride app.");
                            return;
                        }
                        String mode = requestedMode;
                        reset();
                        try {
                            MapsLauncher.directions(CaptureService.this, address, mode);
                            AppSettings.recordStatus(CaptureService.this, "Destination saved and sent to Maps.");
                        } catch (RuntimeException unavailable) {
                            report("Could not open Maps. The address is saved in history.");
                        }
                    }
                    @Override public void onError(String message) {
                        if (request != owner) return;
                        reset();
                        report(message);
                    }
                });
            }

            @Override public void onFailure(int errorCode) {
                if (request != owner || !owner.isCurrent()) return;
                cancelCapture("Android could not capture this screen. It may be protected; try screenshot sharing.");
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Captures the intended app window, excluding accessibility overlays.
                takeScreenshotOfWindow(window.id, getMainExecutor(), callback);
            } else {
                // Display capture on Android 11–13 cannot isolate split-screen windows.
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                if (window.bounds.width() < width * 0.9f || window.bounds.height() < height * 0.8f) {
                    cancelCapture("Use the ride app full screen before capturing the address.");
                    return;
                }
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), callback);
            }
        } catch (RuntimeException denied) {
            cancelCapture("Screen capture is unavailable. Check Accessibility settings or share a screenshot.");
        }
    }

    void cancelCapture(String message) {
        if (request != null) request.cancel();
        reset();
        report(message);
    }

    private void reset() {
        handler.removeCallbacks(poll);
        request = null;
        targetPackage = "";
        reading = false;
        sourceSeen = false;
    }

    private boolean locked() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private boolean excluded(String packageName) {
        return packageName.isEmpty() || packageName.equals(getPackageName())
                || packageName.equals(launcherPackage) || packageName.equals("com.android.systemui")
                || packageName.equals("com.android.settings");
    }

    private static final class Window {
        final int id, layer;
        final boolean focused;
        final String packageName;
        final Rect bounds;
        Window(int id, int layer, boolean focused, String packageName, Rect bounds) {
            this.id = id; this.layer = layer; this.focused = focused;
            this.packageName = packageName; this.bounds = bounds;
        }
    }

    private Window topApplication(boolean skipShortcut) {
        Window result = null;
        boolean blocked = false;
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            try {
                if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && window.isFocused()) {
                    blocked = true; // e.g. notification shade, permission dialog, or recents.
                }
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                String packageName;
                try { packageName = root.getPackageName() == null ? "" : root.getPackageName().toString(); }
                finally { root.recycle(); }
                if (skipShortcut && packageName.equals(getPackageName())) continue;
                boolean focused = window.isFocused() || window.isActive();
                if (result == null || (focused && !result.focused)
                        || (focused == result.focused && window.getLayer() > result.layer)) {
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    result = new Window(window.getId(), window.getLayer(), focused, packageName, bounds);
                }
            } finally { window.recycle(); }
        }
        return blocked ? null : result;
    }

    private void report(String message) {
        AppSettings.recordStatus(this, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events only accelerate cancellation/visibility checks for an explicit pending request.
        if (request != null) { handler.removeCallbacks(poll); handler.post(poll); }
    }

    @Override public void onInterrupt() {
        if (request != null) cancelCapture("Screen capture was interrupted.");
    }

    @Override public boolean onUnbind(Intent intent) {
        if (request != null) request.cancel();
        reset();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (request != null) request.cancel();
        reset();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
