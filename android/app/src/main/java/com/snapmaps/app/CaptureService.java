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
    private CaptureGate gate;
    private String pendingAddress;
    private long mapsStartedAt;
    private long handoffGeneration;
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
        handler.removeCallbacks(checkMaps);
        mapsStartedAt = 0;
        request = next;
        targetPackage = target;
        requestedMode = AppSettings.mode(this);
        gate = new CaptureGate(SystemClock.uptimeMillis());
        pendingAddress = null;
        AppSettings.recordStatus(this, "Waiting for the ride address screen...");
        handler.postDelayed(poll, 350);
        return null;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (request == null) return;
            if (!request.isLatest()) { reset(); return; }
            if (pendingAddress == null && !request.isCurrent()) { reset(); return; }
            if (locked()) { cancelCapture("Capture cancelled because the phone was locked."); return; }
            Window window = topApplication(false);
            CaptureGate.Screen screen = window != null && targetPackage.equals(window.packageName)
                    ? CaptureGate.Screen.TARGET
                    : window == null || getPackageName().equals(window.packageName)
                    ? CaptureGate.Screen.TRANSITION : CaptureGate.Screen.OTHER;
            CaptureGate.Decision decision = gate.observe(SystemClock.uptimeMillis(), screen,
                    window == null ? -1 : window.id);
            switch (decision) {
                case CAPTURE: capture(window); break;
                case READY:
                    if (pendingAddress != null) { openCapturedAddress(); return; }
                    break;
                case LEFT: cancelCapture("Capture cancelled because you left the ride app."); return;
                case UNCONFIRMED: cancelCapture("The ride screen could not be confirmed. Keep its address visible and try again."); return;
                case CHANGED: cancelCapture("Capture cancelled because the ride window changed. Try again on the address screen."); return;
                case TIMEOUT: cancelCapture("Reading the address timed out. Try sharing the screenshot instead."); return;
                case NOT_VISIBLE: cancelCapture("The ride app did not become visible. Open its address screen and try again."); return;
                case WAIT: break;
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
                        pendingAddress = address;
                        // Retain a successfully read destination even if the user switches away
                        // before the final visibility check. Never open Maps over another app.
                        HistoryStore.save(CaptureService.this, address);
                        handler.removeCallbacks(poll);
                        handler.post(poll);
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
                cancelCapture("Android could not capture this screen (error " + errorCode + "). Try screenshot sharing.");
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

    private void openCapturedAddress() {
        String address = pendingAddress;
        String mode = requestedMode;
        handoffGeneration = ScanCoordinator.generation();
        reset();
        try {
            // A dedicated foreground activity performs the external-app handoff. It
            // never runs OCR or changes the selected ride app / normal launcher state.
            startActivity(new Intent(this, MapsHandoffActivity.class)
                    .putExtra("address", address).putExtra("mode", mode)
                    .putExtra("generation", handoffGeneration)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AppSettings.recordStatus(this, "Address saved. Waiting for Google Maps to open...");
            mapsStartedAt = SystemClock.uptimeMillis();
            handler.postDelayed(checkMaps, 250);
        } catch (RuntimeException unavailable) {
            report("Address saved in history, but Maps could not open. Open it from history.");
        }
    }

    private final Runnable checkMaps = new Runnable() {
        @Override public void run() {
            if (mapsStartedAt == 0) return;
            if (handoffGeneration != ScanCoordinator.generation()) { mapsStartedAt = 0; return; }
            Window visible = topApplication(false);
            if (visible != null && "com.google.android.apps.maps".equals(visible.packageName)) {
                mapsStartedAt = 0;
                AppSettings.recordStatus(CaptureService.this, "Address saved and Google Maps opened.");
            } else if (SystemClock.uptimeMillis() - mapsStartedAt >= 6000) {
                mapsStartedAt = 0;
                report("Address saved, but Google Maps did not appear. Open it from history. Check your phone's background pop-up permissions for Snap to Maps.");
            } else handler.postDelayed(this, 250);
        }
    };

    void cancelCapture(String message) {
        if (pendingAddress != null) message = "Address saved in history. " + message;
        if (request != null) request.cancel();
        reset();
        report(message);
    }

    private void reset() {
        handler.removeCallbacks(poll);
        request = null;
        targetPackage = "";
        pendingAddress = null;
        gate = null;
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
        handler.removeCallbacks(checkMaps);
        mapsStartedAt = 0;
        reset();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (request != null) request.cancel();
        handler.removeCallbacks(checkMaps);
        mapsStartedAt = 0;
        reset();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
