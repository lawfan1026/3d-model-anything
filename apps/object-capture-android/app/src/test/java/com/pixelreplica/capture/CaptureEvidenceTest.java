package com.pixelreplica.capture;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Random;

public final class CaptureEvidenceTest {
    private PoseHistory.Sample sample(long time, String epoch, boolean tracking) {
        return new PoseHistory.Sample(time,new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},new float[]{100,100,50,50},100,100,epoch,tracking);
    }
    @Test public void poseRequiresTimestampAndEpochNotCallbackOrder() {
        PoseHistory history=new PoseHistory(); history.add(sample(100_000_000L,"first",true)); history.add(sample(140_000_000L,"first",true));
        assertEquals("matched",history.match(100_000_000L,"first").status());
        assertEquals("nearest",history.match(120_000_000L,"first").status());
        assertEquals(Long.valueOf(20_000_000L),history.match(120_000_000L,"first").deltaNs());
        assertEquals("unavailable",history.match(100_000_000L,"second").status());
        assertEquals("unavailable",history.match(200_000_000L,"first").status());
    }
    @Test public void trackingLossCannotBecomeValidPose() {
        PoseHistory history=new PoseHistory(); history.add(sample(100_000_000L,"first",false));
        assertEquals("unavailable",history.match(100_000_000L,"first").status());
        history.add(sample(150_000_000L,"first",true)); history.clear();
        assertEquals("unavailable",history.match(150_000_000L,"first").status());
    }
    @Test public void autoCaptureRequiresEvidenceAndOnePendingCapture() {
        CaptureState state=new CaptureState(); CaptureQuality useful=new CaptureQuality(90,.1,.01);
        assertFalse(state.autoReady(3_000_000_000L,false,true,useful,true));
        assertFalse(state.autoReady(3_000_000_000L,true,false,useful,true));
        assertFalse(state.autoReady(3_000_000_000L,true,true,null,true));
        assertTrue(state.autoReady(3_000_000_000L,true,true,useful,true));
        assertTrue(state.begin()); assertFalse(state.begin());
        assertFalse(state.autoReady(6_000_000_000L,true,true,useful,true));
        state.finish(); assertTrue(state.autoReady(6_000_000_000L,true,true,useful,true));
    }
    @Test public void sharpnessHandlesPaddingAndDetectsFlatImage() {
        byte[] flat=new byte[100*112]; java.util.Arrays.fill(flat,(byte)120);
        assertEquals(0,CaptureQuality.analyze(flat,100,100,112,1).sharpness(),.001);
        byte[] textured=flat.clone(); new Random(42).nextBytes(textured);
        assertTrue(CaptureQuality.analyze(textured,100,100,112,1).sharpness()>100);
    }
}
