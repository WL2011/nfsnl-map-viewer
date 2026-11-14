package com.nfsnl.minimap;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Track lines and race spans from assets/maps.json (written by tools/export_minimap.py). */
final class TrackData {
    static final class Race {
        String id, label;
        boolean rev;
        float s0, s1, len;

        String title() { return label + (rev ? " 反向" : " 正向"); }
        String subtitle() { return String.format("%.2f km", len / 1000f); }
    }

    static final class Track {
        String name;
        float len, roadW, step;
        boolean loop;
        float[] x, z;             // lane-0 samples every `step` metres
        float minX, maxX, minZ, maxZ;
        final List<Race> races = new ArrayList<>();

        int n() { return x.length; }

        /** Point at lane-0 distance d (clamped). */
        void at(float d, float[] out) {
            float f = Math.max(0, Math.min(n() - 1, d / step));
            int i = Math.min((int) f, n() - 2);
            float t = f - i;
            out[0] = x[i] + (x[i + 1] - x[i]) * t;
            out[1] = z[i] + (z[i + 1] - z[i]) * t;
        }
    }

    static final class MapInfo {
        String key, place;
        int region;
        final List<Track> tracks = new ArrayList<>();

        String title() { return String.format("%02d · %s", region, place); }
    }

    final List<MapInfo> maps = new ArrayList<>();

    private static TrackData instance;

    static synchronized TrackData get(Context c) {
        if (instance == null) instance = load(c);
        return instance;
    }

    private static TrackData load(Context c) {
        try (InputStream in = c.getAssets().open("maps.json")) {
            return fromStream(in);
        } catch (java.io.IOException e) {
            throw new RuntimeException("maps.json", e);
        }
    }

    static TrackData fromStream(InputStream in) {
        TrackData d = new TrackData();
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
            JSONArray maps = new JSONObject(bo.toString("UTF-8")).getJSONArray("maps");
            for (int i = 0; i < maps.length(); i++) {
                JSONObject jm = maps.getJSONObject(i);
                MapInfo m = new MapInfo();
                m.key = jm.getString("key");
                m.place = jm.getString("place");
                m.region = jm.getInt("region");
                JSONArray tracks = jm.getJSONArray("tracks");
                for (int k = 0; k < tracks.length(); k++) {
                    JSONObject jt = tracks.getJSONObject(k);
                    Track t = new Track();
                    t.name = jt.getString("name");
                    t.len = (float) jt.getDouble("len");
                    t.roadW = (float) jt.getDouble("w");
                    t.step = (float) jt.getDouble("step");
                    t.loop = jt.getBoolean("loop");
                    JSONArray xz = jt.getJSONArray("xz");
                    int n = xz.length() / 2;
                    t.x = new float[n];
                    t.z = new float[n];
                    t.minX = t.minZ = Float.MAX_VALUE;
                    t.maxX = t.maxZ = -Float.MAX_VALUE;
                    for (int j = 0; j < n; j++) {
                        t.x[j] = xz.getInt(2 * j) / 10f;
                        t.z[j] = xz.getInt(2 * j + 1) / 10f;
                        t.minX = Math.min(t.minX, t.x[j]); t.maxX = Math.max(t.maxX, t.x[j]);
                        t.minZ = Math.min(t.minZ, t.z[j]); t.maxZ = Math.max(t.maxZ, t.z[j]);
                    }
                    JSONArray races = jt.getJSONArray("races");
                    for (int r = 0; r < races.length(); r++) {
                        JSONObject jr = races.getJSONObject(r);
                        Race race = new Race();
                        race.id = jr.getString("id");
                        race.label = jr.getString("label");
                        race.rev = jr.getBoolean("rev");
                        race.s0 = (float) jr.getDouble("s0");
                        race.s1 = (float) jr.getDouble("s1");
                        race.len = (float) jr.getDouble("len");
                        t.races.add(race);
                    }
                    m.tracks.add(t);
                }
                d.maps.add(m);
            }
        } catch (Exception e) {
            throw new RuntimeException("maps.json", e);
        }
        return d;
    }
}
