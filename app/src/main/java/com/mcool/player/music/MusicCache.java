package com.mcool.player.music;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheEvictor;
import com.google.android.exoplayer2.upstream.cache.CacheSpan;
import com.google.android.exoplayer2.upstream.cache.CacheWriter;
import com.google.android.exoplayer2.upstream.cache.ContentMetadata;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.mcool.player.MusicBean;
import com.mcool.player.home.HomePrefs;
import com.mcool.player.lx.LxEngine;
import com.mcool.player.lx.LxLog;
import com.mcool.player.lx.OnlineSettings;
import com.mcool.player.net.HttpUtil;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 边听边缓存（ExoPlayer SimpleCache）。
 * - 缓存键：平台_歌曲ID_音质（播放链接每次都变，不能用链接当键）
 * - 默认目录 /sdcard/CarMusic/cache，上限可设（默认 2GB），满了删最久没听的
 * - 离开一首没缓存完整的歌时，后台把它下载完整（CacheWriter），保证"听过就能离线播"
 * - 完整缓存的歌记在 files/cache_index.json，歌单页「已缓存」从这里列出
 */
public final class MusicCache {

    private static SimpleCache cache;
    private static Evictor evictor;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static final Object INDEX_LOCK = new Object();
    private static JSONObject index;

    private MusicCache() {}

    public static File dir(Context c) {
        File d = new File(Environment.getExternalStorageDirectory(), "CarMusic/cache");
        if (!d.exists()) d.mkdirs();
        if (d.isDirectory() && d.canWrite()) return d;
        File e = c.getExternalFilesDir("music_cache");
        if (e != null) {
            e.mkdirs();
            if (e.canWrite()) return e;
        }
        File f = new File(c.getFilesDir(), "music_cache");
        f.mkdirs();
        return f;
    }

    private static long maxBytes(Context c) {
        int mb = HomePrefs.cacheMaxMb(c);
        return mb <= 0 ? Long.MAX_VALUE : mb * 1024L * 1024L;
    }

    public static synchronized SimpleCache get(Context ctx) {
        if (cache == null) {
            Context c = ctx.getApplicationContext();
            evictor = new Evictor(maxBytes(c));
            File d = dir(c);
            cache = new SimpleCache(d, evictor, new StandaloneDatabaseProvider(c));
            int mb = HomePrefs.cacheMaxMb(c);
            LxLog.i("歌曲缓存：" + d + "，上限 " + (mb <= 0 ? "不限" : mb + "MB"));
        }
        return cache;
    }

    /** 修改上限后调用：立即按"最久没听的先删"清理到新上限以内 */
    public static void applyMaxBytes(Context ctx) {
        final SimpleCache sc = get(ctx);
        evictor.maxBytes = maxBytes(ctx.getApplicationContext());
        WRITER.execute(() -> {
            synchronized (sc) {
                evictor.trim(sc);
            }
        });
    }

    public static String key(MusicBean s, String q) {
        return s.source + "_" + s.songmid + "_" + q;
    }

