package com.nfsnl.minimap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Runs as root in its own process (app_process with this APK on the classpath) and streams the game camera's
 * world transform on stdout, 20 times a second. Read-only: it only reads /proc/<pid>/mem.
 *
 * Path: libapp.so + SLOT holds a (tagged) pointer to the race camera object; its 4x4 row-major world matrix
 * starts at +0x30 (rows: right, up, forward, translation).
 *
 * Output lines:  "P <ms> x y z fx fy fz"  |  "S <status>"
 */
public final class RootReader {
    static final String PKG = "com.ea.game.nfs14_row";
    static final long SLOT = 0x378C158L;
    static final long MAT = 0x30;

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false);
        int pid = -1;
        long base = 0;
        RandomAccessFile mem = null;
        byte[] b = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        String last = "";
        while (true) {
            String status = null;
            if (pid < 0 || !new File("/proc/" + pid).exists()) {
                if (mem != null) { try { mem.close(); } catch (IOException ignored) {} mem = null; }
                pid = findPid();
                base = 0;
                if (pid < 0) status = "nogame";
            }
            if (status == null && base == 0) {
                base = findBase(pid);
                if (base == 0) status = "nolib";
                else {
                    try { mem = new RandomAccessFile("/proc/" + pid + "/mem", "r"); }
                    catch (IOException e) { status = "nomem"; base = 0; }
                }
            }
            if (status == null) {
                try {
                    mem.seek(base + SLOT);
                    mem.readFully(b, 0, 8);
                    long p = bb.getLong(0) & 0x00ffffffffffffffL;
                    if (p == 0) status = "noptr";
                    else {
                        mem.seek(p + MAT);
                        mem.readFully(b, 0, 64);
                        float fx = bb.getFloat(32), fy = bb.getFloat(36), fz = bb.getFloat(40);
                        float x = bb.getFloat(48), y = bb.getFloat(52), z = bb.getFloat(56);
                        float n = fx * fx + fy * fy + fz * fz;
                        if (!(n > 0.9f && n < 1.1f) || !(Math.abs(x) < 1e5f && Math.abs(y) < 1e5f && Math.abs(z) < 1e5f)) status = "badmat";
                        else out.printf(Locale.US, "P %d %.3f %.3f %.3f %.4f %.4f %.4f%n", System.currentTimeMillis(), x, y, z, fx, fy, fz);
                    }
                } catch (IOException e) {
                    status = "readerr";
                    pid = -1;
                }
            }
            if (status != null && !status.equals(last)) out.println("S " + status);
            last = status == null ? "" : status;
            out.flush();
            if (out.checkError()) return;      // the app went away
            Thread.sleep(status == null ? 50 : 1000);
        }
    }

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
             RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
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
                    mem.seek(a);
                    mem.readFully(h);
                    if (h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') return a;
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return 0;
    }
}
