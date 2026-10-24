package com.pixelreplica.capture;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FocusPolicyTest {
    private FocusPolicy.Sample sample(long ms,int mode,int state,Integer lens,float diopters) {
        return new FocusPolicy.Sample(ms*1_000_000L,ms*1_000_000L,mode,state,lens,diopters,mode);
    }
    private FocusPolicy settled() {
        FocusPolicy policy=new FocusPolicy();
        policy.add(sample(1000,3,2,0,4)); policy.add(sample(1080,3,2,0,4.02f)); policy.add(sample(1160,3,2,0,4.01f));
        return policy;
    }
    @Test public void requiresFreshFocusedStableWindowAndReusesReportedMode() {
        FocusPolicy policy=new FocusPolicy(); policy.add(sample(1000,3,2,0,4));
        assertFalse(policy.readiness(1000_000_000L).ready());
        policy.add(sample(1080,3,2,0,4.02f)); assertFalse(policy.readiness(1080_000_000L).ready());
        policy.add(sample(1160,3,2,0,4.01f));
        FocusPolicy.Readiness result=policy.readiness(1160_000_000L);
        assertTrue(result.ready()); assertEquals(Integer.valueOf(3),result.latest().mode());
        assertEquals(160_000_000L,result.stableDurationNs());
    }
    @Test public void scanningResetsWindowEvenWhenEarlierFocusWasReady() {
        FocusPolicy policy=settled(); policy.add(sample(1190,3,1,1,1.0f));
        assertFalse(policy.readiness(1190_000_000L).ready());
        assertTrue(policy.readiness(1190_000_000L).reason().contains("scanning"));
        policy.add(sample(1220,3,2,0,4)); assertFalse(policy.readiness(1220_000_000L).ready());
    }
    @Test public void staleAndDuplicateCallbacksCannotPassFreshness() {
        FocusPolicy policy=settled(); assertFalse(policy.readiness(1500_000_000L).ready());
        policy.add(new FocusPolicy.Sample(1160_000_000L,1600_000_000L,3,2,0,4.01f,3));
        assertFalse(policy.readiness(1600_000_000L).ready());
        policy.add(sample(1200,3,2,0,4.01f)); policy.clear(); assertFalse(policy.readiness(1200_000_000L).ready());
    }
    @Test public void movingLensAndMissingEvidenceDoNotBecomeReady() {
        FocusPolicy policy=settled(); policy.add(sample(1190,3,2,1,4)); assertFalse(policy.readiness(1190_000_000L).ready());
        policy.add(new FocusPolicy.Sample(1220_000_000L,1220_000_000L,null,2,0,4f,null));
        assertFalse(policy.readiness(1220_000_000L).ready());
        policy.add(sample(1250,3,6,0,4)); assertFalse(policy.readiness(1250_000_000L).ready());
    }
    @Test public void focusDistanceJumpAndModeChangeRestartSettling() {
        FocusPolicy policy=settled(); policy.add(sample(1190,3,2,0,1)); assertFalse(policy.readiness(1190_000_000L).ready());
        policy.add(sample(1270,3,2,0,1)); policy.add(sample(1350,3,2,0,1)); assertTrue(policy.readiness(1350_000_000L).ready());
        policy.add(sample(1380,4,2,0,1)); assertFalse(policy.readiness(1380_000_000L).ready());
    }
    @Test public void finalStillIsCheckedIndependentlyOfPreviewReadiness() {
        FocusPolicy.Sample expected=settled().readiness(1160_000_000L).latest();
        assertNull(FocusPolicy.stillWarning(expected,sample(1220,3,2,0,4.01f)));
        assertTrue(FocusPolicy.stillWarning(expected,sample(1220,3,1,1,1)).contains("scanning"));
        assertTrue(FocusPolicy.stillWarning(expected,sample(1220,4,2,0,4)).contains("mode changed"));
        assertTrue(FocusPolicy.stillWarning(expected,sample(1220,3,2,null,4)).contains("not reported"));
        assertTrue(FocusPolicy.stillWarning(expected,sample(1220,3,2,0,1)).contains("distance changed"));
    }
    @Test public void timeoutIsBoundedAndNoFixedSleepImpliesFocus() {
        FocusPolicy policy=new FocusPolicy();
        assertFalse(policy.readiness(4000_000_000L).ready());
        assertFalse(FocusPolicy.timedOut(1000_000_000L,3999_000_000L));
        assertTrue(FocusPolicy.timedOut(1000_000_000L,4000_000_000L));
    }
    @Test public void freshnessAndSettlingUseOneHostClockNotTheSensorClockOrigin() {
        FocusPolicy policy=new FocusPolicy(); long sensorOrigin=900_000_000_000L;
        policy.add(new FocusPolicy.Sample(sensorOrigin,1000_000_000L,3,2,0,4f,3));
        policy.add(new FocusPolicy.Sample(sensorOrigin+80_000_000L,1050_000_000L,3,2,0,4f,3));
        policy.add(new FocusPolicy.Sample(sensorOrigin+160_000_000L,1100_000_000L,3,2,0,4f,3));
        assertFalse(policy.readiness(1100_000_000L).ready()); // Sensor window is 160 ms, host window only 100 ms.
        policy.add(new FocusPolicy.Sample(sensorOrigin+240_000_000L,1160_000_000L,3,2,0,4f,3));
        assertTrue(policy.readiness(1160_000_000L).ready());
        assertFalse(policy.readiness(1500_000_000L).ready());
    }
    @Test public void pixelScanningFinalStateIsNotAlarmingWhenLensHeldLockedDistance() {
        FocusPolicy.Sample locked=sample(1000,3,4,0,4.0f);
        // Pixel 8 still result: PASSIVE_SCAN, stationary lens, unchanged distance (observed on the owner's phone).
        assertTrue(FocusPolicy.lensHeld(locked,sample(1100,3,1,0,4.0f)));
        assertNotNull("the metadata warning is still recorded",FocusPolicy.stillWarning(locked,sample(1100,3,1,0,4.0f)));
        assertFalse("moving lens",FocusPolicy.lensHeld(locked,sample(1100,3,1,1,4.0f)));
        assertFalse("refocused elsewhere",FocusPolicy.lensHeld(locked,sample(1100,3,1,0,2.5f)));
        assertFalse(FocusPolicy.lensHeld(null,sample(1100,3,1,0,4.0f)));
        assertFalse(FocusPolicy.lensHeld(locked,null));
    }
}
