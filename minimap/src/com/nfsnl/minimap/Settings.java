package com.nfsnl.minimap;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved choices: minimap size / opacity / zoom / mirror / debug readout, overlay position. */
final class Settings {
    int sizeDp = 220, alphaPct = 85, radiusM = 350;
    boolean mirror, debug;
    int posX = -1, posY = -1;   // px from bottom-left; -1 = default

    static Settings load(Context c) {
        SharedPreferences p = prefs(c);
        Settings s = new Settings();
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
        prefs(c).edit().putInt("size", sizeDp).putInt("alpha", alphaPct).putInt("radius", radiusM)
                .putBoolean("mirror", mirror).putBoolean("debug", debug).apply();
    }

    static void savePos(Context c, int x, int y) { prefs(c).edit().putInt("posX", x).putInt("posY", y).apply(); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("minimap", Context.MODE_PRIVATE); }
}
