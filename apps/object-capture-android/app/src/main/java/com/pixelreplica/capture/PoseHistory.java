package com.pixelreplica.capture;

import java.util.ArrayDeque;

/** Camera-to-world matrices use physical camera axes, metres, and column-major order. */
public final class PoseHistory {
    public static final long MAX_NEAREST_NS = 40_000_000L;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public record Sample(long timestampNs, float[] matrix, float[] cpuIntrinsics,
                         int width, int height, String epoch, boolean tracking) {
        public Sample {
            matrix = matrix.clone();
            cpuIntrinsics = cpuIntrinsics.clone();
        }
    }
    public record Match(Sample sample, String status, Long deltaNs) {}

    public synchronized void add(Sample sample) {
        if (!samples.isEmpty() && sample.timestampNs() <= samples.peekLast().timestampNs()) return;
        samples.addLast(sample);
        while (samples.size() > 240) samples.removeFirst();
    }
    public synchronized void clear() { samples.clear(); }

    public synchronized Match match(long timestampNs, String epoch) {
        Sample best = null;
        long distance = Long.MAX_VALUE;
        for (Sample sample : samples) {
            if (!sample.epoch().equals(epoch) || !sample.tracking()) continue;
            long delta = Math.abs(sample.timestampNs() - timestampNs);
            if (delta < distance) { distance = delta; best = sample; }
        }
        if (best == null || distance > MAX_NEAREST_NS) return new Match(null, "unavailable", null);
        return new Match(best, distance == 0 ? "matched" : "nearest", distance);
    }
}
