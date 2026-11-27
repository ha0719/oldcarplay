package com.mcool.player.online;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.mcool.player.MusicBean;
import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 在线歌曲的歌词 / 封面：内存缓存 + 硬盘缓存（files/lrc、files/covers），听过一次以后离线也能显示。
 * 歌词来源：kw / tx / kg 各自的接口；封面：歌曲信息自带的 img 优先，其次酷我封面接口。回调在主线程。
 */
public final class OnlineRepo {

    public interface Cb<T> { void done(T value); }

    private static final LruCache<String, String> LRC = new LruCache<>(40);
    private static final LruCache<String, Bitmap> PIC = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) { return value.getRowBytes() * value.getHeight(); }
    };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static File lrcDir, coverDir;

    private OnlineRepo() {}

    /** App 启动时调用 */
    public static void init(Context c) {
        lrcDir = new File(c.getFilesDir(), "lrc");
        coverDir = new File(c.getFilesDir(), "covers");
        lrcDir.mkdirs();
        coverDir.mkdirs();
    }

    private static String fileKey(MusicBean s) {
        return (s.source + "_" + s.songmid).replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static byte[] readFile(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return HttpUtil.readAll(new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, byte[] data) {
        if (f == null || data == null) return;
        try {
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.close();
        } catch (Exception ignored) {
        }
    }

    private static String mi(MusicBean s, String field) {
        try {
            return new org.json.JSONObject(s.musicInfoJson).optString(field, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static void lyric(final MusicBean s, final Cb<String> cb) {
        String c = LRC.get(s.path);
        if (c != null) { cb.done(c.isEmpty() ? null : c); return; }
        HttpUtil.POOL.execute(() -> {
            String lrc = null;
            File f = lrcDir == null ? null : new File(lrcDir, fileKey(s) + ".lrc");
            byte[] disk = readFile(f);
            if (disk != null) {
                try { lrc = new String(disk, "UTF-8"); } catch (Exception ignored) {}
            }
            if (lrc == null && HttpUtil.isOnlineQuick()) {
                try {
                    if ("kw".equals(s.source)) lrc = KuwoApi.lyric(s.songmid);
                    else if ("tx".equals(s.source)) lrc = TxApi.lyric(s.songmid);
                    else if ("kg".equals(s.source)) lrc = KgApi.lyric(mi(s, "hash"));
                } catch (Throwable e) {
                    LxLog.w("获取歌词失败 " + s.title + "：" + e);
                }
                if (lrc != null) {
                    try { writeFile(f, lrc.getBytes("UTF-8")); } catch (Exception ignored) {}
                }
            }
            LRC.put(s.path, lrc == null ? "" : lrc);
            final String r = lrc;
            MAIN.post(() -> cb.done(r));
        });
    }

    public static void cover(final MusicBean s, final Cb<Bitmap> cb) {
        Bitmap c = PIC.get(s.path);
        if (c != null) { cb.done(c); return; }
        HttpUtil.POOL.execute(() -> {
            Bitmap bmp = null;
            File f = coverDir == null ? null : new File(coverDir, fileKey(s) + ".img");
            byte[] data = readFile(f);
            if (data == null && HttpUtil.isOnlineQuick()) {
                try {
                    String url = mi(s, "img");
                    if (!url.startsWith("http")) url = null;
                    if (url == null && "kw".equals(s.source)) url = KuwoApi.picUrl(s.songmid);
                    if (url != null) {
                        data = HttpUtil.getBytes(url);
                        writeFile(f, data);
                    }
                } catch (Throwable e) {
                    LxLog.w("获取封面失败 " + s.title + "：" + e);
                }
            }
            if (data != null) bmp = decode(data);
            if (bmp != null) PIC.put(s.path, bmp);
            final Bitmap r = bmp;
            MAIN.post(() -> cb.done(r));
        });
    }

    private static Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 400) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable e) {
            return null;
        }
    }
}
