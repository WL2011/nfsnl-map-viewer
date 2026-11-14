package com.nfsnl.minimap;

/**
 * Turns the live camera position (from {@link RootReader}) into: which map/track we are on, which race is
 * being run, and where along it we are. Everything is automatic:
 *   map   = the base map whose track lines the camera keeps following while moving
 *   race  = when the car pulls away after standing still, the race whose start line is nearest that spot and
 *           whose direction matches the direction of travel; joined mid-race, the race in that direction
 *           whose span contains the current spot and whose start is nearest behind
 */
final class Locator {
    // ---- outputs (main thread)
    boolean live;                 // fresh pose and recently moving
    boolean onTrack;
    float x, y, z;                // camera world position
    float hx = 0, hz = 1;         // heading (unit, world xz)
    float speed;                  // m/s
    TrackData.MapInfo map;
    TrackData.Track track;
    TrackData.Race race;
    boolean raceGuessed;          // picked mid-race rather than from the start line
    float d;                      // lane-0 distance on `track`
    String status = "等待游戏";

    private final TrackData data;
    private long lastT, lastMoveT, stillSince;
    private float px, pz;
    private boolean havePrev;
    private int idx = -1;                    // nearest lane-0 sample on `track`
    private final int[] hits;                // per-map consecutive on-track readings
    private int evalTick, offTrackCount;
    private float gridX, gridZ;              // where the car last stood still on track
    private boolean gridValid;
    private float startX, startZ;
    private boolean departing;
    private float movedSinceStart;
    private long teleportT, fastSince;

    Locator(TrackData d) {
        data = d;
        hits = new int[d.maps.size()];
    }

    void onStatus(String s) {
        switch (s) {
            case "nogame": status = "游戏未运行"; break;
            case "nolib": status = "找不到游戏模块"; break;
            case "noptr": status = "等待比赛"; break;
            default: status = "读取中…(" + s + ")";
        }
        live = false;
    }

    void onPose(long t, float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
        if (havePrev) {
            float dt = Math.max(0.001f, (t - lastT) / 1000f);
            float dx = x - px, dz = z - pz, dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > 150) {                      // teleport (restart, replay camera, menu)
                gridValid = false; departing = false; race = null; teleportT = t;
            } else {
                float v = dist / dt;
                speed += (v - speed) * 0.3f;
                if (dist > 0.05f && v > 3) {
                    float a = 0.25f;
                    hx += (dx / dist - hx) * a; hz += (dz / dist - hz) * a;
                    float n = (float) Math.sqrt(hx * hx + hz * hz);
                    if (n > 1e-3f) { hx /= n; hz /= n; }
                }
                if (departing) movedSinceStart += dist;
            }
        }
        px = x; pz = z; lastT = t; havePrev = true;

        if (speed > 2) lastMoveT = t;
        live = t - lastMoveT < 8000;

