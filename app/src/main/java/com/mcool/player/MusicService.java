package com.mcool.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.mcool.player.lx.LxEngine;
import com.mcool.player.lx.LxLog;
import com.mcool.player.lx.OnlineSettings;
import com.mcool.player.lx.SourceManager;
import com.mcool.player.music.MusicActivity;
import com.mcool.player.music.ChartRepo;
import com.mcool.player.music.MusicCache;
import com.mcool.player.music.PlaylistStore;
import com.mcool.player.net.HttpUtil;
import com.mcool.player.online.KuwoApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * 播放服务：播放队列的唯一持有者。
 * - 本地歌曲：系统 MediaPlayer（player-final 原逻辑）；在线歌曲：洛雪音源取链 → ExoPlayer
 * - 4 种播放模式；队列/序号/进度持久化，重启后恢复（不自动出声，按播放键从上次位置继续）
 * - 方控三层兜底 + 日志；音频焦点（导航播报降音量）
 */
public class MusicService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    private static final String KEY_TAG = "方控";

    // 播放模式（数值与旧版兼容：0/1/2 不变）
    public static final int MODE_LOOP = 0;    // 列表循环
    public static final int MODE_SINGLE = 1;  // 单曲循环
    public static final int MODE_RANDOM = 2;  // 随机
    public static final int MODE_ORDER = 3;   // 顺序播放（播完停止）
    /** 界面上点"播放模式"按钮时的切换顺序 */
    public static final int[] MODE_CYCLE = {MODE_LOOP, MODE_ORDER, MODE_RANDOM, MODE_SINGLE};

    /** 按钮上用的短名字 */
    public static String modeShort(int m) {
        switch (m) {
            case MODE_SINGLE: return "单曲";
            case MODE_RANDOM: return "随机";
            case MODE_ORDER: return "顺序";
            default: return "循环";
        }
    }

    public static String modeName(int m) {
        switch (m) {
            case MODE_SINGLE: return "单曲循环";
            case MODE_RANDOM: return "随机播放";
            case MODE_ORDER: return "顺序播放";
            default: return "列表循环";
        }
    }

    public static final String ACTION_UPDATE_UI = "com.mcool.player.UPDATE_UI";

    private MediaPlayer player;
    private ExoPlayer exo;
    private boolean usingExo;
    private boolean preparing;
    private int playGen;
    private int consecutiveErrors;
    private String statusText = "";
    private String currentQuality = "";
    private long pendingSeekMs;
    /** 当前在线歌曲的播放链接和缓存键（离开这首歌时用来后台补全缓存） */
    private String currentUrl, currentKey;
    /** 正在播放的缓存键（在线或离线缓存播放都有），清空缓存时跳过它 */
    private String playingKey;
    private String queueName = "";

    private List<MusicBean> playlist = new ArrayList<>();
    private int currentPosition = -1;
    private int playMode = MODE_LOOP;
    private final LinkedList<Integer> history = new LinkedList<>();
    private final Random rnd = new Random();

    private final IBinder musicBind = new MusicBinder();
    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private boolean hasFocus, resumeOnFocusGain, ducked;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 播放中每 15 秒保存一次进度（重启车机后从这里继续） */
    private final Runnable savePosTask = new Runnable() {
        @Override
        public void run() {
            savePos();
            if (isPng()) mainHandler.postDelayed(this, 15000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createdAt = System.currentTimeMillis();
        player = new MediaPlayer();
        initPlayer();
        initMediaSession();
        registerMediaButtonReceiver();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
        wakeLock.setReferenceCounted(false);
        restoreQueue();
    }

    // ================================================================= 方控（三层，原 player-final 逻辑 + 日志）

    private void registerMediaButtonReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_BUTTON);
            filter.addAction("com.android.music.musicservicecommand");
            filter.addAction("android.media.AUDIO_BECOMING_NOISY");
            filter.addAction("com.haoke.media.MUSIC_CONTROL");
            filter.addAction("com.bdstar.media.MUSIC_CONTROL");
            filter.addAction("android.intent.action.MEDIA_PLAY");
            filter.addAction("android.intent.action.MEDIA_PAUSE");
            filter.addAction("android.intent.action.MEDIA_NEXT");
            filter.addAction("android.intent.action.MEDIA_PREVIOUS");
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            registerReceiver(mediaButtonReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String dumpIntent(Intent i) {
        StringBuilder sb = new StringBuilder(String.valueOf(i.getAction()));
        Bundle b = i.getExtras();
        if (b != null) for (String k : b.keySet()) sb.append(' ').append(k).append('=').append(b.get(k));
        return sb.toString();
    }

    // ================================================================= 启动后不自动播放

    /** 启动保护时长：这段时间内，系统/车机发来的"开始播放"命令一律忽略（车机开机常会自动发 PLAY） */
    private static final long AUTO_PLAY_GUARD_MS = 30000;
    private long createdAt;
    /** 用户在界面上（组件 / 全屏 / 歌单）主动开始过播放 */
    private boolean userStarted;

    /** 界面上的播放操作调用：解除启动保护 */
    public void markUserAction() { userStarted = true; }

    /** 界面调用写法：svc.user().resumePlayer() —— 标记为用户手动操作后返回自己 */
    public MusicService user() {
        userStarted = true;
        return this;
    }

    /**
     * 系统/车机/方控发来的命令（广播、MediaSession）：会让音乐开始响的命令，在启动保护期内忽略。
     * 暂停、停止任何时候都执行；已经在播放时，切歌照常执行。
     */
    private void remote(String src, String cmd, Runnable r) {
        boolean starts = !isPng();
        if (starts && !userStarted && System.currentTimeMillis() - createdAt < AUTO_PLAY_GUARD_MS) {
            LxLog.i("启动保护：忽略 " + src + " 发来的「" + cmd + "」（启动 30 秒内只能在界面上手动开始播放）");
            return;
        }
        userStarted = true;
        r.run();
    }

    /** 前台页面收到的媒体键（方控在前台时也走这里，同样受启动保护） */
    public void onMediaKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY: remote("按键", "播放", this::resumePlayer); break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: pausePlayer(); break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                remoteToggle("按键");
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT: remote("按键", "下一首", this::playNext); break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: remote("按键", "上一首", this::playPrev); break;
        }
    }

    private void remoteToggle(String src) {
        if (isPng()) pausePlayer();
        else remote(src, "播放", this::resumePlayer);
    }

    /** 厂商广播的命令字段名不确定，几个常见字段都试一下 */
    private void handleCommand(String cmd) {
        if (cmd == null) return;
        switch (cmd.toLowerCase()) {
            case "next": remote("广播", "下一首", this::playNext); break;
            case "previous": case "prev": remote("广播", "上一首", this::playPrev); break;
            case "play": remote("广播", "播放", this::resumePlayer); break;
            case "pause": case "stop": pausePlayer(); break;
            case "togglepause": case "toggle": case "playpause":
                remoteToggle("广播");
                break;
        }
    }

    private final BroadcastReceiver mediaButtonReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            LxLog.i(KEY_TAG + "[广播] " + dumpIntent(intent));

            if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleKeyEvent(event.getKeyCode());
                    abortBroadcastSafe();
                }
                return;
            }
            if ("android.media.AUDIO_BECOMING_NOISY".equals(action)) {
                pausePlayer();
                return;
            }
            if ("android.intent.action.MEDIA_NEXT".equals(action)) { remote("广播", "下一首", MusicService.this::playNext); abortBroadcastSafe(); return; }
            if ("android.intent.action.MEDIA_PREVIOUS".equals(action)) { remote("广播", "上一首", MusicService.this::playPrev); abortBroadcastSafe(); return; }
            if ("android.intent.action.MEDIA_PLAY".equals(action)) { remote("广播", "播放", MusicService.this::resumePlayer); abortBroadcastSafe(); return; }
            if ("android.intent.action.MEDIA_PAUSE".equals(action)) { pausePlayer(); abortBroadcastSafe(); return; }

            if ("com.android.music.musicservicecommand".equals(action)
                    || "com.haoke.media.MUSIC_CONTROL".equals(action)
                    || "com.bdstar.media.MUSIC_CONTROL".equals(action)) {
                String cmd = intent.getStringExtra("command");
                if (cmd == null) cmd = intent.getStringExtra("cmd");
                if (cmd == null) cmd = intent.getStringExtra("action");
                handleCommand(cmd);
                abortBroadcastSafe();
            }
        }

        private void abortBroadcastSafe() {
            try { if (isOrderedBroadcast()) abortBroadcast(); } catch (Exception ignored) {}
        }

        private void handleKeyEvent(int keyCode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY: remote("媒体键", "播放", MusicService.this::resumePlayer); break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: pausePlayer(); break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    remoteToggle("媒体键");
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT: remote("媒体键", "下一首", MusicService.this::playNext); break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: remote("媒体键", "上一首", MusicService.this::playPrev); break;
            }
        }
    };

    private void initPlayer() {
        player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
    }

    private void initMediaSession() {
        try {
            ComponentName mediaButtonReceiver = null;  // Android 4.4 兼容
            mediaSession = new MediaSessionCompat(this, "MusicService", mediaButtonReceiver, null);
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay() { LxLog.i(KEY_TAG + "[MediaSession] play"); remote("MediaSession", "播放", MusicService.this::resumePlayer); }
                @Override public void onPause() { LxLog.i(KEY_TAG + "[MediaSession] pause"); pausePlayer(); }
                @Override public void onSkipToNext() { LxLog.i(KEY_TAG + "[MediaSession] next"); remote("MediaSession", "下一首", MusicService.this::playNext); }
                @Override public void onSkipToPrevious() { LxLog.i(KEY_TAG + "[MediaSession] previous"); remote("MediaSession", "上一首", MusicService.this::playPrev); }
                @Override public void onStop() { LxLog.i(KEY_TAG + "[MediaSession] stop"); pausePlayer(); }
            });
            mediaSession.setActive(true);
        } catch (Exception e) {
            e.printStackTrace();
            mediaSession = null;
        }
    }

    // ================================================================= 队列

    public class MusicBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return musicBind; }

    @Override
    public boolean onUnbind(Intent intent) { return false; }

    /**
     * 启动时按「默认播放顺序」（歌单页可配置）依次尝试，第一个有歌的生效；只放进队列，不自动出声。
     * 默认顺序：上次的播放队列 → 我喜欢 → 酷我热歌榜。
     */
    private void restoreQueue() {
        PlaylistStore.QueueState q = PlaylistStore.loadQueue(this);
        playMode = q.mode;
        tryStartup(PlaylistStore.startupOrder(this), 0, q);
    }

    private void tryStartup(final List<String> order, final int i, final PlaylistStore.QueueState q) {
        if (i >= order.size()) {
            LxLog.i("播放列表顺序里的来源都没有歌，播放队列保持为空");
            return;
        }
        final String id = order.get(i);
        if (PlaylistStore.START_LAST.equals(id)) {
            if (!q.songs.isEmpty()) {
                playlist = q.songs;
                currentPosition = Math.max(0, q.index);
                pendingSeekMs = q.posMs;
                queueName = PlaylistStore.queueName(this);
                setStatus("");
                LxLog.i("启动：恢复上次播放队列 " + playlist.size() + " 首，第 " + (currentPosition + 1) + " 首，进度 " + (q.posMs / 1000) + "s");
                updateMetadata(getCurrentSong());
                sendUpdateUIBroadcast();
                return;
            }
            tryStartup(order, i + 1, q);
            return;
        }
        resolveList(id, songs -> {
            if (!playlist.isEmpty()) return;
            if (songs != null && !songs.isEmpty()) {
                int start = playMode == MODE_RANDOM && songs.size() > 1 ? rnd.nextInt(songs.size()) : 0;
                String name = PlaylistStore.listName(this, id);
                setQueue(songs, start, false, name);
                int ci = PlaylistStore.cycleOrder(this).indexOf(id);
                if (ci >= 0) PlaylistStore.setCycleIndex(this, ci);
                LxLog.i("启动：使用「" + name + "」作为播放队列，" + songs.size() + " 首");
            } else {
                tryStartup(order, i + 1, q);
            }
        });
    }

    private interface SongsCb { void done(List<MusicBean> songs); }

    /** 读取一个列表（我的收藏 / 榜单 / 已缓存 / 歌单），回调在主线程，失败或为空时 songs 为 null 或空 */
    private void resolveList(final String id, final SongsCb cb) {
        HttpUtil.POOL.execute(() -> {
            List<MusicBean> songs = null;
            try {
                if (PlaylistStore.START_FAV.equals(id)) {
                    PlaylistStore.Meta m = PlaylistStore.findByName(this, PlaylistStore.FAV_NAME);
                    if (m != null) songs = PlaylistStore.load(this, m.id).songs;
                    else LxLog.w("没有找到名为「" + PlaylistStore.FAV_NAME + "」的歌单");
                } else if (ChartRepo.isChart(id)) {
                    songs = ChartRepo.load(this, id, false);
                } else if (PlaylistStore.CACHED.equals(id)) {
                    songs = MusicCache.cachedSongs(this);
                } else {
                    songs = PlaylistStore.load(this, id).songs;
                }
            } catch (Throwable e) {
                LxLog.w("载入列表失败 " + id + "：" + e);
            }
            final List<MusicBean> fs = songs;
            mainHandler.post(() -> cb.done(fs));
        });
    }

    public interface ListCb { void done(String name, int count, String error); }

    /**
     * 播放页「切换歌单」：按播放列表顺序（去掉"上次的播放队列"）切到下一个有歌的列表，
     * 用它替换播放队列，从第一首开始按顺序播放（列表循环）。
     */
    public void cycleList(final ListCb cb) {
        markUserAction();
        final List<String> order = PlaylistStore.cycleOrder(this);
        if (order.isEmpty()) {
            cb.done(null, 0, "播放列表顺序是空的，请到 歌单 → 列表顺序 里添加");
            return;
        }
        cycleTry(order, PlaylistStore.cycleIndex(this), 1, cb);
    }

    private void cycleTry(final List<String> order, final int cur, final int step, final ListCb cb) {
        if (step > order.size()) {
            cb.done(null, 0, "列表都没有歌（网络不通，或者没有名为「" + PlaylistStore.FAV_NAME + "」的歌单）");
            return;
        }
        final int n = order.size();
        final int idx = ((cur + step) % n + n) % n;
        final String id = order.get(idx);
        resolveList(id, songs -> {
            if (songs == null || songs.isEmpty()) {
                cycleTry(order, cur, step + 1, cb);
                return;
            }
            String name = PlaylistStore.listName(this, id);
            PlaylistStore.setCycleIndex(this, idx);
            playMode = MODE_LOOP; // 切换歌单后按顺序播放
            setQueue(songs, 0, true, name);
            LxLog.i("切换歌单：" + name + "，" + songs.size() + " 首");
            cb.done(name, songs.size(), null);
        });
    }

    public String getQueueName() { return queueName; }

    public String getPlayingCacheKey() { return playingKey; }

    /** 用新列表替换播放队列 */
    public void setQueue(List<MusicBean> songs, int index, boolean play) {
        setQueue(songs, index, play, null);
    }

    /** 用新列表替换播放队列；name 为列表名（显示用，null = 不改） */
    public void setQueue(List<MusicBean> songs, int index, boolean play, String name) {
        if (name != null) {
            queueName = name;
            PlaylistStore.saveQueueName(this, name);
        }
        playlist = new ArrayList<>(songs);
        history.clear();
        currentPosition = playlist.isEmpty() ? -1 : Math.max(0, Math.min(index, playlist.size() - 1));
        pendingSeekMs = 0;
        PlaylistStore.saveQueueSongs(this, playlist);
        savePos();
        if (play && currentPosition >= 0) playSong(currentPosition);
        else {
            updateMetadata(getCurrentSong());
            sendUpdateUIBroadcast();
        }
    }

    /** 搜索结果等单首歌：插到当前歌后面并马上播放，不清空队列 */
    public void insertNextAndPlay(MusicBean s) {
        if (playlist.isEmpty()) {
            setQueue(java.util.Collections.singletonList(s), 0, true);
            return;
        }
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).path.equals(s.path)) { playSong(i); return; }
        }
        int at = Math.min(currentPosition + 1, playlist.size());
        playlist.add(at, s);
        PlaylistStore.saveQueueSongs(this, playlist);
        playSong(at);
    }

    public void removeFromQueue(int i) {
        if (i < 0 || i >= playlist.size()) return;
        boolean wasCurrent = i == currentPosition;
        playlist.remove(i);
        if (i < currentPosition) currentPosition--;
        if (playlist.isEmpty()) {
            stopEngines();
            currentPosition = -1;
        } else if (wasCurrent) {
            currentPosition = Math.min(currentPosition, playlist.size() - 1);
            if (isPng()) playSong(currentPosition);
        }
        history.clear();
        PlaylistStore.saveQueueSongs(this, playlist);
        savePos();
        sendUpdateUIBroadcast();
    }

    public List<MusicBean> getQueue() { return playlist; }

    public String getStatusText() { return statusText; }

    public String getCurrentQuality() { return currentQuality; }

    private void setStatus(String s) { statusText = s == null ? "" : s; }

    private void savePos() {
        PlaylistStore.saveQueuePos(this, currentPosition, getPosn(), playMode);
    }

    // ================================================================= 播放

    private void stopEngines() {
        preparing = false;
        try { player.reset(); } catch (Exception ignored) {}
        if (exo != null) {
            try { exo.stop(); exo.clearMediaItems(); } catch (Exception ignored) {}
        }
    }

    public void playSong(int songIndex) {
        try {
            if (playlist == null || playlist.isEmpty()) {
                LxLog.w("播放列表为空");
                return;
            }
            if (songIndex < 0 || songIndex >= playlist.size()) return;
            final int gen = ++playGen;
            finishCaching();
            playingKey = null;
            stopEngines();
            pendingSeekMs = 0; // 恢复进度只在 resumePlayer 里重新设置
            if (currentPosition >= 0 && currentPosition != songIndex) {
                history.add(currentPosition);
                while (history.size() > 50) history.removeFirst();
            }
            currentPosition = songIndex;
            currentQuality = "";
            MusicBean song = playlist.get(currentPosition);
            requestFocus();
            updateMetadata(song);
            savePos();

            if (song.online) {
                usingExo = true;
                String cq = MusicCache.cachedQuality(this, song);
                if (cq != null) {
                    // 已完整缓存：直接从硬盘播，不联网、不调用音源
                    currentQuality = cq;
                    LxLog.i("播放缓存：" + song.title + " " + cq);
                    startExoCached(MusicCache.key(song, cq));
                } else if (!HttpUtil.isOnline(this)) {
                    skipOffline(gen, songIndex);
                } else {
                    setStatus("正在获取播放链接…");
                    sendUpdateUIBroadcast();
                    tryQuality(song, gen, null, 0, "");
                }
            } else {
                usingExo = false;
                releaseExo(); // 本地歌曲用不到 ExoPlayer，释放它的缓冲
                setStatus("");
                try {
                    player.setDataSource(song.path);
                    preparing = true;
                    player.prepareAsync();
                } catch (Exception e) {
                    LxLog.e("本地歌曲打开失败 " + song.path, e);
                }
                sendUpdateUIBroadcast();
            }
        } catch (Exception e) {
            LxLog.e("播放出错", e);
        }
    }

    // ---------------- 在线：取链 + ExoPlayer ----------------

    private void tryQuality(final MusicBean song, final int gen, List<String> chain, final int idx, final String lastErr) {
        if (gen != playGen) return;
        SourceManager.get(this).ensureActiveLoaded();
        final LxEngine eng = LxEngine.get(this);
        if (chain == null) {
            if (!eng.isReady()) {
                setStatus("音源初始化中…");
                sendUpdateUIBroadcast();
                waitEngineThen(gen, () -> tryQuality(song, gen,
                        eng.qualityChain(song.source, OnlineSettings.quality(this), song.types), 0, ""));
                return;
            }
            chain = eng.qualityChain(song.source, OnlineSettings.quality(this), song.types);
        }
        if (idx >= chain.size()) {
            onOnlineFailed(gen, "取链失败：" + lastErr);
            return;
        }
        final List<String> fChain = chain;
        final String q = chain.get(idx);
        JSONObject info = new JSONObject();
        try {
            info.put("type", q);
            info.put("musicInfo", new JSONObject(song.musicInfoJson));
        } catch (Exception e) {
            onOnlineFailed(gen, "歌曲信息错误");
            return;
        }
        setStatus("获取链接中（" + q + "）…");
        sendUpdateUIBroadcast();
        eng.request(song.source, "musicUrl", info, (ok, data, msg) -> {
            if (gen != playGen) return;
            String url = ok && data != null ? data.optString("url", "") : "";
            if (url.isEmpty()) {
                LxLog.w("取链失败 " + song.title + " " + q + "：" + msg);
                tryQuality(song, gen, fChain, idx + 1, msg);
                return;
            }
            LxLog.i("取链成功 " + song.title + " " + q + " → " + url);
            currentQuality = q;
            currentUrl = url;
            currentKey = MusicCache.key(song, q);
            startExo(url, currentKey);
        });
    }

    /** 引擎就绪（或失败）后执行 */
    private void waitEngineThen(final int gen, final Runnable r) {
        final LxEngine eng = LxEngine.get(this);
        final LxEngine.StateListener[] holder = new LxEngine.StateListener[1];
        holder[0] = () -> {
            int st = eng.getState();
            if (st == LxEngine.ST_LOADING) return;
            eng.removeListener(holder[0]);
            if (gen != playGen) return;
            if (st == LxEngine.ST_READY) r.run();
            else onOnlineFailed(gen, eng.getStateMsg());
        };
        eng.addListener(holder[0]);
        mainHandler.post(holder[0]::onStateChanged);
        mainHandler.postDelayed(() -> {
            if (gen == playGen && !eng.isReady()) {
                eng.removeListener(holder[0]);
                onOnlineFailed(gen, eng.getState() == LxEngine.ST_IDLE ? "没有可用音源，请先在「音乐源」页面导入" : eng.getStateMsg());
            }
        }, 30000);
    }

    private void ensureExo() {
        if (exo != null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(HttpUtil.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        // 默认缓冲约 13MB，在 Java 堆上；在线听歌 3MB / 30 秒足够
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 1500, 3000)
                .setTargetBufferBytes(3 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        exo = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(MusicCache.dataSourceFactory(this, new DefaultDataSource.Factory(this, http))))
                .setLoadControl(load)
                .build();
        exo.setWakeMode(C.WAKE_MODE_NETWORK);
        exo.addListener(exoListener);
    }

    private void releaseExo() {
        if (exo == null) return;
        try {
            exo.removeListener(exoListener);
            exo.release();
        } catch (Exception ignored) {
        }
        exo = null;
    }

    /** 离开一首在线歌时：没缓存完整就在后台补全（链接几分钟内仍有效） */
    private void finishCaching() {
        MusicBean s = getCurrentSong();
        if (usingExo && currentKey != null && s != null && s.online) {
            MusicCache.complete(this, currentUrl, currentKey, s, currentQuality);
        }
        currentUrl = null;
        currentKey = null;
    }

    /** 播放已完整缓存的歌：地址随便填，ExoPlayer 按缓存键从硬盘读 */
    private void startExoCached(String key) {
        ensureExo();
        usingExo = true;
        preparing = true;
        playingKey = key;
        exo.setMediaItem(new MediaItem.Builder().setUri("http://offline.cache/" + key).setCustomCacheKey(key).build());
        exo.prepare();
        exo.setPlayWhenReady(true);
        applyVolume();
        setStatus("读取缓存…");
        sendUpdateUIBroadcast();
    }

    /** 离线且这首没缓存：往后找第一首本地或已缓存的歌 */
    private void skipOffline(final int gen, int from) {
        int n = playlist.size();
        for (int k = 1; k < n; k++) {
            final int i = (from + k) % n;
            MusicBean s = playlist.get(i);
            if (!s.online || MusicCache.cachedQuality(this, s) != null) {
                LxLog.i("离线：跳过未缓存的歌 → 第 " + (i + 1) + " 首");
                mainHandler.post(() -> { if (gen == playGen) playSong(i); });
                return;
            }
        }
        preparing = false;
        setStatus("离线：这个列表没有已缓存的歌，可到 歌单 → 已缓存 选歌");
        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED);
        sendUpdateUIBroadcast();
    }

    /** 队列里除当前这首外，是否还有本地或已缓存的歌 */
    private boolean hasPlayableOffline() {
        for (int i = 0; i < playlist.size(); i++) {
            if (i == currentPosition) continue;
            MusicBean s = playlist.get(i);
            if (!s.online || MusicCache.cachedQuality(this, s) != null) return true;
        }
        return false;
    }

    private void startExo(String url, String key) {
        ensureExo();
        usingExo = true;
        preparing = true;
        playingKey = key;
        exo.setMediaItem(new MediaItem.Builder().setUri(url).setCustomCacheKey(key).build());
        exo.prepare();
        exo.setPlayWhenReady(true);
        applyVolume();
        setStatus("缓冲中…");
        sendUpdateUIBroadcast();
    }

    private final Player.Listener exoListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (!usingExo) return;
            if (state == Player.STATE_READY && preparing) {
                preparing = false;
                consecutiveErrors = 0;
                setStatus("");
                applyPendingSeek();
                onStartedPlaying();
            } else if (state == Player.STATE_ENDED) {
                handleCompletion();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (!usingExo) return;
            updateMediaSessionState(isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED);
            sendUpdateUIBroadcast();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (!usingExo) return;
            LxLog.e("在线播放出错 " + error.getErrorCodeName(), error);
            onOnlineFailed(playGen, "播放出错 " + error.getErrorCodeName());
        }
    };

    private void onOnlineFailed(final int gen, String msg) {
        if (gen != playGen) return;
        preparing = false;
        consecutiveErrors++;
        MusicBean s = getCurrentSong();
        LxLog.w("在线歌曲无法播放 " + (s == null ? "" : s.title) + "：" + msg);
        if (consecutiveErrors >= 2 && playlist.size() > 1 && hasPlayableOffline()) {
            // 连续失败（多半是网络不通，比如连着没外网的热点）：改播已缓存的歌
            setStatus("连续失败，改播已缓存的歌（" + msg + "）");
            sendUpdateUIBroadcast();
            final int from = currentPosition;
            mainHandler.postDelayed(() -> { if (gen == playGen) skipOffline(gen, from); }, 1500);
        } else if (consecutiveErrors < 3 && playlist.size() > 1) {
            setStatus("无法播放，2 秒后跳到下一首（" + msg + "）");
            sendUpdateUIBroadcast();
            mainHandler.postDelayed(() -> { if (gen == playGen) playNext(); }, 2000);
        } else {
            setStatus("连续失败已停止：" + msg);
            updateMediaSessionState(PlaybackStateCompat.STATE_ERROR);
            sendUpdateUIBroadcast();
        }
    }

    // ---------------- 公共控制 ----------------

    @Override
    public void onPrepared(MediaPlayer mp) {
        try {
            if (usingExo) return;
            preparing = false;
            consecutiveErrors = 0;
            applyVolume();
            applyPendingSeek();
            mp.start();
            onStartedPlaying();
        } catch (Exception e) {
            LxLog.e("开始播放失败", e);
            updateMediaSessionState(PlaybackStateCompat.STATE_ERROR);
        }
    }

    private void applyPendingSeek() {
        if (pendingSeekMs <= 0) return;
        long p = pendingSeekMs;
        pendingSeekMs = 0;
        seek((int) p);
    }

    private void onStartedPlaying() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        updateNotification();
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
        mainHandler.removeCallbacks(savePosTask);
        mainHandler.postDelayed(savePosTask, 15000);
        sendUpdateUIBroadcast();
    }

    public void pausePlayer() {
        resumeOnFocusGain = false;
        doPause();
    }

    private void doPause() {
        if (usingExo) {
            if (exo != null) exo.setPlayWhenReady(false);
        } else {
            try { if (player.isPlaying()) player.pause(); } catch (IllegalStateException ignored) {}
        }
        if (wakeLock.isHeld()) wakeLock.release();
        mainHandler.removeCallbacks(savePosTask);
        savePos();
        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED);
        stopForeground(false);
        sendUpdateUIBroadcast();
    }

    /** 停止播放（桌面设置里用；桌面本身不退出） */
    public void stopPlayback() {
        finishCaching();
        pausePlayer();
        stopEngines();
        releaseExo();
        stopForeground(true);
        if (audioManager != null) audioManager.abandonAudioFocus(this);
        hasFocus = false;
        setStatus("");
        sendUpdateUIBroadcast();
    }

    public void resumePlayer() {
        if (getCurrentSong() == null) return;
        requestFocus();
        if (usingExo) {
            if (exo == null || exo.getPlaybackState() == Player.STATE_IDLE) {
                long keep = pendingSeekMs > 0 ? pendingSeekMs : getPosn();
                playSong(currentPosition);
                pendingSeekMs = keep;
                return;
            }
            exo.setPlayWhenReady(true);
        } else {
            try {
                if (preparing) return;
                if (!player.isPlaying()) {
                    if (getDur() <= 0) throw new IllegalStateException("not prepared");
                    player.start();
                }
            } catch (IllegalStateException e) {
                // 刚恢复队列还没加载过：从保存的进度开始播
                long keep = pendingSeekMs;
                playSong(currentPosition);
                pendingSeekMs = keep;
                return;
            }
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
        updateNotification();
        mainHandler.removeCallbacks(savePosTask);
        mainHandler.postDelayed(savePosTask, 15000);
        sendUpdateUIBroadcast();
    }

    public void playNext() {
        if (playlist == null || playlist.isEmpty()) return;
        int n;
        if (playMode == MODE_RANDOM && playlist.size() > 1) {
            do { n = rnd.nextInt(playlist.size()); } while (n == currentPosition);
        } else {
            n = currentPosition + 1;
            if (n >= playlist.size()) n = 0;
        }
        playSong(n);
    }

    public void playPrev() {
        if (playlist == null || playlist.isEmpty()) return;
        if (playMode == MODE_RANDOM && !history.isEmpty()) {
            int p = history.removeLast();
            if (p >= 0 && p < playlist.size()) {
                currentPosition = -1; // 避免把当前歌又压回历史
                playSong(p);
                return;
            }
        }
        int p = currentPosition - 1;
        if (p < 0) p = playlist.size() - 1;
        playSong(p);
    }

    public void setPlayMode(int mode) {
        playMode = mode;
        savePos();
        sendUpdateUIBroadcast();
    }

    public int getPlayMode() { return playMode; }

    /** 切到下一个播放模式，返回新模式 */
    public int cyclePlayMode() {
        int i = 0;
        for (int k = 0; k < MODE_CYCLE.length; k++) if (MODE_CYCLE[k] == playMode) i = k;
        setPlayMode(MODE_CYCLE[(i + 1) % MODE_CYCLE.length]);
        return playMode;
    }

    private void handleCompletion() {
        if (playMode == MODE_SINGLE) {
            playSong(currentPosition);
        } else if (playMode == MODE_ORDER && currentPosition >= playlist.size() - 1) {
            setStatus("播放完毕");
            doPause();
        } else {
            playNext();
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (usingExo) return;
        if (consecutiveErrors >= 3) return;
        handleCompletion();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        LxLog.w("本地播放出错 what=" + what + " extra=" + extra);
        consecutiveErrors++;
        preparing = false;
        mp.reset();
        // 返回 false 会触发 onCompletion → 跳下一首；连续 3 次失败则停止
        return consecutiveErrors >= 3;
    }

    public int getPosn() {
        try {
            if (usingExo) return exo == null ? (int) pendingSeekMs : (int) exo.getCurrentPosition();
            if (preparing) return (int) pendingSeekMs;
            return player.getCurrentPosition();
        } catch (Exception e) {
            return (int) pendingSeekMs;
        }
    }

    public int getDur() {
        try {
            if (usingExo) {
                if (exo == null) return 0;
                long d = exo.getDuration();
                return d == C.TIME_UNSET ? 0 : (int) d;
            }
            if (preparing) return 0;
            return player.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isPng() {
        try {
            if (usingExo) return exo != null && exo.isPlaying();
            return player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public MusicBean getCurrentSong() {
        if (playlist != null && currentPosition >= 0 && currentPosition < playlist.size()) {
            return playlist.get(currentPosition);
        }
        return null;
    }

    public int getCurrentPosition() { return currentPosition; }

    public void seek(int position) {
        try {
            if (usingExo) { if (exo != null) exo.seekTo(position); }
            else if (player != null) player.seekTo(position);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ================================================================= 音频焦点

    private void requestFocus() {
        if (hasFocus || audioManager == null) return;
        int r = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        LxLog.i("请求音频焦点：" + (hasFocus ? "成功" : "失败 " + r));
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        LxLog.i("音频焦点变化：" + focusName(focusChange));
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                ducked = false;
                applyVolume();
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    resumePlayer();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;   // 导航播报：降音量
                applyVolume();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPng()) {   // 电话/语音：暂停，结束后自动恢复
                    doPause();
                    resumeOnFocusGain = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                hasFocus = false;
                resumeOnFocusGain = false;
                if (isPng()) doPause();
                break;
        }
    }

    private static String focusName(int f) {
        switch (f) {
            case AudioManager.AUDIOFOCUS_GAIN: return "GAIN（恢复）";
            case AudioManager.AUDIOFOCUS_LOSS: return "LOSS（被其他应用长期占用）";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: return "LOSS_TRANSIENT（短暂打断，暂停）";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: return "CAN_DUCK（导航播报，降音量）";
            default: return String.valueOf(f);
        }
    }

    private void applyVolume() {
        float v = ducked ? 0.2f : 1.0f;
        try { player.setVolume(v, v); } catch (Exception ignored) {}
        if (exo != null) exo.setVolume(v);
    }

    // ================================================================= MediaSession / 通知

    private void updateMetadata(MusicBean s) {
        if (mediaSession == null || s == null) return;
        try {
            mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.duration)
                    .build());
        } catch (Exception ignored) {
        }
    }

    private void updateMediaSessionState(int state) {
        if (mediaSession == null) return;
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder();
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            b.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        } else {
            b.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        }
        b.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1);
        mediaSession.setPlaybackState(b.build());
    }

    private void updateNotification() {
        try {
            String channelId = "mcool_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationHelper.createChannel(this, channelId);
            }
            Intent notIntent = new Intent(this, MusicActivity.class);
            notIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pendInt = PendingIntent.getActivity(this, 0, notIntent, flags);

            MusicBean song = getCurrentSong();
            String title = (song != null) ? song.title : "音乐";
            String artist = (song != null) ? song.artist : "";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId);
            builder.setContentIntent(pendInt)
                    .setSmallIcon(R.drawable.logo)
                    .setTicker(title)
                    .setOngoing(true)
                    .setContentTitle(title)
                    .setContentText(artist);
            startForeground(1, builder.build());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    // Separate class to avoid verification errors on older Android versions
    private static class NotificationHelper {
        static void createChannel(Context context, String channelId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel chan = new NotificationChannel(channelId, "Music Playback", NotificationManager.IMPORTANCE_LOW);
                chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                NotificationManager service = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (service != null) service.createNotificationChannel(chan);
            }
        }
    }

    private void sendUpdateUIBroadcast() {
        sendBroadcast(new Intent(ACTION_UPDATE_UI).setPackage(getPackageName()));
    }

    @Override
    public void onDestroy() {
        savePos();
        mainHandler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(mediaButtonReceiver); } catch (Exception e) { e.printStackTrace(); }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (mediaSession != null) mediaSession.release();
        if (audioManager != null) audioManager.abandonAudioFocus(this);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
        }
        releaseExo();
        super.onDestroy();
    }
}
