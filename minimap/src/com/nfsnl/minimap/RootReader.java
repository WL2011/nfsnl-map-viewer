package com.nfsnl.minimap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Runs as root in its own process (app_process with this APK on the classpath). Read-only: it only reads
 * /proc/<pid>/mem of the game.
 *
 * Camera: libapp.so + SLOT holds a (tagged) pointer to the race camera; its 4x4 row-major world matrix starts at
 * +0x30 (rows right, up, back, translation; the view axis is -row2).
 *
 * Cars: there is no short static path to the car objects, but the game keeps reusing the same car objects from
 * race to race. So the reader learns them: when a race loads (camera jumps) and the camera settles on the grid,
 * it scans memory for 4x4 matrices around the camera; once the race starts, the matrix that rides a steady
 * ~10 m in front of the camera is the player's car and the other moving one is the opponent (races are 1-on-1).
 * Learned addresses are kept per game process, so later races report the car as soon as they load.
 *
 * Output lines:
 *   P <ms> x y z fx fy fz        camera position and row2
 *   C <ms> x y z hx hz           player car position and heading (unit, xz)
 *   O <ms> x y z hx hz           opponent car
 *   R <trackId>                  selected event (from the menu; repeated every few seconds)
 *   S <status>                   status / learning events
 */
public final class RootReader {
    static final String PKG = "com.ea.game.nfs14_row";
    static final long SLOT = 0x378C158L;
    static final long MAT = 0x30;
    static final String STORE = "/data/local/tmp/nfsnl_minimap_cars.txt";
    /** Event menu data: the selected event's TrackId (e.g. region07_track01_race01_forward_day). Freed during the race. */
    static final long[][] TRACK_ID_PATHS = {{0x356FC60L, 0x190, 0xd8, 0xa0}, {0x3569178L, 0xf8, 0x180, 0x88, 0x180}};
    private static String lastTrackId = "";
    private static long lastTrackIdOut;

