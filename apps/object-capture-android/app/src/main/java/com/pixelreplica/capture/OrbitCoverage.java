package com.pixelreplica.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * Viewpoint coverage for walking around one stationary object.
 *
 * Cells come from the camera's viewing direction in the gravity-aligned ARCore world, so no object
 * centre is needed: circling an object while pointing at it sweeps the heading through 360 degrees,
 * and raising the phone to look down moves the pitch from the low ring toward the overhead cell.
 * Headings are only comparable inside one tracking world, so a restarted world starts a new dial.
 */
public final class OrbitCoverage {
    /** Pitch is degrees below horizontal for the optical axis; a ring covers [minPitch, maxPitch). */
    public record Ring(String name, double minPitch, double maxPitch, int sectors) {}
    public record View(double headingDeg, double pitchDeg) {}
    public record Cell(int ring, int sector) {}
    /** What the current viewpoint would add: a new photo, nothing, or nothing until the camera moves. */
    public enum Need { NEW_VIEW, COVERED, TURN_ONLY, OFF_DIAL }
    public record Snapshot(boolean[][] filled, int photos, String epoch) {
        public int filled(int ring) { int n = 0; for (boolean f : filled[ring]) if (f) n++; return n; }
        public int total() { int n = 0; for (boolean[] ring : filled) n += ring.length; return n; }
        public int filledTotal() { int n = 0; for (int r = 0; r < filled.length; r++) n += filled(r); return n; }
        public double fraction() { return (double) filledTotal() / total(); }
    }

    // Outer to inner on the dial: a dome seen from above.
    public static final Ring[] RINGS = {
            new Ring("low", -25, 22, 24),
            new Ring("middle", 22, 47, 24),
            new Ring("top", 47, 78, 12),
            new Ring("overhead", 78, 90.0001, 1),
    };
    /** Two automatic photos closer than this add little parallax for photogrammetry. */
    public static final double MIN_SEPARATION_DEG = 8;
    /** Turning the phone without moving it gives photogrammetry no parallax. */
    public static final double MIN_BASELINE_M = 0.02;

    private final boolean[][] filled = new boolean[RINGS.length][];
    private final List<double[]> forwards = new ArrayList<>(), positions = new ArrayList<>();
    private String epoch;

    public OrbitCoverage() { for (int r = 0; r < RINGS.length; r++) filled[r] = new boolean[RINGS[r].sectors()]; }

    /** cameraToWorld is column-major with the camera looking down its -Z axis (ARCore physical pose). */
    public static double[] forward(float[] cameraToWorld) {
        double x = -cameraToWorld[8], y = -cameraToWorld[9], z = -cameraToWorld[10];
        double length = Math.sqrt(x * x + y * y + z * z);
        if (!(length > 1e-6)) return null;
        return new double[]{x / length, y / length, z / length};
    }

