package com.nfsnl.minimap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the race HUD from a captured RGBA frame: the "PROGRESS nn %" number (top-left) and the speed
 * number on the speedometer (bottom-right). Regions are anchored to the screen corners and sized by the
 * frame height, which is how the game lays out its HUD. Digits are white; they are split into connected
 * components and matched against the DIN Condensed templates in {@link Glyphs}.
 */
final class HudReader {
    static final class Result {
        boolean hud;      // PROGRESS label seen
        int pct = -1;     // 0..100 or -1
        int speed = -1;   // km/h or -1
        float shift = Float.NaN;  // horizontal scenery shift since the previous frame, fraction of width (+ = scenery moved right = turning left)
    }

    // ---- scenery shift (yaw proxy): column-luminance profile of a band above the car, matched against the previous frame
    private static final int COLS = 320, MAXS = 80;
    private float[] prof = new float[COLS], prev = new float[COLS], tmpP = new float[COLS];
    private boolean havePrev;

    void resetMotion() { havePrev = false; }

    private float motion(ByteBuffer buf, int rs, int ps, int W, int H) {
        int y0 = Math.round(0.22f * H), y1 = Math.round(0.45f * H);
        ensureRow(W * ps);
        java.util.Arrays.fill(tmpP, 0);
        int rows = 0;
        for (int y = y0; y < y1; y += 3, rows++) {
            buf.position(y * rs);
            buf.get(row, 0, W * ps);
            for (int c = 0; c < COLS; c++) {
                int o = (c * W / COLS) * ps;
                tmpP[c] += (row[o] & 255) * 0.3f + (row[o + 1] & 255) * 0.59f + (row[o + 2] & 255) * 0.11f;
            }
        }
        // high-pass: subtract a 21-column moving average, then normalise
        float mean = 0, var = 0;
        for (int c = 0; c < COLS; c++) {
            float a = 0; int n = 0;
            for (int k = Math.max(0, c - 10); k <= Math.min(COLS - 1, c + 10); k++) { a += tmpP[k]; n++; }
            prof[c] = (tmpP[c] - a / n) / rows;
        }
        float res = Float.NaN;
        if (havePrev) {
            float best = -2; int bs = 0;
            for (int sft = -MAXS; sft <= MAXS; sft++) {
                float sab = 0, saa = 0, sbb = 0;
                for (int c = Math.max(0, sft); c < Math.min(COLS, COLS + sft); c++) {
                    float a = prof[c], b = prev[c - sft];
                    sab += a * b; saa += a * a; sbb += b * b;
                }
                float r = sab / (float) Math.sqrt(saa * sbb + 1e-6f);
                if (r > best) { best = r; bs = sft; }
            }
            if (best > 0.5f) res = bs / (float) COLS;
        }
        float[] t = prev; prev = prof; prof = t; havePrev = true;
        return res;
    }

    private static final float MAX_ERR = 0.09f;

    private byte[] row = new byte[0];
    private boolean[] mask = new boolean[0];
    private int[] lab = new int[0], stack = new int[0];
    private final float[] cell = new float[Glyphs.W * Glyphs.H];

    Result read(ByteBuffer buf, int rowStride, int ps, int W, int H) {
        Result r = new Result();
        // cyan "PROGRESS" label
        r.hud = cyanRatio(buf, rowStride, ps, ax(0.16f, H), ax(0.29f, H), ax(0.065f, H), ax(0.11f, H)) > 0.04f;
        if (r.hud) r.pct = number(buf, rowStride, ps, H, ax(0.13f, H), ax(0.45f, H), ax(0.10f, H), ax(0.21f, H));
        if (r.pct > 100) r.pct = -1;
        r.speed = number(buf, rowStride, ps, H, W - ax(0.34f, H), W - ax(0.15f, H), ax(0.76f, H), ax(0.875f, H));
        r.shift = motion(buf, rowStride, ps, W, H);
        return r;
    }

    private static int ax(float f, int H) { return Math.round(f * H); }

    private float cyanRatio(ByteBuffer buf, int rs, int ps, int x0, int x1, int y0, int y1) {
        int w = x1 - x0, n = 0, hit = 0;
        ensureRow(w * ps);
        for (int y = y0; y < y1; y++) {
            buf.position(y * rs + x0 * ps);
            buf.get(row, 0, w * ps);
            for (int x = 0; x < w; x++) {
                int o = x * ps, R = row[o] & 255, G = row[o + 1] & 255, B = row[o + 2] & 255;
                if (B > 180 && G > 140 && R < 140 && B - R > 80) hit++;
                n++;
            }
        }
        return n == 0 ? 0 : hit / (float) n;
    }