    public static boolean isFullyCached(Context c, String key) {
        try {
            SimpleCache sc = get(c);
            long len = ContentMetadata.getContentLength(sc.getContentMetadata(key));
            return len > 0 && sc.isCached(key, 0, len);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 这首歌已完整缓存的音质（按优先音质顺序找），没有返回 null */
    public static String cachedQuality(Context c, MusicBean s) {
        if (s == null || !s.online) return null;
        List<String> order = LxEngine.orderFor(OnlineSettings.quality(c));
        order.add("flac24bit");
        for (String q : order) if (isFullyCached(c, key(s, q))) return q;
        return null;
    }

    public static CacheDataSource.Factory dataSourceFactory(Context c, DataSource.Factory upstream) {
        return new CacheDataSource.Factory()
                .setCache(get(c))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /** 后台补全缓存（离开一首歌时调用）；已完整则只记录 */
    public static void complete(Context ctx, final String url, final String key, final MusicBean s, final String q) {
        final Context c = ctx.getApplicationContext();
        WRITER.execute(() -> {
            try {
                if (isFullyCached(c, key)) {
                    mark(c, s, q);
                    return;
                }
                if (url == null || !HttpUtil.isOnline(c)) return;
                DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                        .setUserAgent(HttpUtil.UA)
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(20000);
                CacheDataSource ds = dataSourceFactory(c, http).createDataSource();
                DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).build();
                new CacheWriter(ds, spec, null, null).cache();
                if (isFullyCached(c, key)) {
                    mark(c, s, q);
                    LxLog.i("后台补全缓存：" + s.title + " " + q);
                }
            } catch (Throwable e) {
                LxLog.w("后台补全缓存失败 " + s.title + "：" + e);
            }
        });
    }

    // ================================================================= 已缓存列表

    private static File indexFile(Context c) { return new File(c.getFilesDir(), "cache_index.json"); }

    private static JSONObject index(Context c) {
        if (index == null) {
            try {
                index = new JSONObject(new String(HttpUtil.readAll(new FileInputStream(indexFile(c))), "UTF-8"));
            } catch (Exception e) {
                index = new JSONObject();
            }
        }
        return index;
    }

    private static void saveIndex(Context c) {
        try {
            FileOutputStream fo = new FileOutputStream(indexFile(c));
            fo.write(index(c).toString().getBytes("UTF-8"));
            fo.close();
        } catch (Exception e) {
            LxLog.w("保存缓存索引失败：" + e);
        }
    }

    static void mark(Context c, MusicBean s, String q) {
        synchronized (INDEX_LOCK) {
            try {
                JSONObject e = new JSONObject();
                e.put("song", PlaylistStore.toJson(s));
                e.put("q", q);
                e.put("t", System.currentTimeMillis());
                index(c).put(s.source + "_" + s.songmid, e);
                saveIndex(c);
            } catch (Exception ignored) {
            }
        }
    }

    /** 完整缓存的歌，最近缓存的在前；已被清理掉的顺便从索引删除 */
    public static List<MusicBean> cachedSongs(Context ctx) {
        Context c = ctx.getApplicationContext();
        final List<Object[]> rows = new ArrayList<>();
        boolean dirty = false;
        synchronized (INDEX_LOCK) {
            JSONObject idx = index(c);
            Iterator<String> it = idx.keys();
            List<String> dead = new ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject e = idx.optJSONObject(k);
                MusicBean s = e == null ? null : PlaylistStore.fromJson(e.optJSONObject("song") == null ? new JSONObject() : e.optJSONObject("song"));
                if (s == null || cachedQuality(c, s) == null) {
                    dead.add(k);
                    continue;
                }
                rows.add(new Object[]{e.optLong("t"), s});
            }
            for (String k : dead) idx.remove(k);
            dirty = !dead.isEmpty();
            if (dirty) saveIndex(c);
        }
        Collections.sort(rows, (a, b) -> Long.compare((Long) b[0], (Long) a[0]));
        List<MusicBean> out = new ArrayList<>();
        for (Object[] r : rows) out.add((MusicBean) r[1]);
        return out;
    }

    public static long usedBytes(Context c) {
        try {
            return get(c).getCacheSpace();
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 清空缓存；keepKey 是正在播放的那首（不删，避免播放出错） */
    public static void clear(Context ctx, final String keepKey, final Runnable done) {
        final Context c = ctx.getApplicationContext();
        WRITER.execute(() -> {
            SimpleCache sc = get(c);
            for (String k : new HashSet<>(sc.getKeys())) {
                if (k.equals(keepKey)) continue;
                try {
                    sc.removeResource(k);
                } catch (Throwable ignored) {
                }
            }
            synchronized (INDEX_LOCK) {
                index = new JSONObject();
                saveIndex(c);
            }
            LxLog.i("歌曲缓存已清空");
            if (done != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
        });
    }

    // ================================================================= LRU 清理（上限可在运行时修改）

    static final class Evictor implements CacheEvictor {
        volatile long maxBytes;
        private final TreeSet<CacheSpan> spans = new TreeSet<>(Evictor::cmp);
        private long current;

        Evictor(long maxBytes) { this.maxBytes = maxBytes; }

        private static int cmp(CacheSpan a, CacheSpan b) {
            if (a.lastTouchTimestamp != b.lastTouchTimestamp) return a.lastTouchTimestamp < b.lastTouchTimestamp ? -1 : 1;
            return a.compareTo(b);
        }

        @Override public boolean requiresCacheSpanTouches() { return true; }

        @Override public void onCacheInitialized() {}

        @Override
        public void onStartFile(Cache cache, String key, long position, long length) {
            if (length != C.LENGTH_UNSET) evict(cache, length);
        }

        @Override
        public void onSpanAdded(Cache cache, CacheSpan span) {
            spans.add(span);
            current += span.length;
            evict(cache, 0);
        }

        @Override
        public void onSpanRemoved(Cache cache, CacheSpan span) {
            if (spans.remove(span)) current -= span.length;
        }

        @Override
        public void onSpanTouched(Cache cache, CacheSpan oldSpan, CacheSpan newSpan) {
            onSpanRemoved(cache, oldSpan);
            onSpanAdded(cache, newSpan);
        }

        void trim(Cache cache) { evict(cache, 0); }

        private void evict(Cache cache, long required) {
            while (current + required > maxBytes && !spans.isEmpty()) {
                CacheSpan first = spans.first();
                try {
                    cache.removeSpan(first);
                } catch (Throwable ignored) {
                }
                if (spans.remove(first)) current -= first.length; // 回调没触发时也要移出，避免死循环
            }
        }
    }
}
