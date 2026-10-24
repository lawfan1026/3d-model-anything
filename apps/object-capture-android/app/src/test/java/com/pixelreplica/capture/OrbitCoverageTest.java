package com.pixelreplica.capture;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OrbitCoverageTest {
    /** Camera 30 cm from an object at the origin, looking at it along heading/pitch (column-major). */
    static float[] looking(double headingDeg, double pitchDeg) { return looking(headingDeg, pitchDeg, 0.3); }
    static float[] looking(double headingDeg, double pitchDeg, double radius) {
        double h = Math.toRadians(headingDeg), p = Math.toRadians(pitchDeg);
        double fx = Math.sin(h) * Math.cos(p), fy = -Math.sin(p), fz = -Math.cos(h) * Math.cos(p);
        return new float[]{1,0,0,0, 0,1,0,0, (float)-fx,(float)-fy,(float)-fz,0,
                (float)(-fx * radius),(float)(-fy * radius),(float)(-fz * radius),1};
    }
    static float[] turningInPlace(double headingDeg) {
        float[] pose = looking(headingDeg, 10); pose[12] = 0.5f; pose[13] = 0.05f; pose[14] = 0.5f; return pose;
    }

    @Test public void viewUsesOpticalAxisInGravityAlignedWorld() {
        OrbitCoverage.View ahead = OrbitCoverage.view(new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1});
        assertEquals(0, ahead.headingDeg(), 1e-6); assertEquals(0, ahead.pitchDeg(), 1e-6);
        OrbitCoverage.View east = OrbitCoverage.view(looking(90, 30));
        assertEquals(90, east.headingDeg(), 1e-4); assertEquals(30, east.pitchDeg(), 1e-4);
        assertEquals(270, OrbitCoverage.view(looking(-90, 10)).headingDeg(), 1e-4);
        assertNull(OrbitCoverage.view(new float[16]));
    }

    @Test public void cellsFollowRingBoundariesAndWrapHeading() {
        assertEquals(new OrbitCoverage.Cell(0, 0), OrbitCoverage.cell(OrbitCoverage.view(looking(0, 5))));
        assertEquals(new OrbitCoverage.Cell(0, 23), OrbitCoverage.cell(OrbitCoverage.view(looking(359.5, 5))));
        assertEquals(new OrbitCoverage.Cell(1, 6), OrbitCoverage.cell(OrbitCoverage.view(looking(91, 30))));
        assertEquals(new OrbitCoverage.Cell(2, 3), OrbitCoverage.cell(OrbitCoverage.view(looking(91, 60))));
        assertEquals(new OrbitCoverage.Cell(3, 0), OrbitCoverage.cell(OrbitCoverage.view(looking(200, 85))));
        assertNull("pointing up at the ceiling is not an object view", OrbitCoverage.cell(OrbitCoverage.view(looking(0, -40))));
    }

    @Test public void walkingAroundFillsRingWithoutNearDuplicates() {
        OrbitCoverage coverage = new OrbitCoverage(); coverage.reset("world-1");
        int shots = 0;
        for (double heading = 0; heading < 720; heading += 1.5) {   // two laps; the second adds nothing
            float[] pose = looking(heading % 360, 12);
            if (coverage.wantsShot("world-1", pose)) {
                assertTrue(coverage.nearestShotDegrees(pose) >= OrbitCoverage.MIN_SEPARATION_DEG);
                assertTrue(coverage.add("world-1", pose)); shots++;
            }
        }
        OrbitCoverage.Snapshot snapshot = coverage.snapshot();
        assertEquals(24, snapshot.filled(0)); assertEquals(0, snapshot.filled(1));
        assertEquals(24, shots); assertEquals(24, snapshot.photos());
        assertFalse(coverage.wantsShot("world-1", looking(100, 12)));
        assertTrue("same heading, higher ring is a new view", coverage.wantsShot("world-1", looking(100, 35)));
    }

    @Test public void turningThePhoneInPlaceIsNotANewView() {
        OrbitCoverage coverage = new OrbitCoverage(); coverage.reset("w");
        assertTrue(coverage.add("w", turningInPlace(0)));
        assertEquals(OrbitCoverage.Need.TURN_ONLY, coverage.need("w", turningInPlace(60)));
        assertFalse(coverage.wantsShot("w", turningInPlace(60)));
        assertEquals(OrbitCoverage.Need.NEW_VIEW, coverage.need("w", looking(60, 10)));
        assertEquals(OrbitCoverage.Need.COVERED, coverage.need("w", turningInPlace(3)));
        assertEquals(OrbitCoverage.Need.OFF_DIAL, coverage.need("w", looking(60, -60)));
        assertEquals(OrbitCoverage.Need.OFF_DIAL, coverage.need("other", looking(60, 10)));
        assertEquals("Walk around the object instead of turning the phone",
                OrbitCoverage.hint(coverage.snapshot(), OrbitCoverage.view(turningInPlace(60)), OrbitCoverage.Need.TURN_ONLY));
    }

    @Test public void restartedTrackingWorldCannotReusePreviousHeadings() {
        OrbitCoverage coverage = new OrbitCoverage(); coverage.reset("world-1");
        assertTrue(coverage.add("world-1", looking(10, 10)));
        assertFalse(coverage.add("world-2", looking(40, 10)));
        assertFalse(coverage.wantsShot("world-2", looking(40, 10)));
        coverage.reset("world-2");
        assertEquals(0, coverage.snapshot().filledTotal());
        assertTrue(coverage.wantsShot("world-2", looking(10, 10)));
        assertFalse("nothing is recorded before a world exists", new OrbitCoverage().add(null, looking(0, 0)));
    }

    @Test public void hintGuidesFromLowRingToTopAndCompletion() {
        OrbitCoverage coverage = new OrbitCoverage(); coverage.reset("w");
        assertEquals("Tilt the phone down toward the object", OrbitCoverage.hint(coverage.snapshot(), OrbitCoverage.view(looking(0, -50)), coverage.need("w", looking(0, -50))));
        assertEquals("New view: pause here", OrbitCoverage.hint(coverage.snapshot(), OrbitCoverage.view(looking(0, 10)), coverage.need("w", looking(0, 10))));
        for (int i = 0; i < 24; i++) coverage.add("w", looking(i * 15 + 7, 10));
        assertTrue(OrbitCoverage.hint(coverage.snapshot(), OrbitCoverage.view(looking(7, 10)), coverage.need("w", looking(7, 10))).startsWith("Raise the phone a little"));
        for (int i = 0; i < 24; i++) coverage.add("w", looking(i * 15 + 7, 35));
        for (int i = 0; i < 12; i++) coverage.add("w", looking(i * 30 + 7, 60));
        assertEquals("Almost done: one photo looking straight down", OrbitCoverage.hint(coverage.snapshot(), OrbitCoverage.view(looking(7, 60)), coverage.need("w", looking(7, 60))));
        coverage.add("w", looking(0, 88));
        assertEquals(1.0, coverage.snapshot().fraction(), 1e-9);
        assertEquals("Coverage complete. Add close-ups or tap Done", OrbitCoverage.hint(coverage.snapshot(), OrbitCoverage.view(looking(7, 60)), coverage.need("w", looking(7, 60))));
    }
}
