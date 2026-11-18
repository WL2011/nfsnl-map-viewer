package com.nfsnl.minimap;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * Foreground service: shows the minimap overlay, fed by {@link RootReader} (run as root through `su`), which reads
 * the game camera / cars / selected event from the game's memory. Read-only.
 */
public final class MinimapService extends Service {
    static final String ACTION_START = "start", ACTION_UPDATE = "update", ACTION_STOP = "stop";
    private static final String CHANNEL = "minimap";
    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private MinimapView view;
    private WindowManager.LayoutParams lp;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "小地图", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) { stopSelf(); return START_NOT_STICKY; }
        goForeground();
        running = true;
        if (view == null) addOverlay();
        if (rootProc == null) startRoot();
        applySettings();
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Intent stop = new Intent(this, MinimapService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pOpen = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("NFSNL 小地图运行中")
                .setContentText("点此打开设置")
                .setContentIntent(pOpen)
                .addAction(new Notification.Action.Builder(null, "停止", pStop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ------------------------------------------------------------------ overlay
    @SuppressLint("ClickableViewAccessibility")
    private void addOverlay() {
        view = new MinimapView(this);
        lp = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        Settings s = Settings.load(this);
        float dp = getResources().getDisplayMetrics().density;
        lp.x = s.posX >= 0 ? s.posX : Math.round(16 * dp);
        lp.y = s.posY >= 0 ? s.posY : Math.round(16 * dp);
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        view.setOnTouchListener(new android.view.View.OnTouchListener() {
            float dx, dy; int x0, y0; boolean drag; long down;
            final Runnable longPress = () -> {
                if (!drag) {
                    drag = true;   // swallow the up
                    startActivity(new Intent(MinimapService.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            };
            @Override public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY(); x0 = lp.x; y0 = lp.y; drag = false; down = SystemClock.uptimeMillis();
                        main.postDelayed(longPress, 700);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (!drag && Math.hypot(mx, my) > slop) { drag = true; main.removeCallbacks(longPress); }
                        if (drag && SystemClock.uptimeMillis() - down >= 0) {
                            lp.x = Math.max(0, Math.round(x0 + mx));
                            lp.y = Math.max(0, Math.round(y0 - my));
                            wm.updateViewLayout(view, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress);
                        if (drag) Settings.savePos(MinimapService.this, lp.x, lp.y);
                        else view.toggleOverview();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        return true;
                }
                return false;
            }
        });
        wm.addView(view, lp);
    }

    private void applySettings() {
        if (view == null) return;
        Settings s = Settings.load(this);
        int px = Math.round(s.sizeDp * getResources().getDisplayMetrics().density);
        if (lp.width != px) { lp.width = px; lp.height = px; wm.updateViewLayout(view, lp); }
        view.configure(s.radiusM, s.alphaPct / 100f, s.mirror, s.debug);
    }

    // ------------------------------------------------------------------ root (memory) source
    private Process rootProc;
    private Locator locator;

    private void startRoot() {
        locator = new Locator(TrackData.get(this));
        view.setLocator(locator);
        try {
            String apk = getApplicationInfo().sourceDir;
            rootProc = new ProcessBuilder("su", "-c", "CLASSPATH=" + apk + " app_process /system/bin " + RootReader.class.getName()).start();
        } catch (java.io.IOException e) {
            locator.status = "无法获取 root";
            view.onPose();
            return;
        }
        final Process proc = rootProc;
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                for (String l; (l = br.readLine()) != null; ) {
                    final String line = l;
                    main.post(() -> onRootLine(line));
                }
            } catch (java.io.IOException ignored) {}
            main.post(() -> { if (locator != null && rootProc == proc) { locator.status = "读取进程已退出"; locator.live = false; if (view != null) view.onPose(); } });
        }, "root-reader");
        t.setDaemon(true);
        t.start();
    }

    private void onRootLine(String l) {
        if (locator == null || view == null) return;
        if (l.startsWith("P ")) {
            String[] p = l.split(" ");
            if (p.length < 8) return;
            try {
                long t = Long.parseLong(p[1]);
                float x = Float.parseFloat(p[2]), y = Float.parseFloat(p[3]), z = Float.parseFloat(p[4]);
                locator.onPose(t, x, y, z, Float.parseFloat(p[5]), Float.parseFloat(p[7]));
                logPose(t, x, y, z);
            } catch (NumberFormatException ignored) { return; }
        } else if (l.startsWith("R ")) {
            String id = l.substring(2).trim();
            if (!id.equals(locator.trackId)) logLine("trackId " + id);
            locator.onTrackId(id);
            return;
        } else if (l.startsWith("C ") || l.startsWith("O ")) {
            String[] p = l.split(" ");
            if (p.length < 7) return;
            try {
                long t = Long.parseLong(p[1]);
                float x = Float.parseFloat(p[2]), y = Float.parseFloat(p[3]), z = Float.parseFloat(p[4]);
                float hx = Float.parseFloat(p[5]), hz = Float.parseFloat(p[6]);
                if (l.charAt(0) == 'C') locator.onCar(t, x, y, z, hx, hz); else locator.onOpp(t, x, z, hx, hz);
            } catch (NumberFormatException ignored) {}
            return;
        } else if (l.startsWith("S ")) {
            if (l.startsWith("S learn") || l.startsWith("S restored") || l.startsWith("S lost")) {
                logLine(l);
                return;
            }
            locator.onStatus(l.substring(2).trim());
        }
        view.onPose();
    }

    private void logLine(String l) {
        try { if (runLog != null) runLog.write("# " + l + "\n"); } catch (java.io.IOException ignored) {}
    }

    private void logPose(long t, float x, float y, float z) {
        try {
            if (runLog == null) {
                java.io.File dir = new java.io.File(getExternalFilesDir(null), "runs");
                dir.mkdirs();
                String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
                runLog = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(dir, name + "-mem.csv")));
                runLog.write("t_ms,x,y,z,map,track,race,d,speed,car,oppx,oppz\n");
            }
            Locator L = locator;
            boolean opp = L.oppVisible(t);
            runLog.write(String.format(java.util.Locale.US, "%d,%.2f,%.2f,%.2f,%s,%s,%s,%.1f,%.1f,%d,%s,%s\n", t, L.x, L.y, L.z,
                    L.map == null ? "" : L.map.key, L.track == null ? "" : L.track.name,
                    L.race == null ? "" : L.race.id + (L.raceGuessed ? "?" : ""), L.d, L.speed, L.usingCar ? 1 : 0,
                    opp ? String.format(java.util.Locale.US, "%.1f", L.oppX) : "", opp ? String.format(java.util.Locale.US, "%.1f", L.oppZ) : ""));
            long now = SystemClock.uptimeMillis();
            if (now - lastFlush > 2000) { runLog.flush(); lastFlush = now; }
        } catch (java.io.IOException ignored) {}
    }

    // ------------------------------------------------------------------ run log (Android/data/com.nfsnl.minimap/files/runs/*-mem.csv)
    private java.io.Writer runLog;
    private long lastFlush;

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (view != null) { wm.removeView(view); view = null; }
        if (rootProc != null) { rootProc.destroy(); rootProc = null; }
        try { if (runLog != null) runLog.close(); } catch (java.io.IOException ignored) {}
        super.onDestroy();
    }

    static void update(Context c) {
        if (running) c.startService(new Intent(c, MinimapService.class).setAction(ACTION_UPDATE));
    }
}
