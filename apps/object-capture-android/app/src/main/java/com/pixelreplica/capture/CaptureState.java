package com.pixelreplica.capture;

/** A single pending capture owns its epoch and role until it is committed or failed. */
public final class CaptureState {
    private boolean pending;
    private long lastAutoNs;
    /** Automatic photos are gated by new viewpoints; this only paces focus-lock attempts. */
    static final long AUTO_INTERVAL_NS = 1_200_000_000L;
    public synchronized boolean begin() {
        if (pending) return false;
        pending = true; return true;
    }
    public synchronized void finish() { pending = false; }
    public synchronized boolean pending() { return pending; }
    public synchronized boolean autoReady(long nowNs, boolean tracking, boolean stable,
                                           CaptureQuality quality, boolean enabled) {
        if (!enabled || pending || !tracking || !stable || quality == null || !quality.useful()
                || nowNs - lastAutoNs < AUTO_INTERVAL_NS) return false;
        lastAutoNs = nowNs;
        return true;
    }
}
