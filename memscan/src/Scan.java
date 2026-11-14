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
