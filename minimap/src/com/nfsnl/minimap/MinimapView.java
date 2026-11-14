package com.nfsnl.minimap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * The floating minimap. While a race HUD is on screen it follows the car heading-up; otherwise (or when
 * tapped) it shows the whole race north-up. Start pin is white, finish pin black, as in the web viewer.
 */
final class MinimapView extends View {
    private static final int ACCENT = 0xFFFF7A1A;

    final Progress progress = new Progress();
    private TrackData.Track track;
    private TrackData.Race race;
    private float radiusM = 350;     // follow-mode view radius
    private float alpha = 0.85f;
    private boolean mirror, debug, overview;

    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG), route = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint done = new Paint(Paint.ANTI_ALIAS_FLAG), pinFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinRing = new Paint(Paint.ANTI_ALIAS_FLAG), car = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carEdge = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trackPath = new Path(), routePath = new Path(), donePath = new Path(), clip = new Path();
    private final Path carShape = new Path();
    private final RectF rect = new RectF();
    private final float[] tmp = new float[2], tmp2 = new float[2];
    private float routeMinX, routeMaxX, routeMinY, routeMaxY;
    private float heading = Float.NaN;   // smoothed, radians clockwise from up
    private long lastFrame;
    private HudReader.Result lastRead;
    private Locator locator;              // memory mode (root); null = screen-reading mode
    private float dispX = Float.NaN, dispZ;

    void setLocator(Locator l) { locator = l; invalidate(); }

    void onPose() {
        if (locator != null && locator.live) postInvalidateOnAnimation(); else invalidate();
    }

    MinimapView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1 * dp);
        for (Paint p : new Paint[]{road, route, done}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        road.setColor(0xFF4A515E);
        route.setColor(ACCENT);
        done.setColor(0x66FF7A1A);
        pinRing.setStyle(Paint.Style.STROKE);
        car.setColor(Color.WHITE);
        carEdge.setColor(0xFF111317);
        carEdge.setStyle(Paint.Style.STROKE);
        carEdge.setStrokeWidth(1.5f * dp);
        carEdge.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(0xFFE8EAEE);
        text.setTextSize(11 * dp);
        text.setFakeBoldText(true);
        textBg.setColor(0x99000000);
        carShape.moveTo(0, -9 * dp);
        carShape.lineTo(6.5f * dp, 7 * dp);
        carShape.lineTo(0, 3.5f * dp);
        carShape.lineTo(-6.5f * dp, 7 * dp);
        carShape.close();
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void configure(TrackData.Track t, TrackData.Race r, float radiusM, float alpha, boolean mirror, boolean debug) {
        boolean changed = t != track || r != race || mirror != this.mirror;
        this.radiusM = radiusM;
        this.alpha = alpha;
        this.debug = debug;
        this.mirror = mirror;
        if (changed) {
            track = t;
            race = r;
            buildPaths();
            progress.reset(r == null ? 1 : Math.abs(r.s1 - r.s0));
            heading = Float.NaN;
        }
        invalidate();
    }

    void toggleOverview() { overview = !overview; invalidate(); }

    void onReading(HudReader.Result r) {
        lastRead = r;
        boolean was = progress.active;
        progress.onReading(r, SystemClock.uptimeMillis());
        if (was != progress.active || debug) invalidate();
        if (progress.active && !was) postInvalidateOnAnimation();
    }

    private float mx(float x) { return mirror ? -x : x; }

    private void buildPaths() {
        trackPath.rewind();
        routePath.rewind();
        if (track == null) return;
        int n = track.n();
        for (int i = 0; i < n; i++) {
            if (i == 0) trackPath.moveTo(mx(track.x[i]), track.z[i]);
            else trackPath.lineTo(mx(track.x[i]), track.z[i]);
        }
        float a = race == null ? 0 : race.s0, b = race == null ? track.len : race.s1;
        routeMinX = routeMinY = Float.MAX_VALUE;
        routeMaxX = routeMaxY = -Float.MAX_VALUE;
        appendSpan(routePath, a, b, true);
    }

    /** Lane-0 polyline from distance a to distance b (either direction) appended to p. */
    private void appendSpan(Path p, float a, float b, boolean bounds) {
        float step = track.step;
        int dir = b >= a ? 1 : -1;
        track.at(a, tmp);
        p.moveTo(mx(tmp[0]), tmp[1]);
        if (bounds) grow(mx(tmp[0]), tmp[1]);
        int i = dir > 0 ? (int) Math.floor(a / step) + 1 : (int) Math.ceil(a / step) - 1;
        for (; dir > 0 ? i * step < b : i * step > b; i += dir) {
            if (i < 0 || i >= track.n()) break;
            p.lineTo(mx(track.x[i]), track.z[i]);
            if (bounds) grow(mx(track.x[i]), track.z[i]);
        }
        track.at(b, tmp);
        p.lineTo(mx(tmp[0]), tmp[1]);
        if (bounds) grow(mx(tmp[0]), tmp[1]);
    }

    private void grow(float x, float y) {
        routeMinX = Math.min(routeMinX, x); routeMaxX = Math.max(routeMaxX, x);
        routeMinY = Math.min(routeMinY, y); routeMaxY = Math.max(routeMaxY, y);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float r = 14 * dp;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        progress.tick(dt, now);
        final boolean mem = locator != null;
        if (mem && (locator.track != track || locator.race != race)) {
            track = locator.track;
            race = locator.race;
            buildPaths();
        }
        final boolean active = mem ? locator.live && locator.track != null : progress.active;

        rect.set(0, 0, w, h);
        clip.rewind();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        bg.setColor(Color.argb(Math.round(alpha * 225), 16, 18, 23));
        c.drawRect(rect, bg);

        if (track != null) {
            boolean follow = active && !overview && (mem || race != null);
            float d, px, py, target = 0;
            if (mem) {
                d = locator.d;
                float tx = mx(locator.x), tz = locator.z;
                if (Float.isNaN(dispX) || Math.abs(tx - dispX) + Math.abs(tz - dispZ) > 150) { dispX = tx; dispZ = tz; }
                float k = (float) (1 - Math.exp(-dt * 12));
                dispX += (tx - dispX) * k;
                dispZ += (tz - dispZ) * k;
                px = dispX; py = dispZ;
                target = (float) Math.atan2(mirror ? -locator.hx : locator.hx, -locator.hz);
            } else {
                d = race == null ? 0 : race.s0 + (race.s1 - race.s0) * progress.f;
                track.at(d, tmp);
                px = mx(tmp[0]); py = tmp[1];
                if (race != null) {
                    int dir = race.s1 >= race.s0 ? 1 : -1;
                    track.at(d + dir * 25, tmp2);
                    float ax = mx(tmp2[0]), ay = tmp2[1];
                    track.at(d - dir * 5, tmp2);
                    target = (float) Math.atan2(ax - mx(tmp2[0]), -(ay - tmp2[1]));
                }
            }
            float s;
            c.save();
            if (follow) {
                if (Float.isNaN(heading)) heading = target;
                float diff = (float) Math.atan2(Math.sin(target - heading), Math.cos(target - heading));
                heading += diff * (float) (1 - Math.exp(-dt * 5));
                s = w * 0.5f / radiusM;
                c.translate(w * 0.5f, h * 0.62f);
                c.rotate((float) -Math.toDegrees(heading));
                c.scale(s, s);
                c.translate(-px, -py);
            } else {
                float pad = 18 * dp;
                float bw = Math.max(1, routeMaxX - routeMinX), bh = Math.max(1, routeMaxY - routeMinY);
                s = Math.min((w - 2 * pad) / bw, (h - 2 * pad - 22 * dp) / bh);
                c.translate(w * 0.5f, h * 0.5f - 11 * dp);
                c.scale(s, s);
                c.translate(-(routeMinX + routeMaxX) / 2, -(routeMinY + routeMaxY) / 2);
            }
            float roadPx = Math.max(track.roadW * s, (follow ? 7 : 4) * dp);
            road.setStrokeWidth(roadPx / s);
            route.setStrokeWidth(Math.max(2.5f * dp, roadPx * 0.42f) / s);
            done.setStrokeWidth(route.getStrokeWidth());
            c.drawPath(trackPath, road);
            c.drawPath(routePath, route);
            if (race != null && active) {
                donePath.rewind();
                appendSpan(donePath, race.s0, d, false);
                c.drawPath(donePath, road);
                c.drawPath(donePath, done);
            }
            if (race != null) {
                pin(c, race.s0, Color.WHITE, 0xFF111317, s);
                pin(c, race.s1, Color.BLACK, Color.WHITE, s);
            }
            c.restore();

            if (follow) {
                c.save();
                c.translate(w * 0.5f, h * 0.62f);
                c.drawPath(carShape, car);
                c.drawPath(carShape, carEdge);
                c.restore();
            } else if (active && (mem || race != null)) {
                // car dot in the north-up overview
                float bw = Math.max(1, routeMaxX - routeMinX), bh = Math.max(1, routeMaxY - routeMinY);
                float pad = 18 * dp, so = Math.min((w - 2 * pad) / bw, (h - 2 * pad - 22 * dp) / bh);
                float cx = w * 0.5f + (px - (routeMinX + routeMaxX) / 2) * so;
                float cy = h * 0.5f - 11 * dp + (py - (routeMinY + routeMaxY) / 2) * so;
                c.drawCircle(cx, cy, 5 * dp, car);
                c.drawCircle(cx, cy, 5 * dp, carEdge);
            }
            label(c, w, h, active, d);
        } else {
            label(c, w, h, false, 0);
        }
        c.restore();
        border.setColor(Color.argb(Math.round(alpha * 90), 255, 255, 255));
        rect.set(0, 0, w, h);
        rect.inset(0.5f * dp, 0.5f * dp);
        c.drawRoundRect(rect, r, r, border);

        if (active) postInvalidateOnAnimation();
    }

    private void pin(Canvas c, float d, int fill, int ring, float s) {
        track.at(d, tmp);
        float x = mx(tmp[0]), y = tmp[1], rad = 5.5f * dp / s;
        pinFill.setColor(fill);
        pinRing.setColor(ring);
        pinRing.setStrokeWidth(1.8f * dp / s);
        c.drawCircle(x, y, rad, pinFill);
        c.drawCircle(x, y, rad, pinRing);
    }

    private void label(Canvas c, int w, int h, boolean active, float d) {
        String s;
        if (locator != null) {
            s = locator.status;
            if (race != null && active) {
                float left = Math.abs(race.s1 - d);
                s += " · 剩 " + (left >= 1000 ? String.format("%.2f km", left / 1000f) : Math.round(left) + " m");
            }
            if (debug) s += String.format("  [%.0f,%.0f %.0fkm/h d%.0f]", locator.x, locator.z, locator.speed * 3.6f, locator.d);
        } else if (race == null) s = "未选择比赛";
        else if (!progress.active) s = race.title() + " · 等待比赛";
        else {
            float left = Math.abs(race.s1 - race.s0) * (1 - progress.f);
            s = race.title() + " · 剩 " + (left >= 1000 ? String.format("%.2f km", left / 1000f) : Math.round(left) + " m");
        }
        if (debug) {
            HudReader.Result r = lastRead;
            s += "  [" + (r == null ? "-" : (r.hud ? "HUD " : "") + r.pct + "% " + r.speed + "km/h") + "]";
        }
        float tw = text.measureText(s), pad = 6 * dp, th = 16 * dp;
        float x = Math.max(pad, (w - tw) / 2 - pad), y = h - th - 5 * dp;
        rect.set(x, y, Math.min(w - pad, x + tw + 2 * pad), y + th);
        c.drawRoundRect(rect, th / 2, th / 2, textBg);
        c.save();
        c.clipRect(rect);
        c.drawText(s, x + pad, y + th - 4.5f * dp, text);
        c.restore();
    }
}