        trackStillness(t);
        locateMap();
        if (track == null) { onTrack = false; status = map == null ? "识别地图中…" : status; return; }
        project();
        raceLogic(t);
        status = race == null ? map.title() : race.title() + (raceGuessed ? "?" : "");
    }

    // ------------------------------------------------------------------ map / track
    private void locateMap() {
        if (track != null) {
            float dd = nearestDist(track, x, z, true);
            if (dd < 40) { offTrackCount = 0; onTrack = true; }
            else if (++offTrackCount > 60) { map = null; track = null; race = null; idx = -1; onTrack = false; }
            else onTrack = false;
            // tracks of one map can share roads: switch only if another track is clearly closer
            if (map != null && map.tracks.size() > 1 && (++evalTick % 10) == 0 && dd > 15) {
                for (TrackData.Track t : map.tracks) {
                    if (t == track) continue;
                    float o = nearestDist(t, x, z, false);
                    if (o + 10 < dd) { track = t; idx = -1; race = null; break; }
                }
            }
            if (track != null) return;
        }
        if (speed < 3) return;
        if ((++evalTick % 4) != 0) return;
        int best = -1, bestHits = 0;
        for (int m = 0; m < hits.length; m++) {
            float dm = Float.MAX_VALUE;
            for (TrackData.Track t : data.maps.get(m).tracks) dm = Math.min(dm, nearestDist(t, x, z, false));
            hits[m] = dm < 25 ? hits[m] + 1 : 0;
            if (hits[m] > bestHits) { bestHits = hits[m]; best = m; }
        }
        if (best >= 0 && bestHits >= 8) {
            int second = 0;
            for (int m = 0; m < hits.length; m++) if (m != best) second = Math.max(second, hits[m]);
            if (second < bestHits - 4) {
                map = data.maps.get(best);
                TrackData.Track bt = null; float bd = Float.MAX_VALUE;
                for (TrackData.Track t : map.tracks) { float o = nearestDist(t, x, z, false); if (o < bd) { bd = o; bt = t; } }
                track = bt; idx = -1; race = null; offTrackCount = 0;
                java.util.Arrays.fill(hits, 0);
            }
        }
    }

    /** Distance from (x,z) to the track's lane-0 samples; `local` searches around the last index. */
    private float nearestDist(TrackData.Track t, float x, float z, boolean local) {
        if (x < t.minX - 60 || x > t.maxX + 60 || z < t.minZ - 60 || z > t.maxZ + 60) return Float.MAX_VALUE;
        int n = t.n(), lo = 0, hi = n;
        if (local && idx >= 0) { lo = Math.max(0, idx - 80); hi = Math.min(n, idx + 80); }
        float best = Float.MAX_VALUE; int bi = -1;
        for (int i = lo; i < hi; i++) {
            float dx = t.x[i] - x, dz = t.z[i] - z, q = dx * dx + dz * dz;
            if (q < best) { best = q; bi = i; }
        }
        if (local && bi >= 0) {
            float r = (float) Math.sqrt(best);
            if (r > 40 && (lo > 0 || hi < n)) return nearestDist(t, x, z, false);
            if (t == track) idx = bi;
            return r;
        }
        if (t == track && bi >= 0) idx = bi;
        return (float) Math.sqrt(best);
    }

    /** Lane-0 distance: nearest sample refined by projecting onto the neighbouring segment. */
    private void project() {
        TrackData.Track t = track;
        if (idx < 0) nearestDist(t, x, z, false);
        int i = Math.max(0, Math.min(t.n() - 2, idx));
        int j = i;
        if (i > 0) {
            float a = seg(t, i - 1, x, z), b = seg(t, i, x, z);
            if (a < b) j = i - 1;
        }
        float ax = t.x[j], az = t.z[j], bx = t.x[j + 1] - ax, bz = t.z[j + 1] - az;
        float u = ((x - ax) * bx + (z - az) * bz) / Math.max(1e-6f, bx * bx + bz * bz);
        u = Math.max(0, Math.min(1, u));
        d = (j + u) * t.step;
    }

    private static float seg(TrackData.Track t, int j, float x, float z) {
        float ax = t.x[j], az = t.z[j], bx = t.x[j + 1] - ax, bz = t.z[j + 1] - az;
        float u = ((x - ax) * bx + (z - az) * bz) / Math.max(1e-6f, bx * bx + bz * bz);
        u = Math.max(0, Math.min(1, u));
        float qx = ax + bx * u - x, qz = az + bz * u - z;
        return qx * qx + qz * qz;
    }

    // ------------------------------------------------------------------ race
    /** Standing still = on the grid (or stopped); pulling away from there starts a departure. Runs before the map is known. */
    private void trackStillness(long t) {
        if (speed < 1.0f) {
            if (stillSince == 0) stillSince = t;
            if (t - stillSince > 1200) { gridX = x; gridZ = z; gridValid = true; departing = false; }
        } else {
            if (stillSince != 0 && gridValid && !departing) {
                departing = true; movedSinceStart = 0; startX = gridX; startZ = gridZ;
            }
            stillSince = 0;
        }
        if (speed > 25) { if (fastSince == 0) fastSince = t; } else fastSince = 0;
        if (departing && movedSinceStart > 3000) { departing = false; gridValid = false; }   // too long ago to trust
    }

    private void raceLogic(long t) {
        // pulled away from the grid: decide once we have a direction
        if (departing && movedSinceStart > 40) {
            departing = false; gridValid = false;
            int dir = directionSign();
            TrackData.Race best = null; TrackData.Track bestT = null; float bestCost = 250;
            for (TrackData.Track tr : map.tracks) {
                float[] sd = projectOn(tr, startX, startZ);
                if (sd[1] > 40) continue;
                for (TrackData.Race r : tr.races) {
                    int rd = r.s1 >= r.s0 ? 1 : -1;
                    if (rd != dirOn(tr, dir)) continue;
                    float cost = Math.abs(r.s0 - sd[0]) + sd[1];
                    if (cost < bestCost) { bestCost = cost; best = r; bestT = tr; }
                }
            }
            if (best != null) {
                race = best; raceGuessed = false;
                if (bestT != track) { track = bestT; idx = -1; project(); }
            }
        }
        // no start seen (joined mid-race): guess from position and direction
        if (race == null && fastSince != 0 && t - fastSince > 2000 && t - teleportT > 5000 && !departing) {
            int dir = directionSign();
            TrackData.Race best = null; float bestBack = Float.MAX_VALUE;
            for (TrackData.Race r : track.races) {
                int rd = r.s1 >= r.s0 ? 1 : -1;
                if (rd != dir) continue;
                float lo = Math.min(r.s0, r.s1), hi = Math.max(r.s0, r.s1);
                if (d < lo - 20 || d > hi + 20) continue;
                float back = (d - r.s0) * rd;            // metres since that race's start
                if (back >= -20 && back < bestBack) { bestBack = back; best = r; }
            }
            if (best != null) { race = best; raceGuessed = true; }
        }
        // well past the finish: race over
        if (race != null) {
            int rd = race.s1 >= race.s0 ? 1 : -1;
            if ((d - race.s1) * rd > 400) race = null;
        }
    }

    /** +1 if moving towards larger lane-0 distance on the current track. */
    private int directionSign() {
        int i = Math.max(0, Math.min(track.n() - 2, idx));
        float tx = track.x[i + 1] - track.x[i], tz = track.z[i + 1] - track.z[i];
        return tx * hx + tz * hz >= 0 ? 1 : -1;
    }

    /** Direction sign transferred to another track (tracks may run either way along shared roads). */
    private int dirOn(TrackData.Track tr, int dirOnCurrent) {
        if (tr == track) return dirOnCurrent;
        float[] sd = projectOn(tr, x, z);
        int i = Math.max(0, Math.min(tr.n() - 2, Math.round(sd[0] / tr.step)));
        float tx = tr.x[i + 1] - tr.x[i], tz = tr.z[i + 1] - tr.z[i];
        return tx * hx + tz * hz >= 0 ? 1 : -1;
    }

    /** {lane-0 distance, offset} of a point on any track (full search). */
    private static float[] projectOn(TrackData.Track t, float x, float z) {
        float best = Float.MAX_VALUE; int bi = 0;
        for (int i = 0; i < t.n(); i++) {
            float dx = t.x[i] - x, dz = t.z[i] - z, q = dx * dx + dz * dz;
            if (q < best) { best = q; bi = i; }
        }
        return new float[]{bi * t.step, (float) Math.sqrt(best)};
    }

    /** Fraction of the current race done (0..1), or -1. */
    float raceFraction() {
        if (race == null) return -1;
        float span = race.s1 - race.s0;
        if (Math.abs(span) < 1) return -1;
        return Math.max(0, Math.min(1, (d - race.s0) / span));
    }
}
