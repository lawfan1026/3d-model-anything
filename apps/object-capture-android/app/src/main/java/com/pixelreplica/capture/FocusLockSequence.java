package com.pixelreplica.capture;

import java.util.Objects;

/** One bounded START -> confirmed lock -> still -> CANCEL transaction. No platform side effects. */
public final class FocusLockSequence {
    public enum Phase { SETTLING, LOCKING, STILL, FINISHED }
    public static final long OVERALL_TIMEOUT_NS=8_000_000_000L;
    private final long requestedElapsedNs;
    private Phase phase=Phase.SETTLING;
    private Integer mode;
    private FocusPolicy.Sample startResult,lockedResult;
    private boolean startRequested,cancelRequested;

    public FocusLockSequence(long requestedElapsedNs) { this.requestedElapsedNs=requestedElapsedNs; }
    public synchronized Phase phase() { return phase; }
    public synchronized boolean expired(long nowElapsedNs) { return nowElapsedNs-requestedElapsedNs>=OVERALL_TIMEOUT_NS; }
    public synchronized boolean requestStart(int observedMode,long nowElapsedNs) {
        if(phase!=Phase.SETTLING || expired(nowElapsedNs)) return false;
        mode=observedMode; startRequested=true; phase=Phase.LOCKING; return true;
    }
    public synchronized void startCompleted(FocusPolicy.Sample sample) {
        if(phase==Phase.LOCKING && Objects.equals(mode,sample.mode())) startResult=sample;
    }
    public synchronized boolean acceptLocked(FocusPolicy.Sample sample,long nowElapsedNs) {
        if(phase!=Phase.LOCKING || expired(nowElapsedNs) || startResult==null || sample==null) return false;
        if(sample.timestampNs()<startResult.timestampNs() || sample.receivedElapsedNs()<startResult.receivedElapsedNs()
                || nowElapsedNs<sample.receivedElapsedNs() || nowElapsedNs-sample.receivedElapsedNs()>FocusPolicy.MAX_AGE_NS) return false;
        if(!Objects.equals(mode,sample.mode()) || !Integer.valueOf(4).equals(sample.state())
                || !Integer.valueOf(0).equals(sample.lensState()) || sample.diopters()==null
                || !Float.isFinite(sample.diopters()) || sample.diopters()<0) return false;
        lockedResult=sample; phase=Phase.STILL; return true;
    }
    public synchronized FocusPolicy.Sample lockedResult() { return lockedResult; }
    /** The caller must dispatch cleanup to the originating live session, never a replacement session. */
    public synchronized boolean claimCancel() {
        phase=Phase.FINISHED;
        if(!startRequested || cancelRequested) return false;
        cancelRequested=true; return true;
    }
    public synchronized boolean cancelRequested() { return cancelRequested; }
}