    public static View view(float[] cameraToWorld) {
        double[] f = forward(cameraToWorld);
        if (f == null) return null;
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -f[1]))));
        double heading = Math.toDegrees(Math.atan2(f[0], -f[2]));
        if (heading < 0) heading += 360;
        return new View(heading, pitch);
    }

    /** Returns null when the camera points up and away from an object on a table. */
    public static Cell cell(View view) {
        if (view == null) return null;
        for (int r = 0; r < RINGS.length; r++) {
            Ring ring = RINGS[r];
            if (view.pitchDeg() >= ring.minPitch() && view.pitchDeg() < ring.maxPitch()) {
                int sector = (int) Math.floor(view.headingDeg() / (360.0 / ring.sectors()));
                return new Cell(r, Math.floorMod(sector, ring.sectors()));
            }
        }
        return null;
    }

    public synchronized void reset(String epoch) {
        this.epoch = epoch; forwards.clear(); positions.clear();
        for (boolean[] ring : filled) java.util.Arrays.fill(ring, false);
    }

    /** Records a saved photo. Photos from another tracking world cannot be placed on this dial. */
    public synchronized boolean add(String photoEpoch, float[] cameraToWorld) {
        if (cameraToWorld == null || epoch == null || !epoch.equals(photoEpoch)) return false;
        double[] f = forward(cameraToWorld);
        Cell c = cell(view(cameraToWorld));
        if (f == null) return false;
        forwards.add(f);
        positions.add(new double[]{cameraToWorld[12], cameraToWorld[13], cameraToWorld[14]});
        if (c != null) filled[c.ring()][c.sector()] = true;
        return true;
    }

    public synchronized boolean isFilled(Cell c) { return c != null && filled[c.ring()][c.sector()]; }

    /** Smallest angle between this viewing direction and any photo already on the dial. */
    public synchronized double nearestShotDegrees(float[] cameraToWorld) {
        double[] f = forward(cameraToWorld);
        if (f == null) return 0;
        double best = 180;
        for (double[] g : forwards) {
            double dot = Math.max(-1, Math.min(1, f[0] * g[0] + f[1] * g[1] + f[2] * g[2]));
            best = Math.min(best, Math.toDegrees(Math.acos(dot)));
        }
        return best;
    }

    /** Distance from this camera position to the nearest photo already on the dial. */
    public synchronized double nearestShotMetres(float[] cameraToWorld) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] q : positions) {
            double dx = cameraToWorld[12] - q[0], dy = cameraToWorld[13] - q[1], dz = cameraToWorld[14] - q[2];
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    public synchronized Need need(String currentEpoch, float[] cameraToWorld) {
        if (epoch == null || !epoch.equals(currentEpoch)) return Need.OFF_DIAL;
        Cell c = cell(view(cameraToWorld));
        if (c == null) return Need.OFF_DIAL;
        if (filled[c.ring()][c.sector()] || nearestShotDegrees(cameraToWorld) < MIN_SEPARATION_DEG) return Need.COVERED;
        return nearestShotMetres(cameraToWorld) < MIN_BASELINE_M ? Need.TURN_ONLY : Need.NEW_VIEW;
    }

    /** True when this view fills an empty cell, is not a near-duplicate and the camera has moved. */
    public boolean wantsShot(String currentEpoch, float[] cameraToWorld) {
        return need(currentEpoch, cameraToWorld) == Need.NEW_VIEW;
    }

    public synchronized Snapshot snapshot() {
        boolean[][] copy = new boolean[filled.length][];
        for (int r = 0; r < filled.length; r++) copy[r] = filled[r].clone();
        return new Snapshot(copy, forwards.size(), epoch);
    }

    /** One short instruction for the person holding the phone. */
    public static String hint(Snapshot s, View current, Need need) {
        if (current == null) return "Point the camera at the object";
        if (current.pitchDeg() < RINGS[0].minPitch()) return "Tilt the phone down toward the object";
        int low = s.filled(0), middle = s.filled(1), top = s.filled(2);
        int lowTotal = RINGS[0].sectors(), middleTotal = RINGS[1].sectors(), topTotal = RINGS[2].sectors();
        if (low >= lowTotal * 0.9 && middle >= middleTotal * 0.9 && top >= topTotal * 0.75)
            return s.filled(3) == 0 ? "Almost done: one photo looking straight down" : "Coverage complete. Add close-ups or tap Done";
        if (need == Need.NEW_VIEW) return "New view: pause here";
        if (need == Need.TURN_ONLY) return "Walk around the object instead of turning the phone";
        Cell here = cell(current);
        // Finish the ring the person is on before asking them to change height.
        if (here != null && here.ring() == 0 && low < lowTotal * 0.9) return "Walk slowly around the object (low ring)";
        if (here != null && here.ring() == 1 && middle < middleTotal * 0.9) return "Walk slowly around the object (middle ring)";
        if (here != null && here.ring() == 2 && top < topTotal * 0.75) return "Walk around while looking down (top ring)";
        if (low < lowTotal * 0.9) return "Lower the phone to the object's height and walk around";
        if (middle < middleTotal * 0.9) return "Raise the phone a little, look down about 35°, walk around";
        return "Raise the phone higher and look down at the object";
    }
}
