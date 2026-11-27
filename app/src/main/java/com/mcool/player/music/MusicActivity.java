package com.mcool.player.music;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mcool.player.MusicBean;
import com.mcool.player.MusicService;
import com.mcool.player.OnlineActivity;
import com.mcool.player.R;
import com.mcool.player.home.Cards;
import com.mcool.player.home.Dock;
import com.mcool.player.home.HomePrefs;
import com.mcool.player.home.LauncherActivity;
import com.mcool.player.home.WallpaperRotator;
import com.mcool.player.net.HttpUtil;
import com.mcool.player.online.OnlineRepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 全屏播放页（CarPlay 正在播放样式）：左侧 Dock，右侧封面 + 歌词（一次一句）+ 控制。
 * 控制：播放顺序 / 上一首 / 播放 / 下一首 / ♥ 收藏（内置「我喜欢」）/ 切换歌单（长按打开歌单页）/ 搜索。
 * 刷新：只在可见且播放中计时；进度按秒更新，歌词只在换句时改文字。
 */
public class MusicActivity extends AppCompatActivity implements Dock.Host {

    private ImageView ivCover;
    private TextView tvLyric, tvTitle, tvArtist, tvList, tvPos, tvDur;
    private SeekBar seek;
    private ImageButton btnPlay;
    private Button btnMode, btnLove, btnCycle;
    private Dock dock;

    private MusicService svc;
    private boolean bound, resumed, userSeeking, cycling;
    private String shownKey;
    private List<Lrc.Line> lrc = new ArrayList<>();
    private int lrcIdx = -2, lastSec = -1, lastDurSec = -1, lyricLead = 2000;
    private final Handler h = new Handler();

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

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress(false);
            if (resumed && svc != null && svc.isPng()) h.postDelayed(this, lrc.isEmpty() ? 1000 : 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music);
        WallpaperRotator.applyTo(findViewById(R.id.ivWallpaper));
        Cards.apply(findViewById(R.id.root));
        dock = new Dock(this, this);

        ivCover = findViewById(R.id.ivCover);
        tvLyric = findViewById(R.id.tvLyric);
        tvTitle = findViewById(R.id.tvTitle);
        tvArtist = findViewById(R.id.tvArtist);
        tvList = findViewById(R.id.tvList);
        tvPos = findViewById(R.id.tvPos);
        tvDur = findViewById(R.id.tvDur);
        seek = findViewById(R.id.seekBar);
        btnPlay = findViewById(R.id.btnPlay);
        btnMode = findViewById(R.id.btnMode);
        btnLove = findViewById(R.id.btnLove);
        btnCycle = findViewById(R.id.btnCycle);

