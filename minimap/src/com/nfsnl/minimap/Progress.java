package com.nfsnl.minimap;

/**
 * Race progress estimate. The HUD gives whole percent only, so between percent steps the position is
 * advanced with the speed read off the speedometer (or, failing that, the recent rate of percent steps),
 * and every percent reading clamps it back into the band it allows.
 */
final class Progress {
    boolean active;      // a race HUD is on screen
    float f;             // fraction of the race done, 0..1
    int pct = -1;        // last accepted HUD percent
    float kmh = -1;      // last accepted HUD speed

    private float routeLen = 1;
    private int rawPct = -1, rawSpeed = -1;
    private long lastHud, lastSpeed, lastPctChange;
    private float pctRate;   // m/s from percent steps

    void reset(float routeLen) {
        this.routeLen = Math.max(1, routeLen);
        active = false; f = 0; pct = -1; kmh = -1; rawPct = -1; rawSpeed = -1; pctRate = 0;
    }

    void onReading(HudReader.Result r, long now) {
        if (r.speed >= 0 && r.speed <= 600) {
            if (kmh < 0 || Math.abs(r.speed - kmh) < 80 || r.speed == rawSpeed) { kmh = r.speed; lastSpeed = now; }
            rawSpeed = r.speed;
        }
        if (!r.hud || r.pct < 0) {
            if (active && now - lastHud > 2500) active = false;
            return;
        }
        lastHud = now;
        int p = r.pct;
        if (!active) {
            if (p == rawPct) {                       // two equal reads in a row: start tracking
                active = true; pct = p; f = p / 100f; lastPctChange = now; pctRate = 0;
            }
        } else if (p >= pct && p <= pct + 3) {
            if (p > pct) {
                long dt = now - lastPctChange;
                if (dt > 0 && dt < 20000) pctRate = (p - pct) * 0.01f * routeLen / (dt / 1000f);
                lastPctChange = now;
                pct = p;
            }
        } else if (p == rawPct) {                   // confirmed jump: restart or a new race
            pct = p; f = p / 100f; lastPctChange = now; pctRate = 0;
        }
        rawPct = p;
    }

    /** Advance by dt seconds. */
    void tick(float dt, long now) {
        if (!active) return;
        float v = (kmh >= 0 && now - lastSpeed < 1500) ? kmh / 3.6f : pctRate;
        f += v * dt / routeLen;
        float lo = (pct - 0.5f) / 100f, hi = Math.min(1f, (pct + 1f) / 100f - 0.0005f);
        if (pct >= 100) lo = hi = 1f;
        if (f < lo) f = lo;
        if (f > hi) f = hi;
        if (f < 0) f = 0;
    }
}
