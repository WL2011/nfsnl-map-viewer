package com.nfsnl.minimap;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved choices: which race, minimap size/opacity/zoom, overlay position. */
final class Settings {
    int map, track, race;
    int sizeDp = 220, alphaPct = 85, radiusM = 350;
    boolean mirror, debug;
    int posX = -1, posY = -1;   // px from bottom-left; -1 = default

    static Settings load(Context c) {
        SharedPreferences p = prefs(c);
        Settings s = new Settings();
        s.map = p.getInt("map", 0);
        s.track = p.getInt("track", 0);
        s.race = p.getInt("race", 0);
        s.sizeDp = p.getInt("size", 220);
        s.alphaPct = p.getInt("alpha", 85);
        s.radiusM = p.getInt("radius", 350);
        s.mirror = p.getBoolean("mirror", false);
        s.debug = p.getBoolean("debug", false);
        s.posX = p.getInt("posX", -1);
        s.posY = p.getInt("posY", -1);
        return s;
    }

    void save(Context c) {
        prefs(c).edit().putInt("map", map).putInt("track", track).putInt("race", race)
                .putInt("size", sizeDp).putInt("alpha", alphaPct).putInt("radius", radiusM)
                .putBoolean("mirror", mirror).putBoolean("debug", debug).apply();
    }

    static void savePos(Context c, int x, int y) { prefs(c).edit().putInt("posX", x).putInt("posY", y).apply(); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("minimap", Context.MODE_PRIVATE); }

    /** Resolve indices against the data, clamping anything out of range. */
    TrackData.Track trackOf(TrackData d) {
        if (d.maps.isEmpty()) return null;
        TrackData.MapInfo m = d.maps.get(Math.max(0, Math.min(map, d.maps.size() - 1)));
        return m.tracks.isEmpty() ? null : m.tracks.get(Math.max(0, Math.min(track, m.tracks.size() - 1)));
    }

    TrackData.Race raceOf(TrackData d) {
        TrackData.Track t = trackOf(d);
        if (t == null || t.races.isEmpty()) return null;
        return t.races.get(Math.max(0, Math.min(race, t.races.size() - 1)));
    }
}
