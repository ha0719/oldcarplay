package com.mcool.player.home;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mcool.player.MusicBean;
import com.mcool.player.MusicService;
import com.mcool.player.R;
import com.mcool.player.SourceActivity;
import com.mcool.player.lx.LxLog;
import com.mcool.player.lx.OnlineSettings;
import com.mcool.player.music.LocalMusic;
import com.mcool.player.music.Lrc;
import com.mcool.player.music.MusicActivity;
import com.mcool.player.music.MusicCache;
import com.mcool.player.net.HttpUtil;
import com.mcool.player.online.OnlineRepo;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 车机桌面（HOME，CarPlay 样式）：Dock 10% | 音乐组件 60% | 天气 + 导航 30%。
 * 点音乐组件进入全屏播放页；返回键在桌面上不做任何事。
 * 刷新策略：桌面可见且正在播放时才计时；进度按秒更新，歌词只在换句时更新文字。
 */
public class LauncherActivity extends AppCompatActivity implements Dock.Host {

    public static final String EXTRA_SETTINGS = "open_settings";
    private static final int REQ_NAV = 100;   // 0..2 = Dock 自定义应用位

    private ImageView ivWall, ivCover, ivNav, ivWeather;
    private TextView tvTitle, tvLyric, tvPos, tvDur;
    private TextView tvCity, tvTemp, tvDesc, tvRange, tvNavSub;
    private View navMapGroup, navShortcutGroup, navTbtGroup;
    private MiniMapView miniMapView;
    private TextView tvMapStatus, tvMapNavHint;
    private NavTurnIconView ivNavTurn;
    private TextView tvNavSegDis, tvNavNextRoad, tvNavRemain, tvNavLimit;
    private SeekBar seek;
    private ImageButton btnPlay;
    private Button btnMode;
    private Dock dock;
    private WallpaperRotator wallpaper;
    private AmapNavMonitor navMonitor;
    private LocationManager locationManager;
    private boolean gpsListening;

    private final LocationListener mapGpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            if (loc != null) applyMapLocation(loc, true);
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private MusicService svc;
    private boolean bound, userSeeking, resumed;
    private final Handler h = new Handler();

