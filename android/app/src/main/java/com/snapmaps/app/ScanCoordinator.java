package com.snapmaps.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One explicit request at a time across picker, screenshot shares, and accessibility capture. */
final class ScanCoordinator {
    interface ImageSource { Bitmap read() throws Exception; }
    interface Listener {
        void onAddress(String address);
        void onError(String message);
    }
    static final class Request {
        private volatile boolean cancelled;
        private boolean submitted;
        private Thread runningThread;

        boolean isCurrent() { synchronized (ScanCoordinator.class) { return active == this && !cancelled; } }

        void cancel() {
            synchronized (ScanCoordinator.class) {
                cancelled = true;
                if (active == this) active = null;
                if (runningThread != null) runningThread.interrupt();
            }
        }

        void read(Context context, ImageSource source, Listener listener) {
            synchronized (ScanCoordinator.class) {
                if (submitted) throw new IllegalStateException("A scan request can only read one image.");
                submitted = true;
            }
            Context app = context.getApplicationContext();
            // Even cancelled queued work takes ownership of its source and releases any Bitmap.
            WORKER.execute(() -> {
                Bitmap bitmap = null;
                String address = "";
                String error = null;
                try {
                    synchronized (ScanCoordinator.class) { runningThread = Thread.currentThread(); }
                    bitmap = source.read();
                    if (!cancelled) address = OcrReader.readBottomAddress(app, bitmap);
                    if (address.isEmpty()) error = "No clear bottom address found. Keep the full address card visible and try again.";
                } catch (Exception failure) {
                    error = "Could not read the bottom address. Share the original screenshot or try again with the address visible.";
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    synchronized (ScanCoordinator.class) { runningThread = null; }
                    Thread.interrupted();
                }
                final String result = address;
                final String failure = error;
                MAIN.post(() -> {
                    synchronized (ScanCoordinator.class) {
                        if (active != this || cancelled) return;
                        active = null;
                    }
                    if (failure == null) listener.onAddress(result); else listener.onError(failure);
                });
            });
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Request active;
    private ScanCoordinator() { }

    static synchronized Request begin() {
        if (active != null) return null;
        active = new Request();
        return active;
    }
}
