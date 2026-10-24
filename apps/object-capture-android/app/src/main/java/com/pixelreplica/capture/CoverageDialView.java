package com.pixelreplica.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.function.Supplier;

/**
 * The capture dome seen from above: outer ring is low views, centre is straight down.
 * The dial turns with the camera so the view you are taking is always at the bottom; sectors to
 * the right are reached by walking right around the object.
 */
final class CoverageDialView extends View {
    private static final int FILLED = Color.argb(235, 61, 220, 132), EMPTY = Color.argb(70, 255, 255, 255),
            HERE = Color.rgb(255, 213, 79);
    // Inner and outer radius of each ring as a fraction of the dial radius, matching OrbitCoverage.RINGS.
    private static final float[][] BANDS = {{.70f, .97f}, {.44f, .67f}, {.20f, .41f}};
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path marker = new Path();
    private OrbitCoverage coverage;
    private Supplier<OrbitCoverage.View> current;
    private final Runnable tick = new Runnable() {
        @Override public void run() { invalidate(); postDelayed(this, 100); }
    };

    CoverageDialView(Context context) {
        super(context);
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeCap(Paint.Cap.BUTT);
    }

    void bind(OrbitCoverage coverage, Supplier<OrbitCoverage.View> current) {
        this.coverage = coverage; this.current = current; invalidate();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        if (coverage == null) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = Math.min(cx, cy) * .86f;
        disc.setStyle(Paint.Style.FILL); disc.setColor(Color.argb(150, 8, 12, 20));
        canvas.drawCircle(cx, cy, radius * 1.06f, disc);
        OrbitCoverage.Snapshot snapshot = coverage.snapshot();
        OrbitCoverage.View view = current == null ? null : current.get();
        OrbitCoverage.Cell here = OrbitCoverage.cell(view);
        float heading = view == null ? 0 : (float) view.headingDeg();
        for (int r = 0; r < BANDS.length; r++) {
            int sectors = OrbitCoverage.RINGS[r].sectors();
            float width = 360f / sectors, gap = Math.min(2.5f, width * .15f);
            float inner = BANDS[r][0] * radius, outer = BANDS[r][1] * radius, middle = (inner + outer) / 2;
            arc.setStrokeWidth(outer - inner);
            oval.set(cx - middle, cy - middle, cx + middle, cy + middle);
            for (int i = 0; i < sectors; i++) {
                boolean isHere = here != null && here.ring() == r && here.sector() == i;
                arc.setColor(isHere && !snapshot.filled()[r][i] ? HERE : snapshot.filled()[r][i] ? FILLED : EMPTY);
                // Canvas angles run clockwise from 3 o'clock; the current heading lands at 6 o'clock.
                canvas.drawArc(oval, 90 + i * width - heading + gap / 2, width - gap, false, arc);
                if (isHere && snapshot.filled()[r][i]) {
                    arc.setColor(HERE); arc.setStrokeWidth(Math.max(3, (outer - inner) * .25f));
                    canvas.drawArc(oval, 90 + i * width - heading + gap / 2, width - gap, false, arc);
                    arc.setStrokeWidth(outer - inner);
                }
            }
        }
        boolean overheadFilled = snapshot.filled()[3][0], overheadHere = here != null && here.ring() == 3;
        disc.setColor(overheadHere && !overheadFilled ? HERE : overheadFilled ? FILLED : EMPTY);
        canvas.drawCircle(cx, cy, radius * .15f, disc);
        // "You are here" marker just outside the bottom of the dial.
        float tip = cy + radius * 1.0f, size = radius * .09f;
        marker.reset(); marker.moveTo(cx, tip); marker.lineTo(cx - size, tip + size * 1.4f); marker.lineTo(cx + size, tip + size * 1.4f); marker.close();
        disc.setColor(Color.WHITE); canvas.drawPath(marker, disc);
    }
}
