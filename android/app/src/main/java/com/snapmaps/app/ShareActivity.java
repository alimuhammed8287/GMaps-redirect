package com.snapmaps.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.util.ArrayList;

/** A separate translucent share receiver; it never creates or depends on the settings UI. */
public final class ShareActivity extends Activity {
    private ScanCoordinator.Request request;
    private boolean launched;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri image;
        try { image = sharedImage(getIntent()); }
        catch (RuntimeException badShare) { fail("The shared item did not include a readable image."); return; }
        if (image == null) { fail("Share one screenshot containing the bottom address."); return; }
        request = ScanCoordinator.begin();
        if (request == null) { fail("Another scan is already running. Please wait."); return; }
        String mode = AppSettings.mode(this);
        Toast.makeText(this, "Reading bottom address…", Toast.LENGTH_SHORT).show();
        request.read(this, () -> OcrReader.decode(getApplicationContext(), image), new ScanCoordinator.Listener() {
            @Override public void onAddress(String address) {
                if (isFinishing() || isDestroyed()) return;
                try {
                    MapsLauncher.directions(ShareActivity.this, address, mode);
                    launched = true;
                    AppSettings.recordStatus(ShareActivity.this, "Destination saved and sent to Maps.");
                    finish();
                } catch (RuntimeException missingMaps) { fail("Could not open Maps. The address is saved in history."); }
            }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private Uri sharedImage(Intent intent) {
        if (intent == null) return null;
        Uri result = null;
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> images = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (images != null && images.size() > 1) return null;
            if (images != null && !images.isEmpty()) result = images.get(0);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            result = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else return null;
        if (result == null && intent.getClipData() != null) {
            if (intent.getClipData().getItemCount() != 1) return null;
            result = intent.getClipData().getItemAt(0).getUri();
        }
        if (result == null || !("content".equals(result.getScheme()) || "file".equals(result.getScheme()))) return null;
        return result;
    }

    private void fail(String message) {
        AppSettings.recordStatus(this, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override protected void onStop() {
        super.onStop();
        // Switching away or locking the phone cancels a pending redirect.
        if (!launched && request != null) {
            if (request.isCurrent()) AppSettings.recordStatus(this, "Share scan cancelled because you left the scan.");
            request.cancel();
            finish();
        }
    }

    @Override protected void onDestroy() {
        if (request != null) request.cancel();
        super.onDestroy();
    }
}