        findViewById(R.id.btnBack).setOnClickListener(v -> goHome());
        findViewById(R.id.btnPrev).setOnClickListener(v -> { if (svc != null) svc.user().playPrev(); });
        findViewById(R.id.btnNext).setOnClickListener(v -> { if (svc != null) svc.user().playNext(); });
        btnPlay.setOnClickListener(v -> {
            if (svc == null) return;
            if (svc.getCurrentSong() == null) { cycle(); return; }
            if (svc.isPng()) svc.pausePlayer(); else svc.user().resumePlayer();
        });
        btnMode.setOnClickListener(v -> {
            if (svc == null) return;
            int m = svc.cyclePlayMode();
            btnMode.setText(MusicService.modeShort(m));
            Toast.makeText(this, MusicService.modeName(m), Toast.LENGTH_SHORT).show();
        });
        btnLove.setOnClickListener(v -> {
            if (svc == null || svc.getCurrentSong() == null) return;
            boolean on = PlaylistStore.toggleLove(this, svc.getCurrentSong());
            btnLove.setText(on ? "♥" : "♡");
            Toast.makeText(this, on ? "已收藏到「我喜欢」" : "已取消收藏", Toast.LENGTH_SHORT).show();
        });
        btnCycle.setOnClickListener(v -> cycle());
        btnCycle.setOnLongClickListener(v -> {
            startActivity(new Intent(this, PlaylistActivity.class).putExtra(PlaylistActivity.EXTRA_OPEN, PlaylistActivity.QUEUE));
            return true;
        });
        findViewById(R.id.btnSearch).setOnClickListener(v -> startActivity(new Intent(this, OnlineActivity.class)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) tvPos.setText(fmt(p * 1000L)); }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (svc != null) svc.seek(s.getProgress() * 1000);
                lastSec = -1;
            }
        });

        bindService(new Intent(this, MusicService.class), conn, Context.BIND_AUTO_CREATE);
    }

    /** 切换到下一个歌单（按 歌单 → 列表顺序） */
    private void cycle() {
        if (svc == null || cycling) return;
        cycling = true;
        btnCycle.setText("切换中…");
        svc.cycleList((name, count, err) -> {
            cycling = false;
            btnCycle.setText("切换歌单");
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "切换到「" + name + "」（" + count + " 首）", Toast.LENGTH_SHORT).show();
            updateSong();
        });
    }

    private void goHome() {
        if (isTaskRoot()) {
            startActivity(new Intent(this, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }

    // ================================================================= Dock.Host

    @Override
    public void onSlotEdit(int slot) {
        Toast.makeText(this, "回到桌面，长按左侧图标可以更换应用", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSettings() { Dock.openHomeSettings(this); }

    // ================================================================= 生命周期

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
        Cards.apply(findViewById(R.id.root));
        dock.onResume();
        updateSong();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(tick);
        dock.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(uiReceiver); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (bound) {
            try { unbindService(conn); } catch (Exception ignored) {}
            bound = false;
        }
    }

    @Override
    public void onBackPressed() { goHome(); }

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

    // ================================================================= 显示

    private static void setIfChanged(TextView tv, CharSequence t) {
        if (!t.toString().contentEquals(tv.getText())) tv.setText(t);
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
            setIfChanged(tvTitle, "点「切换歌单」开始播放");
            setIfChanged(tvArtist, "长按「切换歌单」可以查看全部歌单和榜单");
            setIfChanged(tvList, "");
            btnLove.setText("♡");
            if (shownKey != null) ivCover.setImageResource(R.drawable.noimg);
            shownKey = null;
            lrc = new ArrayList<>();
            return;
        }
        btnLove.setText(PlaylistStore.isLoved(this, s) ? "♥" : "♡");
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
        setIfChanged(tvTitle, s.title);
        StringBuilder sub = new StringBuilder();
        if (s.artist != null && !s.artist.isEmpty() && !"Unknown".equals(s.artist)) sub.append(s.artist);
        if (s.album != null && !s.album.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(s.album);
        String q = svc.getCurrentQuality();
        if (q != null && !q.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(q);
        String st = svc.getStatusText();
        if (st != null && !st.isEmpty()) sub.append("  【").append(st).append("】");
        setIfChanged(tvArtist, sub.length() == 0 ? " " : sub.toString());
        String qn = svc.getQueueName();
        setIfChanged(tvList, (qn == null || qn.isEmpty() ? "播放队列" : qn)
                + " · " + (svc.getCurrentPosition() + 1) + " / " + svc.getQueue().size());
        if (lrc.isEmpty()) setIfChanged(tvLyric, s.title);
    }

    private void loadCover(final MusicBean s) {
        final String key = s.path;
        if (s.online) {
            OnlineRepo.cover(s, bmp -> { if (bmp != null && key.equals(shownKey)) ivCover.setImageBitmap(bmp); });
            return;
        }
        HttpUtil.POOL.execute(() -> {
            final Bitmap b = embeddedCover(s.path, 400);
            runOnUiThread(() -> { if (b != null && key.equals(shownKey)) ivCover.setImageBitmap(b); });
        });
    }

    /** 本地歌曲内嵌封面（缩小解码 + RGB_565） */
    public static Bitmap embeddedCover(String path, int maxPx) {
        android.media.MediaMetadataRetriever r = null;
        try {
            r = new android.media.MediaMetadataRetriever();
            r.setDataSource(path);
            byte[] d = r.getEmbeddedPicture();
            if (d == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(d, 0, d.length, o);
        } catch (Throwable e) {
            return null;
        } finally {
            if (r != null) try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private void loadLyric(final MusicBean s) {
        final String key = s.path;
        if (s.online) {
            OnlineRepo.lyric(s, text -> { if (key.equals(shownKey)) setLyrics(Lrc.parse(text)); });
            return;
        }
        HttpUtil.POOL.execute(() -> {
            final List<Lrc.Line> l = Lrc.parse(Lrc.readLocal(s.path));
            runOnUiThread(() -> { if (key.equals(shownKey)) setLyrics(l); });
        });
    }

    private void setLyrics(List<Lrc.Line> l) {
        lrc = l;
        lrcIdx = -2;
        updateProgress(true);
        if (resumed && svc != null && svc.isPng()) {
            h.removeCallbacks(tick);
            h.post(tick);
        }
    }

    /** 只在秒数/时长/歌词句变化时才改界面；歌词按「歌词提前」设置提前显示 */
    private void updateProgress(boolean force) {
        if (svc == null) return;
        int pos = svc.getPosn(), dur = svc.getDur();
        if (!lrc.isEmpty()) {
            int i = Math.max(0, Lrc.indexAt(lrc, pos + lyricLead));
            if (force || i != lrcIdx) {
                lrcIdx = i;
                setIfChanged(tvLyric, lrc.get(i).text);
            }
        }
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

    public static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
