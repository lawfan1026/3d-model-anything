package com.pixelreplica.capture;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FocusLockSequenceTest {
    private FocusPolicy.Sample sample(long sensorMs,long hostMs,int mode,int state,Integer lens) {
        return new FocusPolicy.Sample(sensorMs*1_000_000L,hostMs*1_000_000L,mode,state,lens,4f,mode);
    }
    @Test public void cannotCaptureFromPassiveFocusOrBeforeStartResult() {
        FocusLockSequence sequence=new FocusLockSequence(1000_000_000L);
        assertTrue(sequence.requestStart(3,1000_000_000L));
        assertFalse(sequence.acceptLocked(sample(1100,1100,3,4,0),1100_000_000L));
        sequence.startCompleted(sample(1100,1100,3,2,0));
        assertFalse(sequence.acceptLocked(sample(1130,1130,3,2,0),1130_000_000L));
        assertEquals(FocusLockSequence.Phase.LOCKING,sequence.phase());
    }
    @Test public void confirmedTriggerFrameLockCanAuthorizeExactlyOneStill() {
        FocusLockSequence sequence=new FocusLockSequence(1000_000_000L); sequence.requestStart(3,1000_000_000L);
        FocusPolicy.Sample lock=sample(1100,1100,3,4,0); sequence.startCompleted(lock);
        assertTrue(sequence.acceptLocked(lock,1100_000_000L));
        assertFalse(sequence.acceptLocked(lock,1101_000_000L));
        assertSame(lock,sequence.lockedResult()); assertEquals(FocusLockSequence.Phase.STILL,sequence.phase());
    }
    @Test public void staleWrongModeMovingAndUnfocusedResultsCannotAuthorizeStill() {
        FocusLockSequence sequence=new FocusLockSequence(1000_000_000L); sequence.requestStart(3,1000_000_000L);
        sequence.startCompleted(sample(1100,1100,3,2,0));
        assertFalse(sequence.acceptLocked(sample(1090,1110,3,4,0),1110_000_000L));
        assertFalse(sequence.acceptLocked(sample(1130,1130,4,4,0),1130_000_000L));
        assertFalse(sequence.acceptLocked(sample(1130,1130,3,4,1),1130_000_000L));
        assertFalse(sequence.acceptLocked(sample(1130,1130,3,5,0),1130_000_000L));
        assertFalse(sequence.acceptLocked(sample(1130,1130,3,4,null),1130_000_000L));
        assertFalse(sequence.acceptLocked(sample(1130,1130,3,4,0),1500_000_000L));
    }
    @Test public void anyStartedTerminalPathClaimsExactlyOneCancelAndStopsFutureStill() {
        for(boolean afterLock:new boolean[]{false,true}) {
            FocusLockSequence sequence=new FocusLockSequence(1000_000_000L); sequence.requestStart(3,1000_000_000L);
            FocusPolicy.Sample lock=sample(1100,1100,3,4,0); sequence.startCompleted(lock);
            if(afterLock) assertTrue(sequence.acceptLocked(lock,1100_000_000L));
            assertTrue(sequence.claimCancel()); assertFalse(sequence.claimCancel());
            assertFalse(sequence.acceptLocked(lock,1100_000_000L)); assertTrue(sequence.cancelRequested());
        }
    }
    @Test public void noStartNeedsNoCancelAndOverallDeadlineCannotAuthorizeLatePhoto() {
        FocusLockSequence settling=new FocusLockSequence(1000_000_000L); assertFalse(settling.claimCancel());
        FocusLockSequence sequence=new FocusLockSequence(1000_000_000L); sequence.requestStart(3,1000_000_000L);
        sequence.startCompleted(sample(1100,1100,3,4,0)); assertTrue(sequence.expired(9000_000_000L));
        assertFalse(sequence.acceptLocked(sample(9000,9000,3,4,0),9000_000_000L));
        assertTrue(sequence.claimCancel());
    }
    @Test public void nextTransactionCannotReusePreviousFocusedLockedObservation() {
        FocusLockSequence next=new FocusLockSequence(2000_000_000L); next.requestStart(3,2000_000_000L);
        next.startCompleted(sample(2100,2100,3,2,0));
        assertFalse(next.acceptLocked(sample(1100,1100,3,4,0),2110_000_000L));
        FocusPolicy policy=new FocusPolicy(); policy.add(sample(1100,1100,3,4,0)); policy.clear();
        assertFalse(policy.readiness(2110_000_000L).ready());
    }
}
