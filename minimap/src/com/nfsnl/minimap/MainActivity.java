package com.nfsnl.minimap;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Pick the race, tune the minimap, start/stop the overlay. */
public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 7;
    private TrackData data;
    private Settings s;
    private Spinner mapSp, trackSp, raceSp;
    private View trackRow;
    private Button startBtn;
    private TextView status;
    private float dp;
    private boolean filling;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        data = TrackData.get(this);
        s = Settings.load(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = px(20);
        col.setPadding(pad, pad, pad, pad);

        TextView title = text("NFSNL Minimap", 24, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(title);
        TextView sub = text("在游戏左下角悬浮显示小地图，实时跟随车辆，自动识别地图和比赛（需要 root）。没有 root 时改为读取屏幕上的 PROGRESS，需要在下面手动选比赛。", 13, 0xFF9AA1AD);
        sub.setPadding(0, px(4), 0, px(16));
        col.addView(sub);

        mapSp = new Spinner(this);
        trackSp = new Spinner(this);
        raceSp = new Spinner(this);
        TextView manual = text("手动选择（仅读屏模式使用）", 13, 0xFF9AA1AD);
        manual.setPadding(0, 0, 0, px(4));
        col.addView(manual);
        col.addView(row("地图", mapSp));
        trackRow = row("路线", trackSp);
        col.addView(trackRow);
        col.addView(row("比赛", raceSp));

        col.addView(slider("大小", 140, 400, s.sizeDp, "dp", v -> s.sizeDp = v));
        col.addView(slider("不透明度", 30, 100, s.alphaPct, "%", v -> s.alphaPct = v));
        col.addView(slider("视野半径", 150, 900, s.radiusM, "m", v -> s.radiusM = v));

        CheckBox mirror = new CheckBox(this);
        mirror.setText("左右镜像（地图方向和游戏相反时勾选）");
        mirror.setChecked(s.mirror);
        mirror.setOnCheckedChangeListener((v, c) -> { s.mirror = c; changed(); });
        col.addView(mirror);
        CheckBox debug = new CheckBox(this);
        debug.setText("在小地图上显示识别读数");
        debug.setChecked(s.debug);
        debug.setOnCheckedChangeListener((v, c) -> { s.debug = c; changed(); });
        col.addView(debug);

        startBtn = new Button(this);
        startBtn.setAllCaps(false);
        startBtn.setTextSize(16);
        startBtn.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFF7A1A);
        g.setCornerRadius(px(10));
        startBtn.setBackground(g);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52));
        bl.topMargin = px(18);
        col.addView(startBtn, bl);
        startBtn.setOnClickListener(v -> {
            if (MinimapService.running) {
                startService(new Intent(this, MinimapService.class).setAction(MinimapService.ACTION_STOP));
                startBtn.postDelayed(this::refresh, 300);
            } else start();
        });

        status = text("", 13, 0xFF9AA1AD);
        status.setPadding(0, px(14), 0, 0);
        col.addView(status);

        TextView help = text("用法：启动 → 允许“显示在其他应用上层”（和 root 授权）→ 回到游戏开赛，地图和比赛会自动识别。\n"
                + "· 轻点小地图：切换跟随 / 全图\n· 拖动：移动位置\n· 长按：回到这里换比赛", 13, 0xFF9AA1AD);
        help.setPadding(0, px(14), 0, 0);
        col.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF121419);
        sv.addView(col);
        setContentView(sv);

        fillMaps();
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = MinimapService.running;
        startBtn.setText(on ? "停止小地图" : "启动小地图");
        if (!android.provider.Settings.canDrawOverlays(this)) status.setText("需要“显示在其他应用上层”权限，点启动后去设置里打开。");
        else if (!status.getText().toString().startsWith("root") || !on) status.setText(on ? "小地图运行中，回到游戏即可。" : "就绪。");
    }

    private void start() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        status.setText("检测 root…");
        startBtn.setEnabled(false);
        new Thread(() -> {
            boolean root = hasRoot();
            runOnUiThread(() -> {
                startBtn.setEnabled(true);
                if (root) {
                    s.save(this);
                    startForegroundService(new Intent(this, MinimapService.class).setAction(MinimapService.ACTION_START_ROOT));
                    status.setText("root 可用：读取游戏内存，自动识别地图和比赛。");
                    startBtn.postDelayed(this::refresh, 300);
                } else {
                    status.setText("没有 root：改用读屏模式，请允许录制屏幕，并手动选好比赛。");
                    MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
                }
            });
        }).start();
    }

    /** `su -c id` reports uid 0 within a few seconds. */
    private static boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            final String[] line = {null};
            Thread t = new Thread(() -> { try { line[0] = br.readLine(); } catch (java.io.IOException ignored) {} });
            t.start();
            t.join(8000);
            p.destroy();
            return line[0] != null && line[0].contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req != REQ_CAPTURE) return;
        if (code != RESULT_OK || data == null) { status.setText("没有获得录屏授权，小地图无法跟随车辆。"); return; }
        s.save(this);
        startForegroundService(new Intent(this, MinimapService.class).setAction(MinimapService.ACTION_START)
                .putExtra(MinimapService.EXTRA_CODE, code).putExtra(MinimapService.EXTRA_DATA, data));
        startBtn.postDelayed(this::refresh, 300);
    }

    // ------------------------------------------------------------------ selection
    private void fillMaps() {
        filling = true;
        List<String> names = new ArrayList<>();
        for (TrackData.MapInfo m : data.maps) names.add(m.title());
        mapSp.setAdapter(adapter(names));
        mapSp.setSelection(clamp(s.map, names.size()));
        mapSp.setOnItemSelectedListener(listener(i -> {
            if (i != s.map) { s.map = i; s.track = 0; s.race = 0; }
            fillTracks();
        }));
        fillTracks();
    }

    private void fillTracks() {
        filling = true;
        TrackData.MapInfo m = data.maps.get(clamp(s.map, data.maps.size()));
        List<String> names = new ArrayList<>();
        for (TrackData.Track t : m.tracks) names.add(t.name + String.format("  ·  %.1f km", t.len / 1000f));
        trackSp.setAdapter(adapter(names));
        trackSp.setSelection(clamp(s.track, names.size()));
        trackRow.setVisibility(names.size() > 1 ? View.VISIBLE : View.GONE);
        trackSp.setOnItemSelectedListener(listener(i -> {
            if (i != s.track) { s.track = i; s.race = 0; }
            fillRaces();
        }));
        fillRaces();
    }

    private void fillRaces() {
        filling = true;
        TrackData.Track t = s.trackOf(data);
        List<String> names = new ArrayList<>();
        if (t != null) for (TrackData.Race r : t.races) names.add(r.title() + "  ·  " + r.subtitle());
        raceSp.setAdapter(adapter(names));
        raceSp.setSelection(clamp(s.race, names.size()));
        raceSp.setOnItemSelectedListener(listener(i -> { s.race = i; changed(); }));
        filling = false;
        changed();
    }

    private void changed() {
        if (filling) return;
        s.save(this);
        MinimapService.update(this);
    }

    // ------------------------------------------------------------------ widgets
    private interface IntCb { void on(int v); }

    private AdapterView.OnItemSelectedListener listener(IntCb cb) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { cb.on(i); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
    }

    private ArrayAdapter<String> adapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private View row(String label, View v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(4), 0, px(4));
        TextView t = text(label, 15, 0xFFE8EAEE);
        r.addView(t, new LinearLayout.LayoutParams(px(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private View slider(String label, int min, int max, int value, String unit, IntCb cb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(6), 0, px(6));
        r.addView(text(label, 15, 0xFFE8EAEE), new LinearLayout.LayoutParams(px(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        TextView val = text(value + " " + unit, 13, 0xFF9AA1AD);
        val.setGravity(Gravity.END);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { val.setText((p + min) + " " + unit); cb.on(p + min); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { changed(); }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(val, new LinearLayout.LayoutParams(px(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }

    private static int clamp(int i, int n) { return Math.max(0, Math.min(i, n - 1)); }
}
