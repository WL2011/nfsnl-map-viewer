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
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * Foreground service: shows the minimap overlay and reads the race HUD from a screen capture
 * (MediaProjection, granted by the user). It never touches the game process.
 */
public final class MinimapService extends Service {
    static final String ACTION_START = "start", ACTION_START_ROOT = "startRoot", ACTION_UPDATE = "update", ACTION_STOP = "stop";
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    private static final String CHANNEL = "minimap";
    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private MinimapView view;
    private WindowManager.LayoutParams lp;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread capThread;
    private Handler cap;
    private final HudReader hud = new HudReader();
    private long lastRead;
    private int capW, capH;

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
        boolean capture = ACTION_START.equals(a) || projection != null;
        goForeground(capture);
        running = true;
        if (view == null) addOverlay();
        if (ACTION_START.equals(a) && projection == null) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (data != null) startCapture(code, data);
        }
        if (ACTION_START_ROOT.equals(a) && rootProc == null) startRoot();
        applySettings();
        return START_NOT_STICKY;
    }

    private void goForeground(boolean capture) {
        Intent stop = new Intent(this, MinimapService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pOpen = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("NFSNL 小地图运行中")
                .setContentText("点此选择比赛")
                .setContentIntent(pOpen)
                .addAction(new Notification.Action.Builder(null, "停止", pStop).build())
                .setOngoing(true)
                .build();
        if (capture && Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
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
        TrackData d = TrackData.get(this);
        int px = Math.round(s.sizeDp * getResources().getDisplayMetrics().density);
        if (lp.width != px) { lp.width = px; lp.height = px; wm.updateViewLayout(view, lp); }
        view.configure(s.trackOf(d), s.raceOf(d), s.radiusM, s.alphaPct / 100f, s.mirror, s.debug);
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
                locator.onPose(t, x, y, z);
                logPose(t, x, y, z);
            } catch (NumberFormatException ignored) { return; }
        } else if (l.startsWith("S ")) {
            locator.onStatus(l.substring(2).trim());
        }
        view.onPose();
    }

    private void logPose(long t, float x, float y, float z) {
        try {
            if (runLog == null) {
                java.io.File dir = new java.io.File(getExternalFilesDir(null), "runs");
                dir.mkdirs();
                String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
                runLog = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(dir, name + "-mem.csv")));
                runLog.write("t_ms,x,y,z,map,track,race,d,speed\n");
            }
            Locator L = locator;
            runLog.write(String.format(java.util.Locale.US, "%d,%.2f,%.2f,%.2f,%s,%s,%s,%.1f,%.1f\n", t, x, y, z,
                    L.map == null ? "" : L.map.key, L.track == null ? "" : L.track.name,
                    L.race == null ? "" : L.race.id + (L.raceGuessed ? "?" : ""), L.d, L.speed));
            long now = SystemClock.uptimeMillis();
            if (now - lastFlush > 2000) { runLog.flush(); lastFlush = now; }
        } catch (java.io.IOException ignored) {}
    }

    // ------------------------------------------------------------------ capture
    private void startCapture(int code, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) return;
        capThread = new HandlerThread("capture");
        capThread.start();
        cap = new Handler(capThread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(MinimapService.this::stopSelf); }
        }, main);
        int[] sz = captureSize();
        capW = sz[0]; capH = sz[1];
        reader = newReader(capW, capH);
        display = projection.createVirtualDisplay("nfsnl-minimap", capW, capH, sz[2],
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, cap);
    }

    /** Half the real screen size (the HUD digits stay ~50 px tall), plus density. */
    private int[] captureSize() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[]{(m.widthPixels / 2) & ~1, (m.heightPixels / 2) & ~1, m.densityDpi / 2};
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        r.setOnImageAvailableListener(this::onImage, cap);
        return r;
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastRead < 60) return;
            lastRead = now;
            Image.Plane p = img.getPlanes()[0];
            final HudReader.Result res = hud.read(p.getBuffer(), p.getRowStride(), p.getPixelStride(), img.getWidth(), img.getHeight());
            log(img.getTimestamp(), res);
            main.post(() -> { if (view != null) view.onReading(res); });
        } catch (RuntimeException ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    // ------------------------------------------------------------------ run log (Android/data/com.nfsnl.minimap/files/runs/*.csv)
    private java.io.Writer runLog;
    private long lastFlush;

    private void log(long tsNanos, HudReader.Result r) {
        try {
            if (runLog == null) {
                java.io.File dir = new java.io.File(getExternalFilesDir(null), "runs");
                dir.mkdirs();
                String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
                runLog = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(dir, name + ".csv")));
                Settings s = Settings.load(this);
                TrackData.Race race = s.raceOf(TrackData.get(this));
                runLog.write("# capture " + capW + "x" + capH + " selected map=" + s.map + " track=" + s.track + " race=" + (race == null ? "-" : race.title()) + "\n");
                runLog.write("t_ms,hud,pct,kmh,shift\n");
            }
            runLog.write(String.format(java.util.Locale.US, "%d,%d,%d,%d,%s\n", tsNanos / 1000000L, r.hud ? 1 : 0, r.pct, r.speed,
                    Float.isNaN(r.shift) ? "" : String.format(java.util.Locale.US, "%.4f", r.shift)));
            long now = SystemClock.uptimeMillis();
            if (now - lastFlush > 2000) { runLog.flush(); lastFlush = now; }
        } catch (java.io.IOException e) {
            Log.w("NFSNLMM", "log", e);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (display == null) return;
        int[] sz = captureSize();
        if (sz[0] == capW && sz[1] == capH) return;
        capW = sz[0]; capH = sz[1];
        ImageReader old = reader;
        reader = newReader(capW, capH);
        display.resize(capW, capH, sz[2]);
        display.setSurface(reader.getSurface());
        old.close();
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (view != null) { wm.removeView(view); view = null; }
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (capThread != null) capThread.quitSafely();
        if (rootProc != null) { rootProc.destroy(); rootProc = null; }
        try { if (runLog != null) runLog.close(); } catch (java.io.IOException ignored) {}
        super.onDestroy();
    }

    static void update(Context c) {
        if (running) c.startService(new Intent(c, MinimapService.class).setAction(ACTION_UPDATE));
    }
}
