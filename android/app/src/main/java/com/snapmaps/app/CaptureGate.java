package com.snapmaps.app;

/** Timing/identity rules for one explicit capture. No Android objects or image ownership. */
final class CaptureGate {
    enum Screen { TARGET, TRANSITION, OTHER }
    enum Decision { WAIT, CAPTURE, READY, LEFT, UNCONFIRMED, CHANGED, TIMEOUT, NOT_VISIBLE }
    private final long started;
    private long stableSince, missingSince = -1;
    private int windowId = -1;
    private boolean seen, captured;

    CaptureGate(long now) { started = now; }

    Decision observe(long now, Screen screen, int id) {
        if (now - started >= 90000) return Decision.TIMEOUT;
        if (screen != Screen.TARGET) {
            if (seen && screen == Screen.OTHER) return Decision.LEFT;
            if (missingSince < 0) missingSince = now;
            if (seen && now - missingSince >= 1500) return Decision.UNCONFIRMED;
            if (!seen && now - started >= 8000) return Decision.NOT_VISIBLE;
            if (!captured) windowId = -1;
            return Decision.WAIT;
        }
        seen = true;
        missingSince = -1;
        if (captured) return windowId == id ? Decision.READY : Decision.CHANGED;
        if (windowId != id) {
            windowId = id;
            stableSince = now;
        }
        if (now - stableSince < 900) return Decision.WAIT;
        captured = true;
        return Decision.CAPTURE;
    }
}