    private static PrintStream out;
    private static RandomAccessFile mem;
    private static int pid = -1;
    private static long base;
    private static final byte[] b = new byte[64];
    private static final ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);

    // learned cars: matrix start address, forward axis (row index) and sign
    private static long playerM, oppM;
    private static int playerAxis = 2, playerSign = 1, oppAxis = 2, oppSign = 1;

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), false);
        String last = "";
        float[] prevCam = null;
        boolean armed = false;
        long stillSince = 0;
        Learner learner = null;
        while (true) {
            String status = attach();
            float[] cam = null;
            long now = System.currentTimeMillis();
            if (status == null) {
                try {
                    cam = camera();
                    if (cam == null) status = "noptr";
                } catch (IOException e) {
                    status = "readerr";
                    detach();
                }
            }
            if (status == null) {
                String id = trackId();
                if (id != null && (!id.equals(lastTrackId) || now - lastTrackIdOut > 5000)) {
                    out.println("R " + id);
                    lastTrackId = id;
                    lastTrackIdOut = now;
                }
            }
            if (cam != null) {
                out.printf(Locale.US, "P %d %.3f %.3f %.3f %.4f %.4f %.4f%n", now, cam[0], cam[1], cam[2], cam[3], cam[4], cam[5]);
                // race load = camera jump; grid = camera settled afterwards
                if (prevCam != null) {
                    float step = (float) Math.hypot(cam[0] - prevCam[0], cam[2] - prevCam[2]);
                    if (step > 150) { armed = true; stillSince = 0; learner = null; }
                    else if (step < 0.05f) { if (stillSince == 0) stillSince = now; }
                    else stillSince = 0;
                }
                prevCam = cam;
                if (armed && stillSince != 0 && now - stillSince > 800) {
                    armed = false;
                    learner = new Learner(cam);
                    out.println("S learn scan " + learner.n);
                }
                if (learner != null && learner.step(cam, now)) learner = null;
                emitCar("C", playerM, playerAxis, playerSign, now, true);
                emitCar("O", oppM, oppAxis, oppSign, now, false);
            }
            if (status != null && !status.equals(last)) out.println("S " + status);
            last = status == null ? "" : status;
            out.flush();
            if (out.checkError()) return;      // the app went away
            Thread.sleep(status == null ? 50 : 1000);
        }
    }

    // ------------------------------------------------------------------ attach / camera
    private static String attach() {
        if (pid >= 0 && new File("/proc/" + pid).exists() && mem != null) return null;
        detach();
        pid = findPid();
        if (pid < 0) return "nogame";
        base = findBase(pid);
        if (base == 0) { pid = -1; return "nolib"; }
        try { mem = new RandomAccessFile("/proc/" + pid + "/mem", "r"); }
        catch (IOException e) { pid = -1; return "nomem"; }
        loadStore();
        return null;
    }

    private static void detach() {
        if (mem != null) { try { mem.close(); } catch (IOException ignored) {} }
        mem = null;
    }

    /** {x,y,z, row2 x,y,z} or null when there is no camera. */
    private static float[] camera() throws IOException {
        mem.seek(base + SLOT);
        mem.readFully(b, 0, 8);
        long p = bb.getLong(0) & 0x00ffffffffffffffL;
        if (p == 0) return null;
        float[] m = matrix(p + MAT);
        if (m == null) return null;
        return new float[]{m[12], m[13], m[14], m[8], m[9], m[10]};
    }

    /** The selected event's TrackId, or null while it is not readable (e.g. during a race). */
    private static String trackId() {
        for (long[] path : TRACK_ID_PATHS) {
            try {
                long p = base + path[0];
                for (int i = 1; i < path.length; i++) {
                    mem.seek(p);
                    mem.readFully(b, 0, 8);
                    long v = bb.getLong(0) & 0x00ffffffffffffffL;
                    if (v == 0) { p = 0; break; }
                    p = v + path[i];
                }
                if (p == 0) continue;
                mem.seek(p);
                mem.readFully(b, 0, 64);
                StringBuilder sb = new StringBuilder();
                for (byte c : b) {
                    if (c == 0) break;
                    if (c < 32 || c > 126) { sb.setLength(0); break; }
                    sb.append((char) c);
                }
                String s = sb.toString();
                if (s.matches("region\\d\\d_track\\d\\d_[A-Za-z0-9_]+")) return s;
            } catch (IOException ignored) {}
        }
        return null;
    }

    /** 16 floats of a valid rigid 4x4 (unit rows, w column 0,0,0,1), else null. */
    private static float[] matrix(long a) {
        try {
            mem.seek(a);
            mem.readFully(b, 0, 64);
        } catch (IOException e) {
            return null;
        }
        float[] m = new float[16];
        for (int i = 0; i < 16; i++) m[i] = bb.getFloat(i * 4);
        if (m[15] != 1f || m[3] != 0f || m[7] != 0f || m[11] != 0f) return null;
        for (int r = 0; r < 3; r++) {
            float n = m[r * 4] * m[r * 4] + m[r * 4 + 1] * m[r * 4 + 1] + m[r * 4 + 2] * m[r * 4 + 2];
            if (!(n > 0.95f && n < 1.05f)) return null;
        }
        if (!(Math.abs(m[12]) < 1e5f && Math.abs(m[13]) < 1e5f && Math.abs(m[14]) < 1e5f)) return null;
        return m;
    }

    private static boolean upright(float[] m) {
        if (m == null) return false;
        for (int r = 0; r < 3; r++) if (Math.abs(m[r * 4 + 1]) > 0.8f) return true;
        return false;
    }

    private static void emitCar(String tag, long a, int axis, int sign, long now, boolean player) {
        if (a == 0) return;
        float[] m = matrix(a);
        if (m == null) {
            if (player) playerM = 0; else oppM = 0;
            out.println("S lost " + tag);
            return;
        }
        float hx = m[axis * 4] * sign, hz = m[axis * 4 + 2] * sign, n = (float) Math.hypot(hx, hz);
        if (n > 1e-3f) { hx /= n; hz /= n; }
        out.printf(Locale.US, "%s %d %.3f %.3f %.3f %.4f %.4f%n", tag, now, m[12], m[13], m[14], hx, hz);
    }

    // ------------------------------------------------------------------ learning
    /** Matrices around the grid camera, then watched after the start to tell player and opponent apart. */
    private static final class Learner {
        final long[] addr;          // translation addresses (matrix + 48)
        final int n;
        final float[] x0, z0, X, Y, Z, fx, fz;      // start / current position, first moving position
        final double[] sAl, sAl2, sLa;
        final int[] cnt;
        float[] prevCam, gridCam;
        long movingMs, lastT;
        final long born;

        Learner(float[] cam) {
            born = System.currentTimeMillis();
            List<Long> cand = scan(cam[0], cam[1], cam[2]);
            n = cand.size();
            addr = new long[n];
            for (int i = 0; i < n; i++) addr[i] = cand.get(i) + 48;
            Arrays.sort(addr);
            x0 = new float[n]; z0 = new float[n]; X = new float[n]; Y = new float[n]; Z = new float[n];
            fx = new float[n]; fz = new float[n];
            Arrays.fill(fx, Float.NaN);
            sAl = new double[n]; sAl2 = new double[n]; sLa = new double[n]; cnt = new int[n];
            readXYZ(addr, x0, Y, z0);
            prevCam = cam;
            gridCam = cam;
        }

        /** true when finished (learned or gave up). */
        boolean step(float[] cam, long now) {
            if (now - born > 1800000) { out.println("S learn timeout"); return true; }
            float mv = (float) Math.hypot(cam[0] - prevCam[0], cam[2] - prevCam[2]);
            float dt = lastT == 0 ? 0.05f : Math.max(0.01f, (now - lastT) / 1000f);
            prevCam = cam; lastT = now;
            // wait for the race to run: the camera also shifts around before the start, so require it to have left
            // the grid area and to be moving at racing speed
            if (Math.hypot(cam[0] - gridCam[0], cam[2] - gridCam[2]) < 40 || mv / dt < 15 || mv > 100) return false;
            movingMs += (long) (dt * 1000);
            readXYZ(addr, X, Y, Z);
            float lx = -cam[3], lz = -cam[5], ln = (float) Math.hypot(lx, lz);
            if (ln < 0.2f) return false;
            lx /= ln; lz /= ln;
            for (int i = 0; i < n; i++) {
                float ex = X[i] - cam[0], ez = Z[i] - cam[2];
                if (!(Math.abs(ex) < 600 && Math.abs(ez) < 600)) continue;
                float al = ex * lx + ez * lz, la = ex * lz - ez * lx;
                sAl[i] += al; sAl2[i] += al * al; sLa[i] += la; cnt[i]++;
                if (Float.isNaN(fx[i]) && Math.hypot(X[i] - x0[i], Z[i] - z0[i]) > 2) { fx[i] = X[i]; fz[i] = Z[i]; }
            }
            if (movingMs < 1500) return false;
            decide();
            return true;
        }

        private void decide() {
            // player: rides at a steady 3..20 m in front of the camera
            int best = -1; double bestSd = 3;
            for (int i = 0; i < n; i++) {
                if (cnt[i] < 8 || Math.hypot(X[i] - x0[i], Z[i] - z0[i]) < 10) continue;
                double ma = sAl[i] / cnt[i], sd = Math.sqrt(Math.max(0, sAl2[i] / cnt[i] - ma * ma)), ml = sLa[i] / cnt[i];
                if (ma < 3 || ma > 20 || Math.abs(ml) > 3) continue;
                float[] m = matrix(addr[i] - 48);
                if (!upright(m)) continue;           // the chassis keeps one axis pointing up (wheels spin)
                if (sd < bestSd) { bestSd = sd; best = i; }
            }
            if (best < 0) { out.println("S learn noplayer"); return; }
            float px = X[best], pz = Z[best];
            // opponent: the other moving body (largest cluster of matrices moving together, away from the player)
            int opp = -1, oppMembers = 0;
            for (int i = 0; i < n; i++) {
                if (!movingBody(i) || Math.hypot(X[i] - px, Z[i] - pz) < 4) continue;
                int members = 0;
                for (int j = 0; j < n; j++) if (movingBody(j) && Math.hypot(X[j] - X[i], Z[j] - Z[i]) < 3) members++;
                float[] m = matrix(addr[i] - 48);
                if (!upright(m)) continue;
                if (members > oppMembers) { oppMembers = members; opp = i; }
            }
            int[] ps = axis(best);
            playerM = addr[best] - 48; playerAxis = ps[0]; playerSign = ps[1];
            if (opp >= 0) { int[] os = axis(opp); oppM = addr[opp] - 48; oppAxis = os[0]; oppSign = os[1]; }
            else oppM = 0;
            out.printf(Locale.US, "S learned player=%x axis=%d%+d opp=%x%n", playerM, playerAxis, playerSign, oppM);
            saveStore();
        }

        private boolean movingBody(int i) {
            return cnt[i] >= 8 && Math.hypot(X[i] - x0[i], Z[i] - z0[i]) > 10 && Math.hypot(X[i] - x0[i], Z[i] - z0[i]) < 1500;
        }

        /** Forward axis: the matrix row best aligned with this body's motion, and its sign. */
        private int[] axis(int i) {
            float[] m = matrix(addr[i] - 48);
            float dx = X[i] - (Float.isNaN(fx[i]) ? x0[i] : fx[i]), dz = Z[i] - (Float.isNaN(fz[i]) ? z0[i] : fz[i]);
            float dn = (float) Math.hypot(dx, dz);
            if (m == null || dn < 1) return new int[]{2, 1};
            dx /= dn; dz /= dn;
            int ax = 2; float bestDot = 0;
            for (int r = 0; r < 3; r++) {
                float d = m[r * 4] * dx + m[r * 4 + 2] * dz;
                if (Math.abs(d) > Math.abs(bestDot)) { bestDot = d; ax = r; }
            }
            return new int[]{ax, bestDot >= 0 ? 1 : -1};
        }
    }

    /** 16-byte aligned rigid 4x4 matrices whose translation is within 150 m of (cx, cz). */
    private static List<Long> scan(float cx, float cy, float cz) {
        List<Long> cand = new ArrayList<>();
        byte[] buf = new byte[1 << 20];
        ByteBuffer q = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (long[] r : regions()) {
            for (long a = r[0]; a < r[1]; ) {
                int len = (int) Math.min(buf.length, r[1] - a);
                try {
                    mem.seek(a);
                    mem.readFully(buf, 0, len);
                } catch (IOException e) {
                    a += 4096;
                    continue;
                }
                for (int o = 0; o + 64 <= len; o += 16) {
                    if (q.getInt(o + 60) != 0x3f800000 || q.getInt(o + 12) != 0 || q.getInt(o + 28) != 0 || q.getInt(o + 44) != 0) continue;
                    float x = q.getFloat(o + 48), y = q.getFloat(o + 52), z = q.getFloat(o + 56);
                    if (!(Math.abs(x - cx) < 150 && Math.abs(z - cz) < 150 && Math.abs(y - cy) < 60)) continue;
                    boolean ok = true;
                    for (int k = 0; k < 3 && ok; k++) {
                        float u = q.getFloat(o + k * 16), v = q.getFloat(o + k * 16 + 4), w = q.getFloat(o + k * 16 + 8);
                        float nn = u * u + v * v + w * w;
                        ok = nn > 0.97f && nn < 1.03f;
                    }
                    if (ok) cand.add(a + o);
                }
                a += len;
            }
        }
        return cand;
    }

    /** rw, resident, non-device regions of the game. */
    private static List<long[]> regions() {
        List<long[]> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/smaps"))) {
            long a = 0, e = 0; String perm = "", name = "";
            for (String l; (l = br.readLine()) != null; ) {
                char c = l.isEmpty() ? ' ' : l.charAt(0);
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                    String[] p = l.trim().split("\\s+", 6);
                    String[] ab = p[0].split("-");
                    a = Long.parseUnsignedLong(ab[0], 16); e = Long.parseUnsignedLong(ab[1], 16);
                    perm = p[1]; name = p.length > 5 ? p[5] : "";
                } else if (l.startsWith("Rss:")) {
                    long rss = Long.parseLong(l.replaceAll("[^0-9]", ""));
                    if (rss > 0 && perm.startsWith("rw") && !name.startsWith("/dev/") && !name.startsWith("[v")) out.add(new long[]{a, e});
                }
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static void readXYZ(long[] addr, float[] X, float[] Y, float[] Z) {
        byte[] page = new byte[4096];
        ByteBuffer pb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        long cur = -1; boolean ok = false;
        for (int i = 0; i < addr.length; i++) {
            long a = addr[i], pg = a & ~4095L;
            int off = (int) (a - pg);
            if (off > 4096 - 12) { X[i] = Y[i] = Z[i] = Float.NaN; continue; }
            if (pg != cur) { cur = pg; try { mem.seek(pg); mem.readFully(page); ok = true; } catch (IOException e) { ok = false; } }
            if (!ok) { X[i] = Y[i] = Z[i] = Float.NaN; continue; }
            X[i] = pb.getFloat(off); Y[i] = pb.getFloat(off + 4); Z[i] = pb.getFloat(off + 8);
        }
    }

    // ------------------------------------------------------------------ learned addresses survive app restarts
    private static void saveStore() {
        try (FileWriter w = new FileWriter(STORE)) {
            w.write(String.format(Locale.US, "%d %x %d %d %x %d %d%n", pid, playerM, playerAxis, playerSign, oppM, oppAxis, oppSign));
        } catch (IOException ignored) {}
    }

    private static void loadStore() {
        playerM = oppM = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(STORE))) {
            String[] p = br.readLine().trim().split(" ");
            if (Integer.parseInt(p[0]) != pid) return;
            playerM = Long.parseUnsignedLong(p[1], 16); playerAxis = Integer.parseInt(p[2]); playerSign = Integer.parseInt(p[3]);
            oppM = Long.parseUnsignedLong(p[4], 16); oppAxis = Integer.parseInt(p[5]); oppSign = Integer.parseInt(p[6]);
            out.printf(Locale.US, "S restored player=%x opp=%x%n", playerM, oppM);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ process lookup
    static int findPid() {
        File[] ps = new File("/proc").listFiles();
        if (ps == null) return -1;
        for (File f : ps) {
            String n = f.getName();
            if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
            try (FileReader r = new FileReader(new File(f, "cmdline"))) {
                char[] c = new char[64];
                int k = r.read(c);
                if (k > 0 && new String(c, 0, k).startsWith(PKG) && (k == PKG.length() || c[PKG.length()] == 0))
                    return Integer.parseInt(n);
            } catch (IOException | NumberFormatException ignored) {}
        }
        return -1;
    }

    /** libapp.so load base: an ELF mapping of the game's APK/lib that is large and executable. */
    static long findBase(int pid) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/maps"));
             RandomAccessFile m = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            byte[] h = new byte[4];
            for (String l; (l = br.readLine()) != null; ) {
                String[] p = l.trim().split("\\s+", 6);
                if (p.length < 6) continue;
                String path = p[5];
                if (!(path.contains(PKG) || path.endsWith("libapp.so"))) continue;
                if (!p[1].startsWith("r")) continue;
                String[] ab = p[0].split("-");
                long a = Long.parseUnsignedLong(ab[0], 16), e = Long.parseUnsignedLong(ab[1], 16);
                if (e - a < (16L << 20)) continue;
                try {
                    m.seek(a);
                    m.readFully(h);
                    if (h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') return a;
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return 0;
    }
}
