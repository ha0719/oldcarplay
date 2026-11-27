package com.mcool.player.music;

import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;

import com.mcool.player.MusicBean;
import com.mcool.player.lx.LxLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 本地音乐扫描（逻辑来自 player-final，已在车机验证的 U 盘挂载点） */
public final class LocalMusic {

    public static final String[] USB_PATHS = {
            "/mnt/usb_storage", "/mnt/usbhost", "/mnt/usbhost1", "/mnt/udisk", "/storage/usb0", "/storage/usb1",
            "/storage/udisk", "/storage/usbdisk", "/mnt/media_rw/udisk", "/mnt/media_rw/usb_storage",
    };

    private LocalMusic() {}

    /** 扫描（后台线程调用）。root 为 null 时扫描内部存储 + 所有 U 盘，文件扫描为空再查 MediaStore */
    public static List<MusicBean> scan(Context c, File root) {
        List<MusicBean> out = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        if (root != null) {
            roots.add(root);
        } else {
            roots.add(Environment.getExternalStorageDirectory());
            for (String p : USB_PATHS) {
                File f = new File(p);
                if (f.exists() && f.canRead()) roots.add(f);
            }
        }
        for (File r : roots) if (r.isDirectory()) scanDir(r, out, 0);
        if (out.isEmpty() && root == null) fromMediaStore(c, out);
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        LxLog.i("本地音乐扫描完成：" + out.size() + " 首，目录 " + roots);
        return out;
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".ogg")
                || n.endsWith(".m4a") || n.endsWith(".aac");
    }

    private static void scanDir(File dir, List<MusicBean> out, int depth) {
        if (depth > 8) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (!"Android".equals(f.getName())) scanDir(f, out, depth + 1);
            } else if (isAudio(f.getName())) {
                String title = f.getName();
                int dot = title.lastIndexOf('.');
                if (dot > 0) title = title.substring(0, dot);
                String artist = "";
                int dash = title.indexOf(" - ");
                if (dash > 0) { artist = title.substring(0, dash).trim(); title = title.substring(dash + 3).trim(); }
                out.add(new MusicBean(System.nanoTime(), f.getAbsolutePath(), title, artist, "", 0));
            }
        }
    }

    private static void fromMediaStore(Context c, List<MusicBean> out) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION},
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null);
            if (cur == null) return;
            while (cur.moveToNext()) {
                String path = cur.getString(0);
                if (path == null || !isAudio(path)) continue;
                String t = cur.getString(1);
                if (t == null || t.isEmpty()) t = new File(path).getName();
                String ar = cur.getString(2);
                if (ar == null || "<unknown>".equals(ar)) ar = "";
                String al = cur.getString(3);
                out.add(new MusicBean(System.nanoTime(), path, t, ar, al == null ? "" : al, cur.getLong(4)));
            }
        } catch (Exception e) {
            LxLog.w("MediaStore 扫描失败：" + e);
        } finally {
            if (cur != null) cur.close();
        }
    }
}
