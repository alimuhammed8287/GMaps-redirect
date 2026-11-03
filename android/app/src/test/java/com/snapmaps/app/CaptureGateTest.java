package com.snapmaps.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.snapmaps.app.CaptureGate.Decision.*;
import static com.snapmaps.app.CaptureGate.Screen.*;

/** Exercises production timing decisions; not an Android screenshot or Maps integration test. */
public final class CaptureGateTest {
    private CaptureGate reading() {
        CaptureGate gate = new CaptureGate(0);
        assertEquals(WAIT, gate.observe(0, TARGET, 7));
        assertEquals(CAPTURE, gate.observe(900, TARGET, 7));
        return gate;
    }

    @Test public void waitsForStableTargetBeforeCapturing() {
        CaptureGate gate = new CaptureGate(0);
        assertEquals(WAIT, gate.observe(300, TRANSITION, -1));
        assertEquals(WAIT, gate.observe(600, TARGET, 7));
        assertEquals(WAIT, gate.observe(1499, TARGET, 7));
        assertEquals(CAPTURE, gate.observe(1500, TARGET, 7));
    }

    @Test public void briefTransitionBeforeCaptureRestartsStabilityWait() {
        CaptureGate gate = new CaptureGate(0);
        gate.observe(0, TARGET, 7);
        assertEquals(WAIT, gate.observe(700, TRANSITION, -1));
        assertEquals(WAIT, gate.observe(800, TARGET, 7));
        assertEquals(WAIT, gate.observe(1699, TARGET, 7));
        assertEquals(CAPTURE, gate.observe(1700, TARGET, 7));
    }

    @Test public void temporaryWindowLossDuringOcrCanRecover() {
        CaptureGate gate = reading();
        assertEquals(WAIT, gate.observe(1100, TRANSITION, -1));
        assertEquals(READY, gate.observe(2400, TARGET, 7));
    }

    @Test public void unknownWindowNeverTriggersCaptureOrHandoff() {
        CaptureGate gate = reading();
        assertEquals(WAIT, gate.observe(1000, TRANSITION, -1));
        assertEquals(WAIT, gate.observe(2499, TRANSITION, -1));
        assertEquals(UNCONFIRMED, gate.observe(2500, TRANSITION, -1));
    }

    @Test public void explicitOtherAppCancelsImmediatelyDuringOcr() {
        assertEquals(LEFT, reading().observe(1000, OTHER, 8));
    }

    @Test public void explicitOtherAppCancelsBeforeCaptureToo() {
        CaptureGate gate = new CaptureGate(0);
        gate.observe(0, TARGET, 7);
        assertEquals(LEFT, gate.observe(100, OTHER, 8));
    }

    @Test public void changedWindowAfterCaptureCannotUseOldImage() {
        assertEquals(CHANGED, reading().observe(1000, TARGET, 8));
    }

    @Test public void windowChangeBeforeCaptureWaitsForNewWindow() {
        CaptureGate gate = new CaptureGate(0);
        gate.observe(0, TARGET, 7);
        assertEquals(WAIT, gate.observe(800, TARGET, 8));
        assertEquals(WAIT, gate.observe(1699, TARGET, 8));
        assertEquals(CAPTURE, gate.observe(1700, TARGET, 8));
    }

    @Test public void captureOccursOnlyOnceAcrossRepeatedEvents() {
        CaptureGate gate = reading();
        assertEquals(READY, gate.observe(901, TARGET, 7));
        assertEquals(READY, gate.observe(902, TARGET, 7));
    }

    @Test public void slowOcrIsNotLimitedByInitialAppLaunchTimeout() {
        CaptureGate gate = reading();
        assertEquals(WAIT, gate.observe(20000, TRANSITION, -1));
        assertEquals(READY, gate.observe(21000, TARGET, 7));
    }

    @Test public void initialMissingTargetTimesOut() {
        CaptureGate gate = new CaptureGate(0);
        assertEquals(WAIT, gate.observe(7999, OTHER, 1));
        assertEquals(NOT_VISIBLE, gate.observe(8000, OTHER, 1));
    }

    @Test public void overallTimeoutAlsoAppliesWhileScreenIsUnknown() {
        CaptureGate gate = reading();
        assertEquals(TIMEOUT, gate.observe(90000, TRANSITION, -1));
    }

    @Test public void readingTimesOutEvenWhenTargetRemainsVisible() {
        assertEquals(TIMEOUT, reading().observe(90000, TARGET, 7));
    }

    @Test public void recoveredGapDoesNotAccumulateIntoNextGap() {
        CaptureGate gate = reading();
        gate.observe(1000, TRANSITION, -1);
        assertEquals(READY, gate.observe(2000, TARGET, 7));
        assertEquals(WAIT, gate.observe(3000, TRANSITION, -1));
        assertEquals(READY, gate.observe(4400, TARGET, 7));
    }
}