    // 当前卡片显示的歌曲及其歌词
    private String shownKey;
    private List<Lrc.Line> lrc = new ArrayList<>();
    private int lrcIdx = -2, lastSec = -1, lastDurSec = -1, lyricLead = 2000;
    private String songLine = "";

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            svc = ((MusicService.MusicBinder) service).getService();
            bound = true;
            updateSong();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            bound = false;
        }
    };

    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { updateSong(); }
    };

    /** 只在桌面可见且播放中运行：有歌词 250ms 检查一次（只在换句时改文字），没歌词 1 秒 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress(false);
            if (resumed && svc != null && svc.isPng()) h.postDelayed(this, lrc.isEmpty() ? 1000 : 250);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather(false);
            h.postDelayed(this, Math.max(30, HomePrefs.weatherMinutes(LauncherActivity.this)) * 60000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        ivWall = findViewById(R.id.ivWallpaper);
        ivCover = findViewById(R.id.ivCover);
        ivNav = findViewById(R.id.ivNav);
        ivWeather = findViewById(R.id.ivWeather);
        tvTitle = findViewById(R.id.tvTitle);
        tvLyric = findViewById(R.id.tvLyric);
        tvPos = findViewById(R.id.tvPos);
        tvDur = findViewById(R.id.tvDur);
        tvCity = findViewById(R.id.tvCity);
        tvTemp = findViewById(R.id.tvTemp);
        tvDesc = findViewById(R.id.tvDesc);
        tvRange = findViewById(R.id.tvRange);
        tvNavSub = findViewById(R.id.tvNavSub);
        navMapGroup = findViewById(R.id.navMapGroup);
        miniMapView = findViewById(R.id.miniMapView);
        tvMapStatus = findViewById(R.id.tvMapStatus);
        tvMapNavHint = findViewById(R.id.tvMapNavHint);
        navShortcutGroup = findViewById(R.id.navShortcutGroup);
        navTbtGroup = findViewById(R.id.navTbtGroup);
        ivNavTurn = findViewById(R.id.ivNavTurn);
        tvNavSegDis = findViewById(R.id.tvNavSegDis);
        tvNavNextRoad = findViewById(R.id.tvNavNextRoad);
        tvNavRemain = findViewById(R.id.tvNavRemain);
        tvNavLimit = findViewById(R.id.tvNavLimit);
        seek = findViewById(R.id.seekBar);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        btnPlay = findViewById(R.id.btnPlay);
        btnMode = findViewById(R.id.btnMode);
        dock = new Dock(this, this);

        // ---- 音乐组件 ----
        findViewById(R.id.cardMusic).setOnClickListener(v -> openPlayer());
        findViewById(R.id.btnPrev).setOnClickListener(v -> { if (svc != null) svc.user().playPrev(); });
        findViewById(R.id.btnNext).setOnClickListener(v -> { if (svc != null) svc.user().playNext(); });
        btnPlay.setOnClickListener(v -> {
            if (svc == null) return;
            if (svc.getCurrentSong() == null) { openPlayer(); return; }
            if (svc.isPng()) svc.pausePlayer(); else svc.user().resumePlayer();
        });
        btnMode.setOnClickListener(v -> { if (svc != null) btnMode.setText(MusicService.modeShort(svc.cyclePlayMode())); });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) tvPos.setText(fmt(p * 1000L)); }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (svc != null) svc.seek(s.getProgress() * 1000);
                lastSec = -1;
            }
        });

        // ---- 天气（点一下静默刷新；城市/定位在桌面设置里）/ 导航 ----
        findViewById(R.id.cardWeather).setOnClickListener(v -> refreshWeather(true));
        View nav = findViewById(R.id.cardNav);
        nav.setOnClickListener(v -> {
            String c = HomePrefs.nav(this);
            if (c.isEmpty()) pickApp(REQ_NAV, "选择导航应用");
            else Dock.launch(this, ComponentName.unflattenFromString(c));
        });
        nav.setOnLongClickListener(v -> { pickApp(REQ_NAV, "选择导航应用"); return true; });

        Cards.apply(findViewById(R.id.root));
        wallpaper = new WallpaperRotator(this, ivWall);
        navMonitor = new AmapNavMonitor(this, this::updateNavCard);
        navMonitor.start();

        Intent si = new Intent(this, MusicService.class);
        startService(si);
        bindService(si, conn, Context.BIND_AUTO_CREATE);

        bindNav();
        double[] cachedLl = HomePrefs.lastLatLon(this);
        if (cachedLl != null) miniMapView.setWgsLocation(cachedLl[0], cachedLl[1], 0f, false, false);
        showWeather(WeatherRepo.cached(this));
        wallpaper.start();
        h.post(weatherTick);
        // 上次异常退出只记日志，不在桌面上弹提示（详情：桌面设置 → 音乐源 → 长按「刷新日志」）
        if (LxLog.lastCrash() != null) LxLog.w("上次异常退出：" + LxLog.lastCrash());
        LxLog.i("桌面已启动");
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SETTINGS, false)) h.post(this::showSettings);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_SETTINGS, false)) {
            intent.removeExtra(EXTRA_SETTINGS);
            h.post(this::showSettings);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(uiReceiver, new IntentFilter(MusicService.ACTION_UPDATE_UI));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        lyricLead = HomePrefs.lyricLeadMs(this);
        lrcIdx = -2;
        wallpaper.resume();
        dock.onResume(); // 应用可能被卸载/更新
        updateSong();
        bindNav();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(tick);
        dock.onPause();
        wallpaper.pause();
        stopMapGps();
        miniMapView.setActive(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(uiReceiver); } catch (Exception ignored) {}
        miniMapView.evictMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        wallpaper.stop();
        stopMapGps();
        if (miniMapView != null) miniMapView.release();
        if (navMonitor != null) navMonitor.stop();
        if (bound) {
            try { unbindService(conn); } catch (Exception ignored) {}
            bound = false;
        }
    }

    /** 桌面上按返回不退出 */
    @Override
    public void onBackPressed() {}

    /** 方控在桌面前台时也能用 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (svc != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_NEXT: svc.onMediaKey(keyCode); return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: svc.onMediaKey(keyCode); return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    svc.onMediaKey(keyCode);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY: svc.onMediaKey(keyCode); return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: svc.pausePlayer(); return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ================================================================= Dock.Host

    @Override
    public void onSlotEdit(int slot) {
        String c = HomePrefs.slot(this, slot);
        ComponentName cn = c.isEmpty() ? null : ComponentName.unflattenFromString(c);
        CharSequence label = cn == null ? null : HomePrefs.label(this, cn);
        if (cn == null || label == null) pickApp(slot, "选择第 " + (slot + 1) + " 个常用应用");
        else slotMenu(slot, label.toString());
    }

    @Override
    public void onSettings() { showSettings(); }

    // ================================================================= 音乐组件

    private void openPlayer() {
        startActivity(new Intent(this, MusicActivity.class));
    }

    private void updateSong() {
        if (svc == null) return;
        MusicBean s = svc.getCurrentSong();
        btnPlay.setImageResource(svc.isPng() ? R.drawable.pause : R.drawable.play);
        btnMode.setText(MusicService.modeShort(svc.getPlayMode()));
        h.removeCallbacks(tick);
        if (resumed && svc.isPng()) h.post(tick);
        else updateProgress(true);

        if (s == null) {
            setIfChanged(tvLyric, "还没有播放音乐");
            setIfChanged(tvTitle, "点这里进入音乐");
            if (shownKey != null) ivCover.setImageResource(R.drawable.noimg);
            shownKey = null;
            lrc = new ArrayList<>();
            return;
        }
        String artist = s.artist == null || s.artist.isEmpty() || "Unknown".equals(s.artist) ? "" : s.artist;
        String line = s.title + (artist.isEmpty() ? "" : " · " + artist);
        String status = svc.getStatusText();
        if (status != null && !status.isEmpty()) line += "  【" + status + "】";
        songLine = line;

        if (!s.path.equals(shownKey)) {
            shownKey = s.path;
            lrc = new ArrayList<>();
            lrcIdx = -2;
            lastSec = -1;
            lastDurSec = -1;
            ivCover.setImageResource(R.drawable.noimg);
            loadCover(s);
            loadLyric(s);
        }
        applyTexts(s);
    }

    /** 有歌词：歌词大字 + "歌名 · 歌手"小字；没歌词：歌名大字 + 歌手小字 */
    private void applyTexts(MusicBean s) {
        if (lrc.isEmpty()) {
            setIfChanged(tvLyric, s.title);
            String sub = songLine.substring(Math.min(songLine.length(), s.title.length())).replaceFirst("^ · ", "");
            setIfChanged(tvTitle, sub.isEmpty() ? " " : sub);
        } else {
            setIfChanged(tvTitle, songLine);
            showLyricLine(svc == null ? 0 : svc.getPosn(), true);
        }
    }

    private static void setIfChanged(TextView tv, CharSequence t) {
        if (!t.toString().contentEquals(tv.getText())) tv.setText(t);
    }

    private void loadCover(final MusicBean s) {
        final String key = s.path;
        if (s.online) {
            OnlineRepo.cover(s, bmp -> { if (bmp != null && key.equals(shownKey)) ivCover.setImageBitmap(bmp); });
            return;
        }
        HttpUtil.POOL.execute(() -> {
            final Bitmap b = MusicActivity.embeddedCover(s.path, 300);
            runOnUiThread(() -> { if (b != null && key.equals(shownKey)) ivCover.setImageBitmap(b); });
        });
    }

    private void loadLyric(final MusicBean s) {
        final String key = s.path;
        if (s.online) {
            OnlineRepo.lyric(s, text -> { if (key.equals(shownKey)) setLyrics(s, Lrc.parse(text)); });
            return;
        }
        HttpUtil.POOL.execute(() -> {
            final List<Lrc.Line> l = Lrc.parse(Lrc.readLocal(s.path));
            runOnUiThread(() -> { if (key.equals(shownKey)) setLyrics(s, l); });
        });
    }

    private void setLyrics(MusicBean s, List<Lrc.Line> l) {
        lrc = l;
        lrcIdx = -2;
        applyTexts(s);
        if (resumed && svc != null && svc.isPng()) {
            h.removeCallbacks(tick);
            h.post(tick);
        }
    }

    /** 歌词按「歌词提前」设置提前显示 */
    private void showLyricLine(long pos, boolean force) {
        if (lrc.isEmpty()) return;
        int i = Math.max(0, Lrc.indexAt(lrc, pos + lyricLead));
        if (force || i != lrcIdx) {
            lrcIdx = i;
            setIfChanged(tvLyric, lrc.get(i).text);
        }
    }

    /** 只在秒数/时长/歌词句变化时才改界面，避免无意义重绘 */
    private void updateProgress(boolean force) {
        if (svc == null) return;
        int pos = svc.getPosn(), dur = svc.getDur();
        showLyricLine(pos, false);
        if (userSeeking) return;
        int sec = pos / 1000, durSec = dur / 1000;
        if (force || durSec != lastDurSec) {
            lastDurSec = durSec;
            seek.setMax(Math.max(1, durSec));
            tvDur.setText(fmt(dur));
        }
        if (force || sec != lastSec) {
            lastSec = sec;
            seek.setProgress(sec);
            tvPos.setText(fmt(pos));
        }
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ================================================================= Dock 自定义应用

    /** 长按应用位：更换应用 / 更换图标 / 恢复默认图标 / 清空 */
    private void slotMenu(final int slot, final String appLabel) {
        String[] items = {"更换应用", "更换图标", "恢复默认图标", "清空这个位置"};
        new AlertDialog.Builder(this)
                .setTitle(appLabel)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: pickApp(slot, "更换第 " + (slot + 1) + " 个常用应用"); break;
                        case 1: pickIcon(slot); break;
                        case 2:
                            //noinspection ResultOfMethodCallIgnored
                            HomePrefs.slotIconFile(this, slot).delete();
                            dock.refresh();
                            break;
                        case 3:
                            HomePrefs.setSlot(this, slot, "");
                            dock.refresh();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从图标文件夹 / Download / U 盘里选 png/jpg，缩放到 128px 存进应用私有目录 */
    private void pickIcon(final int slot) {
        List<File> roots = new ArrayList<>();
        File iconDir = HomePrefs.defaultIconDir();
        if (!iconDir.exists()) iconDir.mkdirs();
        roots.add(iconDir);
        roots.add(new File(Environment.getExternalStorageDirectory(), "Download"));
        for (String p : LocalMusic.USB_PATHS) roots.add(new File(p));
        final List<File> found = new ArrayList<>();
        for (File r : roots) scanImages(r, 0, found);
        if (found.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("没有找到图标图片")
                    .setMessage("把 png / jpg 图标放到 " + iconDir.getAbsolutePath() + "，或 U 盘根目录，或：\nadb push icon.png " + iconDir.getAbsolutePath() + "/")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < found.size(); i++) names[i] = found.get(i).getName() + "    " + found.get(i).getParent();
        new AlertDialog.Builder(this)
                .setTitle("选择图标")
                .setItems(names, (d, w) -> saveSlotIcon(slot, found.get(w)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void scanImages(File dir, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory() || !dir.canRead() || depth > 1 || out.size() > 80) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) scanImages(f, depth + 1, out);
            else {
                String n = f.getName().toLowerCase(Locale.US);
                if ((n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp"))
                        && f.length() < 5 * 1024 * 1024 && !out.contains(f)) out.add(f);
            }
        }
    }

    private void saveSlotIcon(final int slot, final File src) {
        HttpUtil.POOL.execute(() -> {
            boolean ok = false;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(src.getAbsolutePath(), o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 128) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                Bitmap b = BitmapFactory.decodeFile(src.getAbsolutePath(), o2);
                if (b != null) {
                    int max = Math.max(b.getWidth(), b.getHeight());
                    if (max > 128) {
                        float k = 128f / max;
                        Bitmap sc = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
                        if (sc != b) b.recycle();
                        b = sc;
                    }
                    FileOutputStream fo = new FileOutputStream(HomePrefs.slotIconFile(this, slot));
                    b.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    fo.close();
                    b.recycle();
                    ok = true;
                }
            } catch (Throwable e) {
                LxLog.w("保存图标失败 " + src + "：" + e);
            }
            final boolean fok = ok;
            runOnUiThread(() -> {
                if (!fok) Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
                dock.refresh();
            });
        });
    }

    private void pickApp(int req, String title) {
        Intent i = new Intent(this, AllAppsActivity.class);
        i.putExtra(AllAppsActivity.EXTRA_PICK, true);
        i.putExtra(AllAppsActivity.EXTRA_TITLE, title);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        String comp = data.getStringExtra(AllAppsActivity.RESULT_COMPONENT);
        if (requestCode == REQ_NAV) {
            HomePrefs.setNav(this, comp);
            bindNav();
        } else if (requestCode >= 0 && requestCode < HomePrefs.SLOT_COUNT) {
            HomePrefs.setSlot(this, requestCode, comp);
            dock.refresh();
        }
    }

    // ================================================================= 导航

    private void bindNav() {
        String c = HomePrefs.nav(this);
        ComponentName cn = c.isEmpty() ? null : ComponentName.unflattenFromString(c);
        CharSequence label = cn == null ? null : HomePrefs.label(this, cn);
        if (cn == null || label == null) {
            ivNav.setImageResource(android.R.drawable.ic_menu_mylocation);
            tvNavSub.setText("未找到高德，点击选择导航应用");
            tvMapNavHint.setText("点击选择导航应用");
        } else {
            Drawable d = HomePrefs.icon(this, cn);
            if (d != null) ivNav.setImageDrawable(d);
            tvNavSub.setText("打开 " + label + " · 长按更换");
            tvMapNavHint.setText("打开 " + label);
        }
        if (navMonitor != null) updateNavCard(navMonitor.getState());
    }

    private void updateNavCard(AmapNavMonitor.NavState s) {
        if (s == null || !s.navigating) {
            navTbtGroup.setVisibility(View.GONE);
            boolean mapOn = HomePrefs.miniMapEnabled(this);
            if (mapOn) {
                navMapGroup.setVisibility(View.VISIBLE);
                navShortcutGroup.setVisibility(View.GONE);
                miniMapView.setActive(resumed);
                if (resumed) startMapGps();
            } else {
                navMapGroup.setVisibility(View.GONE);
                navShortcutGroup.setVisibility(View.VISIBLE);
                miniMapView.setActive(false);
                miniMapView.evictMemory();
                stopMapGps();
            }
            return;
        }
        navMapGroup.setVisibility(View.GONE);
        navShortcutGroup.setVisibility(View.GONE);
        navTbtGroup.setVisibility(View.VISIBLE);
        miniMapView.setActive(false);
        stopMapGps();

        ivNavTurn.setIconType(s.iconType);
        tvNavSegDis.setText(s.segRemainDis >= 0 ? AmapNavMonitor.formatDistance(s.segRemainDis) : "导航中");

        String act = AmapNavMonitor.actionText(s.iconType);
        String road = !s.nextRoad.isEmpty() ? s.nextRoad : s.curRoad;
        tvNavNextRoad.setText(road.isEmpty() ? act : (act + " · " + road));

        StringBuilder remain = new StringBuilder();
        if (s.routeRemainDis >= 0) remain.append("剩余 ").append(AmapNavMonitor.formatDistance(s.routeRemainDis));
        if (s.routeRemainTime >= 0) {
            if (remain.length() > 0) remain.append(" · ");
            remain.append("约 ").append(AmapNavMonitor.formatDuration(s.routeRemainTime));
        }
        if (s.demo) {
            if (remain.length() > 0) remain.append(" · ");
            remain.append("演示模式");
        }
        tvNavRemain.setText(remain.length() > 0 ? remain.toString() : "高德导航中 · 点击切回高德");

        if (s.limitedSpeed > 0) {
            tvNavLimit.setText("限速 " + s.limitedSpeed);
            tvNavLimit.setVisibility(View.VISIBLE);
        } else {
            tvNavLimit.setVisibility(View.GONE);
        }
    }

    private void startMapGps() {
        if (gpsListening || locationManager == null) return;
        try {
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = locationManager.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Throwable ignored) {}
            }
            if (best != null) {
                boolean fresh = (System.currentTimeMillis() - best.getTime()) < 5 * 60000L;
                applyMapLocation(best, fresh);
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 5f, mapGpsListener);
                gpsListening = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 20f, mapGpsListener);
                gpsListening = true;
            }
        } catch (Throwable t) {
            LxLog.w("小地图 GPS 启动失败：" + t);
        }
    }

    private void stopMapGps() {
        if (!gpsListening || locationManager == null) return;
        try {
            locationManager.removeUpdates(mapGpsListener);
        } catch (Throwable ignored) {}
        gpsListening = false;
    }

    private void applyMapLocation(Location loc, boolean liveFix) {
        boolean hasB = loc.hasBearing() && (!loc.hasSpeed() || loc.getSpeed() > 1.0f);
        miniMapView.setWgsLocation(loc.getLatitude(), loc.getLongitude(), loc.getBearing(), hasB, liveFix);
        HomePrefs.setLastLatLon(this, loc.getLatitude(), loc.getLongitude());
        if (liveFix) {
            int kmh = loc.hasSpeed() ? Math.round(loc.getSpeed() * 3.6f) : 0;
            tvMapStatus.setText(kmh > 1 ? ("巡航 · " + kmh + " km/h") : "GPS 已定位");
        } else {
            tvMapStatus.setText("上次位置 · 等待 GPS");
        }
    }

    // ================================================================= 天气

    private void refreshWeather(boolean force) {
        WeatherRepo.refresh(this, force, this::showWeather);
    }

    private void showWeather(WeatherRepo.Weather w) {
        if (w == null || !w.ok) {
            tvCity.setText(HomePrefs.weatherGps(this) ? "当前位置" : HomePrefs.city(this));
            tvTemp.setText("--°");
            tvDesc.setText(w != null && w.error != null ? "获取失败" : "");
            tvRange.setText("");
            ivWeather.setVisibility(View.GONE);
            return;
        }
        tvCity.setText(w.city);
        tvTemp.setText(Math.round(w.temp) + "°");
        tvDesc.setText(w.desc);
        tvRange.setText(Math.round(w.min) + "° ~ " + Math.round(w.max) + "°");
        int icon = WeatherRepo.iconRes(w.code, w.day);
        if (icon != 0) {
            ivWeather.setImageResource(icon);
            ivWeather.setVisibility(View.VISIBLE);
        } else {
            ivWeather.setVisibility(View.GONE);
        }
    }

    private void askCity() {
        final EditText et = new EditText(this);
        et.setTextSize(22);
        et.setSingleLine(true);
        et.setHint("例如：北京、上海、杭州");
        et.setText(HomePrefs.city(this));
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("天气城市")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    HomePrefs.setCity(this, et.getText().toString());
                    refreshWeather(true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askWeatherMinutes() {
        final int[] opts = {30, 60, 120, 180};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) labels[i] = opts[i] + " 分钟";
        new AlertDialog.Builder(this)
                .setTitle("天气刷新间隔")
                .setItems(labels, (d, which) -> HomePrefs.setWeatherMinutes(this, opts[which]))
                .show();
    }

    // ================================================================= 设置

    private String sizeText(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.1fGB", bytes / (1024.0 * 1024 * 1024));
        return (bytes / (1024 * 1024)) + "MB";
    }

    private void showSettings() {
        boolean mapOn = HomePrefs.miniMapEnabled(this);
        boolean demoOn = navMonitor != null && navMonitor.getState().demo;
        boolean gps = HomePrefs.weatherGps(this);
        int cacheMb = HomePrefs.cacheMaxMb(this);
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        String cur = currentHomePackage();
        items.add(getPackageName().equals(cur) ? "设为默认桌面（✓ 已经是默认桌面）"
                : "把本应用设为默认桌面（当前：" + homeLabel(cur) + "）");
        acts.add(this::setAsDefaultHome);
        items.add("播放音质优先：" + OnlineSettings.quality(this));
        acts.add(this::askQuality);
        items.add("歌曲缓存上限：" + (cacheMb <= 0 ? "不限" : sizeText(cacheMb * 1024L * 1024)) + "（已用 " + sizeText(MusicCache.usedBytes(this)) + "）");
        acts.add(this::askCacheMax);
        items.add("清空歌曲缓存");
        acts.add(this::askClearCache);
        items.add("音乐源（导入 / 切换 / 自检 / 日志）");
        acts.add(() -> startActivity(new Intent(this, SourceActivity.class)));
        items.add("卡片透明度：" + HomePrefs.cardAlpha(this) + "%");
        acts.add(this::askCardAlpha);
        items.add("歌词提前：" + (HomePrefs.lyricLeadMs(this) / 1000.0) + " 秒");
        acts.add(this::askLyricLead);
        items.add("天气城市：" + HomePrefs.city(this));
        acts.add(this::askCity);
        items.add("天气使用 GPS 定位：" + (gps ? "开（没有位置时用城市）" : "关"));
        acts.add(() -> { HomePrefs.setWeatherGps(this, !gps); refreshWeather(true); });
        items.add("天气刷新间隔：" + HomePrefs.weatherMinutes(this) + " 分钟");
        acts.add(this::askWeatherMinutes);
        items.add("壁纸文件夹：" + HomePrefs.wallDir(this) + "（" + wallpaper.count() + " 张）");
        acts.add(this::askWallDir);
        int wm = HomePrefs.wallMinutes(this);
        items.add("壁纸更换间隔：" + (wm <= 0 ? "不切换" : wm + " 分钟"));
        acts.add(this::askWallMinutes);
        items.add("立即换一张壁纸" + (wm <= 0 ? "（换完固定这张）" : ""));
        acts.add(() -> wallpaper.changeNow());
        items.add("重新扫描壁纸文件夹");
        acts.add(() -> {
            wallpaper.start();
            Toast.makeText(this, "找到 " + wallpaper.count() + " 张图片", Toast.LENGTH_SHORT).show();
        });
        items.add("巡航 2D 小地图：" + (mapOn ? "已开启（点此切回图标）" : "已关闭（点此开启）"));
        acts.add(() -> {
            HomePrefs.setMiniMapEnabled(this, !mapOn);
            updateNavCard(navMonitor != null ? navMonitor.getState() : null);
        });
        items.add(demoOn ? "关闭导航卡片演示预览" : "导航卡片演示预览（测试 TBT 样式）");
        acts.add(() -> { if (navMonitor != null) navMonitor.toggleDemo(); });
        items.add("停止播放音乐");
        acts.add(() -> { if (svc != null) svc.stopPlayback(); });
        items.add("打开系统原桌面（本次）");
        acts.add(this::openSystemLauncher);
        items.add("恢复系统原桌面为默认（博泰桌面）");
        acts.add(this::restoreSystemHome);
        new AlertDialog.Builder(this)
                .setTitle("桌面设置")
                .setItems(items.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void askQuality() {
        final String[] qs = {"128k", "320k", "flac"};
        String[] labels = {
                "128k 优先（默认，省流量）：128k → 320k → flac",
                "320k 优先（高品质）：320k → 128k → flac",
                "flac 优先（无损，4.4 上能否播放未验证）：flac → 320k → 128k",
        };
        new AlertDialog.Builder(this)
                .setTitle("播放音质优先（拿不到时自动换下一个）")
                .setItems(labels, (d, w) -> {
                    OnlineSettings.setQuality(this, qs[w]);
                    Toast.makeText(this, "已设为 " + qs[w] + " 优先，下一首生效", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void askCacheMax() {
        final int[] opts = {1024, 2048, 4096, 8192, 0};
        String[] labels = {"1GB", "2GB（默认）", "4GB", "8GB", "不限"};
        new AlertDialog.Builder(this)
                .setTitle("歌曲缓存上限（满了先删最久没听的）\n存放位置：" + MusicCache.dir(this))
                .setItems(labels, (d, w) -> {
                    HomePrefs.setCacheMaxMb(this, opts[w]);
                    MusicCache.applyMaxBytes(this);
                    Toast.makeText(this, "缓存上限：" + labels[w], Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void askClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("清空歌曲缓存？")
                .setMessage("已用 " + sizeText(MusicCache.usedBytes(this)) + "。清空后，断网时这些歌就不能播了。")
                .setPositiveButton("清空", (d, w) -> MusicCache.clear(this, svc == null ? null : svc.getPlayingCacheKey(),
                        () -> Toast.makeText(this, "缓存已清空", Toast.LENGTH_SHORT).show()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void askCardAlpha() {
        final int[] opts = {20, 30, 40, 50, 60, 70, 80, 90};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) labels[i] = opts[i] + "%" + (opts[i] == 50 ? "（默认）" : "") + (opts[i] <= 30 ? "（更透明）" : opts[i] >= 80 ? "（更深）" : "");
        new AlertDialog.Builder(this)
                .setTitle("卡片透明度（数字越大卡片越深）")
                .setItems(labels, (d, w) -> {
                    HomePrefs.setCardAlpha(this, opts[w]);
                    Cards.apply(findViewById(R.id.root));
                })
                .show();
    }

    private void askLyricLead() {
        final int[] opts = {0, 500, 1000, 1500, 2000, 2500, 3000};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) labels[i] = (opts[i] / 1000.0) + " 秒" + (opts[i] == 2000 ? "（默认）" : "");
        new AlertDialog.Builder(this)
                .setTitle("歌词提前显示")
                .setItems(labels, (d, w) -> {
                    HomePrefs.setLyricLeadMs(this, opts[w]);
                    lyricLead = opts[w];
                    lrcIdx = -2;
                    updateProgress(true);
                })
                .show();
    }

    private void askWallDir() {
        final EditText et = new EditText(this);
        et.setTextSize(20);
        et.setSingleLine(true);
        et.setText(HomePrefs.wallDir(this));
        new AlertDialog.Builder(this)
                .setTitle("壁纸文件夹（可填 U 盘目录，如 /mnt/udisk/wallpapers）")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) p = HomePrefs.defaultWallDir().getAbsolutePath();
                    HomePrefs.setWallDir(this, p);
                    wallpaper.start();
                    Toast.makeText(this, "找到 " + wallpaper.count() + " 张图片", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askWallMinutes() {
        final int[] opts = {0, 1, 5, 10, 30, 60};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) labels[i] = opts[i] == 0 ? "不切换（固定当前这张）" : opts[i] + " 分钟" + (opts[i] == 10 ? "（默认）" : "");
        new AlertDialog.Builder(this)
                .setTitle("壁纸更换间隔")
                .setItems(labels, (d, which) -> {
                    HomePrefs.setWallMinutes(this, opts[which]);
                    wallpaper.start();
                })
                .show();
    }

    private void openSystemLauncher() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(HomePrefs.SYSTEM_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Intent li = getPackageManager().getLaunchIntentForPackage(HomePrefs.SYSTEM_LAUNCHER);
            if (li != null) startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            else Toast.makeText(this, "没找到系统桌面 " + HomePrefs.SYSTEM_LAUNCHER, Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================= 默认桌面

    private static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    /** 当前默认桌面的包名；没有设默认时返回 "android"（系统选择框） */
    private String currentHomePackage() {
        try {
            android.content.pm.ResolveInfo ri = getPackageManager()
                    .resolveActivity(homeIntent(), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
        } catch (Exception e) {
            return "";
        }
    }

    private String homeLabel(String pkg) {
        if (pkg == null || pkg.isEmpty() || "android".equals(pkg)) return "未设置";
        if (getPackageName().equals(pkg)) return "车机桌面";
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void setAsDefaultHome() {
        if (getPackageName().equals(currentHomePackage())) {
            Toast.makeText(this, "车机桌面已经是默认桌面", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("设为默认桌面")
                .setMessage("接下来会弹出「选择桌面」：\n请选「车机桌面」，再点「始终」。\n\n（以后想换回来：桌面设置 → 恢复系统原桌面为默认）")
                .setPositiveButton("好的", (d, w) -> showHomeChooser())
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreSystemHome() {
        new AlertDialog.Builder(this)
                .setTitle("恢复系统原桌面")
                .setMessage("接下来会弹出「选择桌面」：\n请选博泰桌面（" + homeLabel(HomePrefs.SYSTEM_LAUNCHER) + "），再点「始终」。")
                .setPositiveButton("好的", (d, w) -> showHomeChooser())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * Android 4.4 不允许普通应用直接改默认桌面。做法：临时启用一个新的 HOME 组件 → 系统发现候选桌面变了，
     * 作废原来的"始终"设置并弹出选择框 → 马上禁用这个组件（选择框里不会出现它）。
     */
    private void showHomeChooser() {
        android.content.pm.PackageManager pm = getPackageManager();
        ComponentName fake = FakeHomeActivity.component(this);
        try {
            pm.setComponentEnabledSetting(fake, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
            startActivity(homeIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            LxLog.i("已请求重新选择默认桌面（原默认：" + homeLabel(currentHomePackage()) + "）");
        } catch (Exception e) {
            LxLog.e("弹出桌面选择框失败", e);
            Toast.makeText(this, "弹出选择框失败，请到 设置 → 应用 → 当前默认桌面 → 清除默认设置", Toast.LENGTH_LONG).show();
        } finally {
            try {
                pm.setComponentEnabledSetting(fake, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) {
            }
        }
    }
}
