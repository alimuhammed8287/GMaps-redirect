package com.snapmaps.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;

/** Internal, user-requested capture handoff. No launcher or external entry point. */
public final class MapsHandoffActivity extends Activity {
    private boolean handled;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        handled = state != null && state.getBoolean("handled");
        if (handled) finish();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || handled) return;
        handled = true;
        String address = getIntent().getStringExtra("address");
        String mode = getIntent().getStringExtra("mode");
        try {
            KeyguardManager lock = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (getIntent().getLongExtra("generation", -1) == ScanCoordinator.generation()
                    && !ScanCoordinator.busy() && (lock == null || !lock.isKeyguardLocked())
                    && address != null && !address.trim().isEmpty()) {
                MapsLauncher.directions(this, address, mode);
            }
        } catch (RuntimeException unavailable) {
            AppSettings.recordStatus(this, "Address saved in history, but Maps could not open. Open it from history.");
        } finally {
            finish();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("handled", handled);
        super.onSaveInstanceState(state);
    }
}