    private void ensureRow(int n) { if (row.length < n) row = new byte[n]; }

    /** White digits in the box, left to right, stopping at the first big gap. -1 if unreadable. */
    private int number(ByteBuffer buf, int rs, int ps, int H, int x0, int x1, int y0, int y1) {
        int w = x1 - x0, h = y1 - y0, n = w * h;
        if (w <= 0 || h <= 0) return -1;
        if (mask.length < n) { mask = new boolean[n]; lab = new int[n]; stack = new int[n]; }
        ensureRow(w * ps);
        for (int y = 0; y < h; y++) {
            buf.position((y0 + y) * rs + x0 * ps);
            buf.get(row, 0, w * ps);
            for (int x = 0; x < w; x++) {
                int o = x * ps, R = row[o] & 255, G = row[o + 1] & 255, B = row[o + 2] & 255;
                int mn = Math.min(R, Math.min(G, B)), mx = Math.max(R, Math.max(G, B));
                mask[y * w + x] = mn > 175 && mx - mn < 70;
                lab[y * w + x] = 0;
            }
        }
        // connected components (4-neighbour flood fill)
        List<int[]> comps = new ArrayList<>();
        int next = 0;
        int hmin = Math.round(0.055f * H), hmax = Math.round(0.095f * H);
        for (int i = 0; i < n; i++) {
            if (!mask[i] || lab[i] != 0) continue;
            int id = ++next, sp = 0, bx0 = w, bx1 = -1, by0 = h, by1 = -1;
            stack[sp++] = i; lab[i] = id;
            while (sp > 0) {
                int p = stack[--sp], px = p % w, py = p / w;
                if (px < bx0) bx0 = px; if (px > bx1) bx1 = px;
                if (py < by0) by0 = py; if (py > by1) by1 = py;
                if (px > 0 && mask[p - 1] && lab[p - 1] == 0) { lab[p - 1] = id; stack[sp++] = p - 1; }
                if (px < w - 1 && mask[p + 1] && lab[p + 1] == 0) { lab[p + 1] = id; stack[sp++] = p + 1; }
                if (py > 0 && mask[p - w] && lab[p - w] == 0) { lab[p - w] = id; stack[sp++] = p - w; }
                if (py < h - 1 && mask[p + w] && lab[p + w] == 0) { lab[p + w] = id; stack[sp++] = p + w; }
            }
            int gh = by1 - by0 + 1;
            if (gh >= hmin && gh <= hmax) comps.add(new int[]{bx0, by0, bx1 - bx0 + 1, gh, id});
        }
        if (comps.isEmpty()) return -1;
        Collections.sort(comps, (a, b) -> Integer.compare(a[0], b[0]));
        int value = 0, digits = 0, prevEnd = -1;
        for (int[] c : comps) {
            if (prevEnd >= 0 && c[0] - prevEnd > 0.35f * c[3]) break;
            int d = classify(w, c[0], c[1], c[2], c[3], c[4]);
            if (d < 0) return -1;
            value = value * 10 + d;
            if (++digits > 3) return -1;
            prevEnd = c[0] + c[2];
        }
        return value;
    }

    /** Area-average the glyph into the template grid and pick the nearest template. */
    private int classify(int stride, int x, int y, int w, int h, int id) {
        int GW = Glyphs.W, GH = Glyphs.H;
        for (int gy = 0; gy < GH; gy++) {
            int ya = gy * h / GH, yb = Math.max(ya + 1, (gy + 1) * h / GH);
            for (int gx = 0; gx < GW; gx++) {
                int xa = gx * w / GW, xb = Math.max(xa + 1, (gx + 1) * w / GW), on = 0, all = 0;
                for (int yy = ya; yy < yb; yy++)
                    for (int xx = xa; xx < xb; xx++) {
                        if (lab[(y + yy) * stride + x + xx] == id) on++;
                        all++;
                    }
                cell[gy * GW + gx] = on / (float) all;
            }
        }
        int best = -1;
        float be = Float.MAX_VALUE;
        for (int d = 0; d < 10; d++) {
            float[] t = Glyphs.T[d];
            float e = 0;
            for (int i = 0; i < cell.length; i++) { float q = cell[i] - t[i]; e += q * q; }
            e /= cell.length;
            if (e < be) { be = e; best = d; }
        }
        return be <= MAX_ERR ? best : -1;
    }
}
