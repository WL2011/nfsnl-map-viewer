import java.io.*;
import java.nio.*;
import java.util.*;

/**
 * Root-side memory scanner for locating the player car position in NFS No Limits.
 * Run on the device as root:  CLASSPATH=/data/local/tmp/scan.dex app_process / Scan <cmd> ...
 *
 *   near <pid> <pts.bin> <radius> <out.txt>   float triples (x,y,z) whose (x,z) lies within radius of the track polyline
 *   read <pid> <cand.txt> [out.txt]           current (x,y,z) at each address listed (first column, hex)
 *   ptr  <pid> <targetHex> <maxOffHex> <out>  8-byte values pointing into [target-maxOff, target]
 *   maps <pid>                                readable regions considered
 */
public class Scan {
    static final class Region { long a, b; String perm, name; }

    public static void main(String[] args) throws Exception {
        String cmd = args[0];
        int pid = Integer.parseInt(args[1]);
        long t0 = System.currentTimeMillis();
        switch (cmd) {
            case "near": near(pid, args[2], Float.parseFloat(args[3]), args[4]); break;
            case "hunt": hunt(pid, args[2], Float.parseFloat(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]), args[6]); break;
            case "watch": watch(pid, args[2], Float.parseFloat(args[3]), Integer.parseInt(args[4]), args[5]); break;
            case "poll": poll(pid, args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]), args[5]); break;
            case "mat": mat(pid, args[2], args[3]); break;
            case "dump": dump(pid, Long.parseUnsignedLong(args[2], 16), Integer.parseInt(args[3]), args[4]); break;
            case "ptr2": ptr2(pid, args[2], Long.parseLong(args[3], 16), args[4]); break;
            case "carhunt": carhunt(pid, Integer.parseInt(args[2]), args[3]); break;
            case "gridhunt": gridhunt(pid, Integer.parseInt(args[2]), args[3]); break;
            case "str": str(pid, args[2], args[3]); break;
            case "chain": chain(pid, args[2], Long.parseLong(args[3], 16), Integer.parseInt(args[4]), args[5]); break;
            case "path": path(pid, Arrays.copyOfRange(args, 2, args.length)); break;
            case "pathwatch": pathwatch(pid, Integer.parseInt(args[2]), args[3]); break;
            case "read": read(pid, args[2], args.length > 3 ? args[3] : null); break;
            case "ptr": ptr(pid, Long.parseUnsignedLong(args[2], 16), Long.parseLong(args[3], 16), args[4]); break;
            case "maps": for (Region r : regions(pid, false)) System.out.printf("%x-%x %s %s%n", r.a, r.b, r.perm, r.name); break;
        }
        System.err.println("done in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** rw regions with resident pages, skipping device mappings. */
    static List<Region> regions(int pid, boolean includeRo) throws IOException {
        List<Region> out = new ArrayList<>();
        Region cur = null;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/smaps"))) {
            for (String l; (l = br.readLine()) != null; ) {
                char c = l.isEmpty() ? ' ' : l.charAt(0);
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                    String[] p = l.trim().split("\\s+", 6);
                    String[] ab = p[0].split("-");
                    cur = new Region();
                    cur.a = Long.parseUnsignedLong(ab[0], 16);
                    cur.b = Long.parseUnsignedLong(ab[1], 16);
                    cur.perm = p[1];
                    cur.name = p.length > 5 ? p[5] : "";
                } else if (l.startsWith("Rss:") && cur != null) {
                    long rss = Long.parseLong(l.replaceAll("[^0-9]", ""));
                    boolean ok = cur.perm.startsWith("r") && (includeRo || cur.perm.charAt(1) == 'w')
                            && rss > 0 && !cur.name.startsWith("/dev/") && !cur.name.equals("[vvar]") && !cur.name.equals("[vectors]");
                    if (ok) out.add(cur);
                    cur = null;
                }
            }
        }
        return out;
    }

    interface Chunk { void on(long base, ByteBuffer bb, int len); }

    static void walk(int pid, List<Region> rs, Chunk cb) throws IOException {
        byte[] buf = new byte[1 << 20];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            for (Region r : rs) {
                for (long a = r.a; a < r.b; ) {
                    int n = (int) Math.min(buf.length, r.b - a);
                    try {
                        mem.seek(a);
                        mem.readFully(buf, 0, n);
                        cb.on(a, bb, n);
                        a += n;
                    } catch (IOException e) {
                        // unreadable page somewhere in the chunk: retry page by page
                        long end = a + n;
                        for (; a < end; a += 4096) {
                            try { mem.seek(a); mem.readFully(buf, 0, 4096); cb.on(a, bb, 4096); } catch (IOException ignored) {}
                        }
                    }
                }
            }
        }
    }

    static void near(int pid, String ptsFile, float R, String outFile) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(ptsFile)));
        int n = Integer.reverseBytes(in.readInt());
        float[] px = new float[n], pz = new float[n];
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            px[i] = Float.intBitsToFloat(Integer.reverseBytes(in.readInt()));
            pz[i] = Float.intBitsToFloat(Integer.reverseBytes(in.readInt()));
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]); minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
        }
        final float yMin = Float.intBitsToFloat(Integer.reverseBytes(in.readInt()));
        final float yMax = Float.intBitsToFloat(Integer.reverseBytes(in.readInt()));
        in.close();
        final float cell = Math.max(R, 8f), x0 = minX - R, z0 = minZ - R, x1 = maxX + R, z1 = maxZ + R;
        final int gw = (int) ((x1 - x0) / cell) + 1, gh = (int) ((z1 - z0) / cell) + 1;
        int[] cnt = new int[gw * gh + 1];
        for (int i = 0; i < n; i++) cnt[(int) ((px[i] - x0) / cell) + (int) ((pz[i] - z0) / cell) * gw + 1]++;
        for (int i = 1; i < cnt.length; i++) cnt[i] += cnt[i - 1];
        int[] idx = new int[n], fill = cnt.clone();
        for (int i = 0; i < n; i++) idx[fill[(int) ((px[i] - x0) / cell) + (int) ((pz[i] - z0) / cell) * gw]++] = i;
        final float R2 = R * R;
        final PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        final long[] hits = {0};
        List<Region> rs = regions(pid, false);
        long total = 0; for (Region r : rs) total += r.b - r.a;
        System.err.println("regions " + rs.size() + " bytes " + (total >> 20) + " MB");
        walk(pid, rs, (base, bb, len) -> {
            for (int o = 0; o + 12 <= len; o += 4) {
                float x = bb.getFloat(o);
                if (!(x > x0 && x < x1)) continue;
                float z = bb.getFloat(o + 8);
                if (!(z > z0 && z < z1)) continue;
                float y = bb.getFloat(o + 4);
                if (!(y >= yMin && y <= yMax)) continue;
                if (x == (int) x && z == (int) z) continue;   // skip round numbers (constants, not positions)
                if (x * x + z * z < 400) continue;            // near the origin: mostly unrelated small floats
                int cx = (int) ((x - x0) / cell), cz = (int) ((z - z0) / cell);
                boolean ok = false;
                for (int dz = -1; dz <= 1 && !ok; dz++)
                    for (int dx = -1; dx <= 1 && !ok; dx++) {
                        int gx = cx + dx, gz = cz + dz;
                        if (gx < 0 || gz < 0 || gx >= gw || gz >= gh) continue;
                        int c = gx + gz * gw;
                        for (int k = cnt[c]; k < cnt[c + 1]; k++) {
                            int i = idx[k];
                            float ex = px[i] - x, ez = pz[i] - z;
                            if (ex * ex + ez * ez < R2) { ok = true; break; }
                        }
                    }
                if (ok) { out.printf(Locale.US, "%x %.3f %.3f %.3f%n", base + o, x, y, z); hits[0]++; }
            }
        });
        out.close();
        System.err.println("hits " + hits[0]);
    }

    /** near-scan into memory, then sample every candidate `passes` times; keep the ones that travel along the track. */
    static void hunt(int pid, String ptsFile, float R, int passes, int intervalMs, String outFile) throws Exception {
        String tmp = outFile + ".near";
        near(pid, ptsFile, R, tmp);
        List<Long> al = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(tmp))) {
            for (String l; (l = br.readLine()) != null; ) al.add(Long.parseUnsignedLong(l.split(" ")[0], 16));
        }
        long[] all = new long[al.size()];
        for (int i = 0; i < all.length; i++) all[i] = al.get(i);
        al = null;
        Arrays.sort(all);
        // prefilter: two reads 1.5 s apart, keep what moved 5..600 m in the ground plane
        float[] ax = new float[all.length], az = new float[all.length];
        readXZ(pid, all, ax, az);
        Thread.sleep(1500);
        float[] bx = new float[all.length], bz = new float[all.length];
        readXZ(pid, all, bx, bz);
        int n = 0;
        for (int i = 0; i < all.length; i++) {
            float dx = bx[i] - ax[i], dz = bz[i] - az[i], d2 = dx * dx + dz * dz;
            if (d2 > 25 && d2 < 360000) all[n++] = all[i];
        }
        long[] addr = Arrays.copyOf(all, n);
        ax = az = bx = bz = null; all = null;
        System.err.println("prefilter kept " + n);
        float[][] X = new float[passes][n], Z = new float[passes][n], Y = new float[passes][n];
        long[] T = new long[passes];
        byte[] page = new byte[4096];
        ByteBuffer pb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            for (int p = 0; p < passes; p++) {
                long t = System.currentTimeMillis();
                T[p] = t;
                long cur = -1; boolean ok = false;
                for (int i = 0; i < n; i++) {
                    long a = addr[i], pg = a & ~4095L;
                    int off = (int) (a - pg);
                    if (off > 4096 - 12) {   // straddles a page: read directly
                        byte[] b = new byte[12]; ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                        try { mem.seek(a); mem.readFully(b); X[p][i] = bb.getFloat(0); Y[p][i] = bb.getFloat(4); Z[p][i] = bb.getFloat(8); }
                        catch (IOException e) { X[p][i] = Float.NaN; }
                        continue;
                    }
                    if (pg != cur) { cur = pg; try { mem.seek(pg); mem.readFully(page); ok = true; } catch (IOException e) { ok = false; } }
                    if (!ok) { X[p][i] = Float.NaN; continue; }
                    X[p][i] = pb.getFloat(off); Y[p][i] = pb.getFloat(off + 4); Z[p][i] = pb.getFloat(off + 8);
                }
                System.err.println("pass " + p + " " + (System.currentTimeMillis() - t) + " ms");
                long wait = intervalMs - (System.currentTimeMillis() - t);
                if (wait > 0) Thread.sleep(wait);
            }
        }
        PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        out.print("# t");
        for (long t : T) out.print(" " + t);
        out.println();
        int kept = 0;
        for (int i = 0; i < n; i++) {
            double path = 0; boolean bad = false;
            for (int p = 1; p < passes; p++) {
                float dx = X[p][i] - X[p - 1][i], dz = Z[p][i] - Z[p - 1][i];
                if (Float.isNaN(dx) || Float.isNaN(dz)) { bad = true; break; }
                path += Math.sqrt(dx * dx + dz * dz);
            }
            if (bad || path < 30) continue;
            kept++;
            out.printf("%x", addr[i]);
            for (int p = 0; p < passes; p++) out.printf(Locale.US, " %.2f,%.2f,%.2f", X[p][i], Y[p][i], Z[p][i]);
            out.println();
        }
        out.close();
        System.err.println("moving candidates " + kept + " of " + n);
    }

    /**
     * near-scan, then read every candidate about once a second for `seconds`. Each candidate keeps running stats; any
     * candidate that has made at least 4 plausible moves (2..330 m/s) gets its whole trace (t,x,y,z per pass) written out.
     */
    static void watch(int pid, String ptsFile, float R, int seconds, String outFile) throws Exception {
        String tmp = outFile + ".near";
        near(pid, ptsFile, R, tmp);
        int cnt = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(tmp))) { while (br.readLine() != null) cnt++; }
        long[] addr = new long[cnt];
        try (BufferedReader br = new BufferedReader(new FileReader(tmp))) {
            int i = 0; for (String l; (l = br.readLine()) != null; ) addr[i++] = Long.parseUnsignedLong(l.substring(0, l.indexOf(' ')), 16);
        }
        Arrays.sort(addr);
        int n = cnt;
        float[] px = new float[n], pz = new float[n], X = new float[n], Z = new float[n];
        short[] moves = new short[n];
        readXZ(pid, addr, px, pz);
        long tPrev = System.currentTimeMillis(), tEnd = tPrev + seconds * 1000L;
        // traces: up to 120 passes, stored only for candidates that start moving
        final int MAXP = 120;
        Map<Integer, float[]> trace = new HashMap<>();
        long[] T = new long[MAXP];
        int pass = 0;
        T[pass++] = tPrev;
        float[] Y = new float[n];
        while (System.currentTimeMillis() < tEnd && pass < MAXP) {
            Thread.sleep(Math.max(0, 1000 - (System.currentTimeMillis() - tPrev)));
            long t = System.currentTimeMillis();
            readXZY(pid, addr, X, Y, Z);
            float dt = (t - tPrev) / 1000f;
            for (int i = 0; i < n; i++) {
                float dx = X[i] - px[i], dz = Z[i] - pz[i];
                float v = (float) Math.sqrt(dx * dx + dz * dz) / dt;
                if (v > 2 && v < 330 && moves[i] < 30000) moves[i]++;
                if (moves[i] >= 1) {
                    float[] tr = trace.get(i);
                    if (tr == null && trace.size() < 200000) { tr = new float[MAXP * 3]; Arrays.fill(tr, Float.NaN); tr[0] = px[i]; tr[2] = pz[i]; trace.put(i, tr); }
                    if (tr != null) { tr[pass * 3] = X[i]; tr[pass * 3 + 1] = Y[i]; tr[pass * 3 + 2] = Z[i]; }
                }
                px[i] = X[i]; pz[i] = Z[i];
            }
            T[pass++] = t;
            tPrev = t;
            if (pass % 10 == 0) System.err.println("pass " + pass + " tracing " + trace.size() + " read " + (System.currentTimeMillis() - t) + " ms");
        }
        PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        out.print("# t");
        for (int p = 0; p < pass; p++) out.print(" " + T[p]);
        out.println();
        int kept = 0;
        for (Map.Entry<Integer, float[]> e : trace.entrySet()) {
            int i = e.getKey();
            if (moves[i] < 4) continue;
            kept++;
            float[] tr = e.getValue();
            out.printf("%x", addr[i]);
            for (int p = 0; p < pass; p++) out.printf(Locale.US, " %.2f,%.2f,%.2f", tr[p * 3], tr[p * 3 + 1], tr[p * 3 + 2]);
            out.println();
        }
        out.close();
        System.err.println("watch kept " + kept + " of " + n + " passes " + pass);
    }

    /** read the listed addresses every intervalMs for `seconds`; one line per pass: t then x,y,z per address. */
    static void poll(int pid, String candFile, int seconds, int intervalMs, String outFile) throws Exception {
        List<Long> al = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(candFile))) {
            for (String l; (l = br.readLine()) != null; ) if (!l.trim().isEmpty()) al.add(Long.parseUnsignedLong(l.trim().split("\\s+")[0], 16));
        }
        long[] addr = new long[al.size()];
        for (int i = 0; i < addr.length; i++) addr[i] = al.get(i);
        float[] X = new float[addr.length], Y = new float[addr.length], Z = new float[addr.length];
        PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        out.print("# t");
        for (long a : addr) out.printf(" %x", a);
        out.println();
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            long t = System.currentTimeMillis();
            readXZY(pid, addr, X, Y, Z);
            out.print(t);
            for (int i = 0; i < addr.length; i++) out.printf(Locale.US, " %.2f,%.2f,%.2f", X[i], Y[i], Z[i]);
            out.println();
            Thread.sleep(Math.max(0, intervalMs - (System.currentTimeMillis() - t)));
        }
        out.close();
    }

    /** For each listed address A (translation x,y,z), test whether A-48..A-4 holds an orthonormal 3x3 (row-major 4x4). */
    static void mat(int pid, String candFile, String outFile) throws IOException {
        PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        byte[] b = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r");
             BufferedReader br = new BufferedReader(new FileReader(candFile))) {
            for (String l; (l = br.readLine()) != null; ) {
                if (l.trim().isEmpty()) continue;
                long a = Long.parseUnsignedLong(l.trim().split("\\s+")[0], 16);
                for (int layout = 0; layout < 2; layout++) {
                    // layout 0: rows at a-48,a-32,a-16 (4x4 row-major, translation last row); layout 1: 3x4 with rows of 4 where translation is column 3
                    try {
                        mem.seek(a - 48); mem.readFully(b);
                        float[][] r = new float[3][3];
                        for (int i = 0; i < 3; i++) for (int k = 0; k < 3; k++) r[i][k] = bb.getFloat(i * 16 + k * 4);
                        double e = 0;
                        for (int i = 0; i < 3; i++) for (int k = 0; k < 3; k++) {
                            double d = r[i][0] * r[k][0] + r[i][1] * r[k][1] + r[i][2] * r[k][2];
                            e += Math.abs(d - (i == k ? 1 : 0));
                        }
                        if (e < 0.05) out.printf(Locale.US, "%x orth %.4f  fwd(row2) %.3f %.3f %.3f  pos %.2f %.2f %.2f%n", a, e, r[2][0], r[2][1], r[2][2],
                                bb.getFloat(48), bb.getFloat(52), bb.getFloat(56));
                    } catch (IOException ignored) {}
                    break;
                }
            }
        }
        out.close();
    }

    static void dump(int pid, long a, int len, String outFile) throws IOException {
        byte[] b = new byte[len];
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) { mem.seek(a); mem.readFully(b); }
        try (FileOutputStream fo = new FileOutputStream(outFile)) { fo.write(b); }
    }

    /** Pointer scan with tag masking: for each target (hex per line), 8-byte values v with (v & 0x00ffffffffffffff) in [target-maxOff, target]. Includes module data. */
    static void ptr2(int pid, String targetsFile, long maxOff, String outFile) throws IOException {
        List<Long> tl = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(targetsFile))) {
            for (String l; (l = br.readLine()) != null; ) if (!l.trim().isEmpty()) tl.add(Long.parseUnsignedLong(l.trim().split("\\s+")[0], 16));
        }
        final long[] tg = new long[tl.size()];
        for (int i = 0; i < tg.length; i++) tg[i] = tl.get(i);
        Arrays.sort(tg);
        final PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        List<Region> rs = regions(pid, false);
        final Map<Long, String> names = new TreeMap<>();
        for (Region r : rs) names.put(r.a, r.name);
        final long lo = tg[0] - maxOff, hi = tg[tg.length - 1];
        walk(pid, rs, (base, bb, len) -> {
            for (int o = 0; o + 8 <= len; o += 8) {
                long v = bb.getLong(o) & 0x00ffffffffffffffL;
                if (v < lo || v > hi) continue;
                int k = Arrays.binarySearch(tg, v);
                if (k < 0) k = -k - 1;           // first target >= v
                if (k < tg.length && tg[k] - v <= maxOff) {
                    long at = base + o;
                    Map.Entry<Long, String> e = ((TreeMap<Long, String>) names).floorEntry(at);
                    out.printf("%x -> %x +%x  %s%n", at, v, tg[k] - v, e == null ? "" : e.getValue());
                }
            }
        });
        out.close();
    }

    // ---- player car finder: matrices that ride a fixed distance in front of the race camera
    static final long SLOT = 0x378C158L;

    static long libBase(int pid) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/maps"));
             RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            byte[] h = new byte[4];
            for (String l; (l = br.readLine()) != null; ) {
                String[] p = l.trim().split("\\s+", 6);
                if (p.length < 6 || !p[1].startsWith("r") || !(p[5].contains("nfs14_row") || p[5].endsWith("libapp.so"))) continue;
                String[] ab = p[0].split("-");
                long a = Long.parseUnsignedLong(ab[0], 16), e = Long.parseUnsignedLong(ab[1], 16);
                if (e - a < (16L << 20)) continue;
                try { mem.seek(a); mem.readFully(h); if (h[0] == 0x7f && h[1] == 'E') return a; } catch (IOException ignored) {}
            }
        }
        return 0;
    }

    /** camera {x,y,z, lookX, lookY, lookZ} (look = -row2) */
    static float[] camera(RandomAccessFile mem, long base) throws IOException {
        byte[] b = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        mem.seek(base + SLOT); mem.readFully(b, 0, 8);
        long p = bb.getLong(0) & 0x00ffffffffffffffL;
        mem.seek(p + 0x30); mem.readFully(b);
        return new float[]{bb.getFloat(48), bb.getFloat(52), bb.getFloat(56), -bb.getFloat(32), -bb.getFloat(36), -bb.getFloat(40)};
    }

    static void carhunt(int pid, int waitSec, String outFile) throws Exception {
        long base = libBase(pid);
        System.err.println("base " + Long.toHexString(base));
        RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r");
        // 1. wait until the camera is racing (> 20 m/s)
        long until = System.currentTimeMillis() + waitSec * 1000L;
        float[] c0 = camera(mem, base);
        int fast = 0; float run = 0;
        while (true) {
            Thread.sleep(250);
            float[] c = camera(mem, base);
            float step = (float) Math.hypot(c[0] - c0[0], c[2] - c0[2]), v = step / 0.25f;
            c0 = c;
            if (v > 20 && v < 400) { fast++; run += step; } else { fast = 0; run = 0; }
            if (fast >= 8 && run > 60) break;
            if (System.currentTimeMillis() > until) { System.err.println("timeout waiting for a race"); return; }
        }
        long ts = System.currentTimeMillis();
        System.err.println("racing at " + Arrays.toString(c0));
        // 2. scan for 4x4 row-major matrices (w column 0,0,0,1; unit rows) within 300 m of the camera
        final float cx = c0[0], cz = c0[2];
        final List<Long> cand = new ArrayList<>();
        List<Region> rs = regions(pid, false);
        walk(pid, rs, (b0, bb, len) -> {
            for (int o = 0; o + 64 <= len; o += 16) {
                if (bb.getInt(o + 60) != 0x3f800000) continue;
                if (bb.getInt(o + 12) != 0 || bb.getInt(o + 28) != 0 || bb.getInt(o + 44) != 0) continue;
                float x = bb.getFloat(o + 48), z = bb.getFloat(o + 56);
                if (!(Math.abs(x - cx) < 2500 && Math.abs(z - cz) < 2500)) continue;
                boolean ok = true;
                for (int r = 0; r < 3 && ok; r++) {
                    float a = bb.getFloat(o + r * 16), b = bb.getFloat(o + r * 16 + 4), c = bb.getFloat(o + r * 16 + 8);
                    float n = a * a + b * b + c * c;
                    ok = n > 0.97f && n < 1.03f;
                }
                if (ok) cand.add(b0 + o);
            }
        });
        System.err.println("matrices near camera " + cand.size() + " scan " + (System.currentTimeMillis() - ts) + " ms");
        // 3. track candidates against the camera for ~15 s
        int n = cand.size(), passes = 0;
        long[] addr = new long[n];
        for (int i = 0; i < n; i++) addr[i] = cand.get(i) + 48;
        Arrays.sort(addr);
        float[] X = new float[n], Y = new float[n], Z = new float[n];
        double[] sAlong = new double[n], sAlong2 = new double[n], sLat = new double[n], sLat2 = new double[n], sUp = new double[n];
        int[] cnt = new int[n];
        long end = System.currentTimeMillis() + 15000;
        float[] prev = camera(mem, base);
        while (System.currentTimeMillis() < end) {
            long t = System.currentTimeMillis();
            float[] c = camera(mem, base);
            readXZY(pid, addr, X, Y, Z);
            float mv = (float) Math.hypot(c[0] - prev[0], c[2] - prev[2]);
            prev = c;
            float lx = c[3], lz = c[5], ln = (float) Math.hypot(lx, lz);
            if (mv > 1.5f && ln > 0.3f) {      // moving: use this pass
                lx /= ln; lz /= ln;
                for (int i = 0; i < n; i++) {
                    float ex = X[i] - c[0], ez = Z[i] - c[2];
                    if (!(Math.abs(ex) < 400 && Math.abs(ez) < 400)) continue;
                    float al = ex * lx + ez * lz, la = ex * lz - ez * lx;
                    sAlong[i] += al; sAlong2[i] += al * al; sLat[i] += la; sLat2[i] += la * la; sUp[i] += Y[i] - c[1]; cnt[i]++;
                }
                passes++;
            }
            Thread.sleep(Math.max(0, 100 - (System.currentTimeMillis() - t)));
        }
        PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        out.println("# passes " + passes + " candidates " + n);
        for (int i = 0; i < n; i++) {
            if (cnt[i] < passes * 0.8 || cnt[i] < 10) continue;
            double ma = sAlong[i] / cnt[i], sa = Math.sqrt(Math.max(0, sAlong2[i] / cnt[i] - ma * ma));
            double ml = sLat[i] / cnt[i], sl = Math.sqrt(Math.max(0, sLat2[i] / cnt[i] - ml * ml));
            if (ma < 1 || ma > 30 || sa > 6 || sl > 4) continue;
            out.printf(Locale.US, "%x along %.2f sd %.2f lat %.2f sd %.2f up %.2f%n", addr[i] - 48, ma, sa, ml, sl, sUp[i] / cnt[i]);
        }
        out.close();
        System.err.println("passes " + passes);
    }

    /**
     * Wait for a race load (camera jump), scan matrices around the camera once it settles on the grid, then after
     * the start record every matrix that moves, at 10 Hz, together with the camera. Output: one line per pass.
     */
    static void gridhunt(int pid, int waitSec, String outFile) throws Exception {
        long base = libBase(pid);
        RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r");
        long until = System.currentTimeMillis() + waitSec * 1000L;
        float[] c0 = camera(mem, base);
        boolean armed = false; long still = 0;
        while (true) {
            Thread.sleep(100);
            if (System.currentTimeMillis() > until) { System.err.println("timeout"); return; }
            float[] c;
            try { c = camera(mem, base); } catch (IOException e) { continue; }
            float step = (float) Math.hypot(c[0] - c0[0], c[2] - c0[2]);
            c0 = c;
            if (step > 150) { armed = true; still = 0; System.err.println("camera jump -> armed " + Arrays.toString(c)); continue; }
            if (step < 0.05f) { if (still == 0) still = System.currentTimeMillis(); } else still = 0;
            if (armed && still != 0 && System.currentTimeMillis() - still > 1000) break;
        }
        float[] g = c0;
        System.err.println("grid camera " + Arrays.toString(g));
        long ts = System.currentTimeMillis();
        final float cx = g[0], cy = g[1], cz = g[2];
        final List<Long> cand = new ArrayList<>();
        walk(pid, regions(pid, false), (b0, bb, len) -> {
            for (int o = 0; o + 64 <= len; o += 16) {
                if (bb.getInt(o + 60) != 0x3f800000) continue;
                if (bb.getInt(o + 12) != 0 || bb.getInt(o + 28) != 0 || bb.getInt(o + 44) != 0) continue;
                float x = bb.getFloat(o + 48), y = bb.getFloat(o + 52), z = bb.getFloat(o + 56);
                if (!(Math.abs(x - cx) < 150 && Math.abs(z - cz) < 150 && Math.abs(y - cy) < 60)) continue;
                boolean ok = true;
                for (int r = 0; r < 3 && ok; r++) {
                    float a = bb.getFloat(o + r * 16), b = bb.getFloat(o + r * 16 + 4), c = bb.getFloat(o + r * 16 + 8);
                    float n = a * a + b * b + c * c;
                    ok = n > 0.97f && n < 1.03f;
                }
                if (ok) cand.add(b0 + o);
            }
        });
        int n = cand.size();
        long[] addr = new long[n];
        for (int i = 0; i < n; i++) addr[i] = cand.get(i) + 48;
        Arrays.sort(addr);
        System.err.println("grid matrices " + n + " scan " + (System.currentTimeMillis() - ts) + " ms");
        float[] X0 = new float[n], Y0 = new float[n], Z0 = new float[n];
        readXZY(pid, addr, X0, Y0, Z0);
        // wait for the start
        float[] p = camera(mem, base);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 90000) {
            Thread.sleep(100);
            float[] c = camera(mem, base);
            float step = (float) Math.hypot(c[0] - p[0], c[2] - p[2]);
            p = c;
            if (step > 1.0f && step < 150) break;
        }
        System.err.println("start");
        // record 12 s at 10 Hz
        int P = 120;
        float[][] X = new float[P][], Z = new float[P][], Y = new float[P][], C = new float[P][];
        long[] T = new long[P];
        int k = 0;
        for (; k < P; k++) {
            long t = System.currentTimeMillis();
            T[k] = t;
            C[k] = camera(mem, base);
            X[k] = new float[n]; Y[k] = new float[n]; Z[k] = new float[n];
            readXZY(pid, addr, X[k], Y[k], Z[k]);
            Thread.sleep(Math.max(0, 100 - (System.currentTimeMillis() - t)));
        }
        PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        out.printf(Locale.US, "# grid camera %.2f %.2f %.2f look %.3f %.3f %.3f%n", g[0], g[1], g[2], g[3], g[4], g[5]);
        out.print("T");
        for (int i = 0; i < k; i++) out.print(" " + T[i]);
        out.println();
        out.print("CAM");
        for (int i = 0; i < k; i++) out.printf(Locale.US, " %.2f,%.2f,%.2f,%.3f,%.3f", C[i][0], C[i][1], C[i][2], C[i][3], C[i][5]);
        out.println();
        int kept = 0;
        for (int j = 0; j < n; j++) {
            float mx = 0;
            for (int i = 0; i < k; i++) mx = Math.max(mx, (float) Math.hypot(X[i][j] - X0[j], Z[i][j] - Z0[j]));
            if (!(mx > 30 && mx < 3000)) continue;
            kept++;
            out.printf(Locale.US, "%x %.2f,%.2f,%.2f", addr[j] - 48, X0[j], Y0[j], Z0[j]);
            for (int i = 0; i < k; i++) out.printf(Locale.US, " %.2f,%.2f,%.2f", X[i][j], Y[i][j], Z[i][j]);
            out.println();
        }
        out.close();
        System.err.println("moving matrices " + kept);
    }

    /** every occurrence of an ASCII string (all readable regions, including module data) */
    static void str(int pid, String needle, String outFile) throws IOException {
        final byte[] nd = needle.getBytes("US-ASCII");
        final PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        List<Region> rs = regions(pid, true);
        final Map<Long, String> names = new TreeMap<>();
        for (Region r : rs) names.put(r.a, r.name);
        walk(pid, rs, (b0, bb, len) -> {
            byte[] a = bb.array();
            outer:
            for (int o = 0; o + nd.length <= len; o++) {
                if (a[o] != nd[0]) continue;
                for (int k = 1; k < nd.length; k++) if (a[o + k] != nd[k]) continue outer;
                long at = b0 + o;
                StringBuilder ctx = new StringBuilder();
                for (int k = Math.max(0, o - 24); k < Math.min(len, o + nd.length + 24); k++) { int c = a[k] & 255; ctx.append(c >= 32 && c < 127 ? (char) c : '.'); }
                out.printf("%x %s | %s%n", at, ((TreeMap<Long, String>) names).floorEntry(at).getValue(), ctx);
            }
        });
        out.close();
    }

    /**
     * Backward pointer BFS: level 0 = targets; each level finds 8-byte (tag-masked) values pointing into
     * [t - maxOff, t] for any current target. Stops a branch when the holder lies in a module image/.bss.
     * Output: one line per link "level holder -> target +off region".
     */
    static void chain(int pid, String targetsFile, long maxOff, int depth, String outFile) throws IOException {
        List<Long> tl = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(targetsFile))) {
            for (String l; (l = br.readLine()) != null; ) if (!l.trim().isEmpty()) tl.add(Long.parseUnsignedLong(l.trim().split("\\s+")[0], 16));
        }
        final PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        List<Region> rs = regions(pid, false);
        final TreeMap<Long, String> names = new TreeMap<>();
        for (Region r : rs) names.put(r.a, r.name);
        Set<Long> seen = new HashSet<>(tl);
        long[] cur = new long[tl.size()];
        for (int i = 0; i < cur.length; i++) cur[i] = tl.get(i);
        for (int lv = 1; lv <= depth && cur.length > 0; lv++) {
            Arrays.sort(cur);
            final long[] tg = cur;
            final long lo = tg[0] - maxOff, hi = tg[tg.length - 1];
            final List<long[]> found = new ArrayList<>();
            walk(pid, rs, (base, bb, len) -> {
                for (int o = 0; o + 8 <= len; o += 8) {
                    long v = bb.getLong(o) & 0x00ffffffffffffffL;
                    if (v < lo || v > hi) continue;
                    int k = Arrays.binarySearch(tg, v);
                    if (k < 0) k = -k - 1;
                    if (k < tg.length && tg[k] - v <= maxOff) found.add(new long[]{base + o, tg[k], tg[k] - v});
                }
            });
            List<Long> next = new ArrayList<>();
            int stat = 0;
            for (long[] f : found) {
                String nm = names.floorEntry(f[0]).getValue();
                boolean isStatic = nm.contains(".bss") || nm.contains(".so") || nm.contains(".apk") || nm.contains("libapp");
                out.printf("%d %x -> %x +%x %s%s%n", lv, f[0], f[1], f[2], nm, isStatic ? "  STATIC" : "");
                if (isStatic) { stat++; continue; }
                if (nm.contains("stack")) continue;
                if (seen.add(f[0])) next.add(f[0]);
            }
            out.flush();
            System.err.println("level " + lv + ": " + found.size() + " holders, " + stat + " static, next " + next.size());
            if (next.size() > 30000) { System.err.println("too many, stop"); break; }
            cur = new long[next.size()];
            for (int i = 0; i < cur.length; i++) cur[i] = next.get(i);
        }
        out.close();
    }

    /** libapp base + off0 -> [+off1] -> ... -> string at +last. Offsets hex. */
    static void path(int pid, String[] offs) throws IOException {
        long base = libBase(pid);
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            byte[] b = new byte[64];
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            long p = base + Long.parseLong(offs[0], 16);
            System.out.printf("base %x slot %x%n", base, p);
            for (int i = 1; i < offs.length; i++) {
                mem.seek(p); mem.readFully(b, 0, 8);
                long v = bb.getLong(0) & 0x00ffffffffffffffL;
                if (v == 0) { System.out.println("null at step " + i); return; }
                p = v + Long.parseLong(offs[i], 16);
                System.out.printf("step %d -> %x%n", i, p);
            }
            mem.seek(p); mem.readFully(b);
            StringBuilder sb = new StringBuilder();
            for (byte c : b) { if (c == 0) break; sb.append((char) c); }
            System.out.println("string: " + sb);
        }
    }

    static String follow(RandomAccessFile mem, long base, long[] offs) {
        try {
            byte[] b = new byte[64];
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            long p = base + offs[0];
            for (int i = 1; i < offs.length; i++) {
                mem.seek(p); mem.readFully(b, 0, 8);
                long v = bb.getLong(0) & 0x00ffffffffffffffL;
                if (v == 0) return "null@" + i;
                p = v + offs[i];
            }
            mem.seek(p); mem.readFully(b);
            StringBuilder sb = new StringBuilder();
            for (byte c : b) { if (c == 0) break; if (c < 32 || c > 126) return "bin"; sb.append((char) c); }
            return sb.toString();
        } catch (IOException e) { return "err"; }
    }

    /** log changes of the two TrackId paths and camera jumps for `sec` seconds */
    static void pathwatch(int pid, int sec, String outFile) throws Exception {
        long base = libBase(pid);
        long[] a = {0x356FC60L, 0x190, 0xd8, 0xa0}, b2 = {0x3569178L, 0xf8, 0x180, 0x88, 0x180};
        PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        String la = "", lb = ""; float[] lc = null;
        long end = System.currentTimeMillis() + sec * 1000L;
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            while (System.currentTimeMillis() < end) {
                long t = System.currentTimeMillis();
                String sa = follow(mem, base, a), sb = follow(mem, base, b2);
                float[] c = null;
                try { c = camera(mem, base); } catch (IOException ignored) {}
                boolean jump = c != null && lc != null && Math.hypot(c[0] - lc[0], c[2] - lc[2]) > 150;
                if (!sa.equals(la) || !sb.equals(lb) || jump) {
                    out.printf(Locale.US, "%d A=%s B=%s cam=%s%s%n", t, sa, sb, c == null ? "-" : String.format(Locale.US, "%.0f,%.0f", c[0], c[2]), jump ? " JUMP" : "");
                    out.flush();
                    la = sa; lb = sb;
                }
                if (c != null) lc = c;
                Thread.sleep(250);
            }
        }
        out.close();
    }

    static void readXZY(int pid, long[] addr, float[] X, float[] Y, float[] Z) throws IOException {
        byte[] page = new byte[4096];
        ByteBuffer pb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
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
    }

    static void readXZ(int pid, long[] addr, float[] X, float[] Z) throws IOException {
        byte[] page = new byte[4096];
        ByteBuffer pb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            long cur = -1; boolean ok = false;
            for (int i = 0; i < addr.length; i++) {
                long a = addr[i], pg = a & ~4095L;
                int off = (int) (a - pg);
                if (off > 4096 - 12) { X[i] = Z[i] = Float.NaN; continue; }
                if (pg != cur) { cur = pg; try { mem.seek(pg); mem.readFully(page); ok = true; } catch (IOException e) { ok = false; } }
                if (!ok) { X[i] = Z[i] = Float.NaN; continue; }
                X[i] = pb.getFloat(off); Z[i] = pb.getFloat(off + 8);
            }
        }
    }

    static void read(int pid, String candFile, String outFile) throws IOException {
        PrintStream out = outFile == null ? System.out : new PrintStream(new BufferedOutputStream(new FileOutputStream(outFile)));
        byte[] b = new byte[12];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r");
             BufferedReader br = new BufferedReader(new FileReader(candFile))) {
            for (String l; (l = br.readLine()) != null; ) {
                if (l.isEmpty() || l.startsWith("#")) continue;
                String a = l.split("\\s+")[0];
                long addr = Long.parseUnsignedLong(a, 16);
                try {
                    mem.seek(addr);
                    mem.readFully(b);
                    out.printf(Locale.US, "%s %.3f %.3f %.3f%n", a, bb.getFloat(0), bb.getFloat(4), bb.getFloat(8));
                } catch (IOException e) {
                    out.println(a + " ERR");
                }
            }
        }
        out.flush();
        if (outFile != null) out.close();
    }

    static void ptr(int pid, long target, long maxOff, String outFile) throws IOException {
        final PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outFile)));
        List<Region> rs = regions(pid, false);
        final long lo = target - maxOff, hi = target;
        final Map<Long, String> names = new HashMap<>();
        walk(pid, rs, (base, bb, len) -> {
            for (int o = 0; o + 8 <= len; o += 8) {
                long v = bb.getLong(o);
                if (v >= lo && v <= hi) out.printf("%x %x +%x%n", base + o, v, target - v);
            }
        });
        out.close();
    }
}
