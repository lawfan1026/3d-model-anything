package com.pixelreplica.capture;

import java.util.ArrayDeque;
import java.util.Objects;

/** Focus metadata is a capture gate, not proof that the selected object is sharp. */
public final class FocusPolicy {
    public static final long WAIT_TIMEOUT_NS = 3_000_000_000L;
    public static final long MAX_AGE_NS = 250_000_000L;
    public static final long SETTLE_NS = 150_000_000L;
    private static final long MAX_SAMPLE_GAP_NS = 120_000_000L;
    private static final float MAX_DIOPTER_CHANGE = .10f;
    private final ArrayDeque<Sample> stable = new ArrayDeque<>();
    private Sample latest;

    // Integer values are Camera2 constants; no Android dependencies are needed in this policy.
    public record Sample(long timestampNs, long receivedElapsedNs, Integer mode, Integer state,
                         Integer lensState, Float diopters, Integer requestedMode) {}
    public record Readiness(boolean ready, String reason, Sample latest, long stableDurationNs, int samples) {}

    public synchronized void clear() { latest = null; stable.clear(); }

    public synchronized void add(Sample sample) {
        // A delayed or duplicate result must not make an old observation appear fresh.
        if (latest != null && sample.timestampNs() <= latest.timestampNs()) return;
        Sample previous = latest; latest = sample;
        if (problem(sample) != null) { stable.clear(); return; }
        if (previous == null || !Objects.equals(previous.mode(),sample.mode())
                || sample.timestampNs()-previous.timestampNs() > MAX_SAMPLE_GAP_NS
                || sample.receivedElapsedNs()-previous.receivedElapsedNs() > MAX_SAMPLE_GAP_NS) stable.clear();
        for(Sample prior:stable) {
            if(Math.abs(prior.diopters()-sample.diopters()) > MAX_DIOPTER_CHANGE) { stable.clear(); break; }
        }
        stable.addLast(sample);
        while(stable.size()>24) stable.removeFirst();
    }

    public synchronized Readiness readiness(long nowElapsedNs) {
        if(latest==null) return new Readiness(false,"waiting for autofocus metadata",null,0,0);
        if(nowElapsedNs<latest.receivedElapsedNs() || nowElapsedNs-latest.receivedElapsedNs()>MAX_AGE_NS)
            return new Readiness(false,"autofocus metadata is stale",latest,0,stable.size());
        String problem=problem(latest);
        if(problem!=null) return new Readiness(false,problem,latest,0,stable.size());
        long duration=stable.isEmpty()?0:latest.receivedElapsedNs()-stable.peekFirst().receivedElapsedNs();
        boolean ready=stable.size()>=3 && duration>=SETTLE_NS;
        return new Readiness(ready,ready?"focused metadata; check the actual photo":"waiting for stable focus distance",latest,duration,stable.size());
    }

    public static boolean timedOut(long requestedElapsedNs,long nowElapsedNs) { return nowElapsedNs-requestedElapsedNs>=WAIT_TIMEOUT_NS; }

    private static String problem(Sample sample) {
        if(sample.timestampNs()<=0 || sample.mode()==null || sample.state()==null)
            return "autofocus metadata is unavailable";
        int mode=sample.mode(), state=sample.state();
        if(!(mode==1 || mode==2 || mode==3 || mode==4)) return "no supported automatic focus mode reported";
        boolean focused=(mode==3 || mode==4)?state==2 || state==4:state==4;
        if(!focused) return state==1 || state==3?"autofocus is still scanning":"autofocus has not confirmed focus";
        if(sample.lensState()!=null && sample.lensState()!=0) return "lens is still moving";
        if(sample.diopters()==null || !Float.isFinite(sample.diopters()) || sample.diopters()<0)
            return "focus distance is unavailable";
        return null;
    }

    /** A still can lose focus after the pre-capture gate. Preserve it with an explicit retake warning. */
    public static String stillWarning(Sample expected,Sample actual) {
        if(expected==null || actual==null) return "Final still focus metadata is unavailable. Inspect the full-size photo and retake if soft.";
        if(!Objects.equals(expected.mode(),actual.mode())) return "Autofocus mode changed during the still. Hold still and retake this view.";
        String issue=problem(actual);
        if(issue!=null) return "Still focus warning: "+issue+". Hold still, step back slightly and retake this view.";
        if(actual.lensState()==null) return "Still lens state was not reported. Inspect the full-size photo and retake if soft.";
        if(expected.diopters()!=null && Math.abs(expected.diopters()-actual.diopters())>MAX_DIOPTER_CHANGE)
            return "Focus distance changed during the still. Hold still and retake this view.";
        return null;
    }
}
