package com.mcool.player.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.widget.ImageView;

import com.mcool.player.R;
import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 壁纸：从指定文件夹（默认 /sdcard/CarMusic/wallpapers，可改成 U 盘目录）随机轮播。
 * - 更换间隔为 0 = 不切换：固定显示一张（记住是哪张，重启后不变），可在设置里手动换一张
 * - 按屏幕尺寸缩小解码 + RGB_565，切换无动画；文件夹为空时用内置背景
 * - 桌面不可见时 pause()，不再解码换图
 * - 当前壁纸位图共享给全屏播放页（current()），不另外解码一张；旧图交给 GC，不手动 recycle
 */
public final class WallpaperRotator {

    private static volatile Bitmap shared;

    /** 当前桌面壁纸（null = 用内置背景） */
    public static Bitmap current() { return shared; }

    /** 给其他页面设置同一张壁纸 */
    public static void applyTo(ImageView iv) {
        Bitmap b = shared;
        if (b != null) iv.setImageBitmap(b);
        else iv.setImageResource(R.drawable.bg);
    }

    private final Context app;
    private final ImageView iv;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<File> files = new ArrayList<>();
    private int idx = -1;
    private boolean running;
    private long nextAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            next();
            int m = HomePrefs.wallMinutes(app);
            if (m <= 0) return; // 不切换
            long ms = m * 60000L;
            nextAt = System.currentTimeMillis() + ms;
            main.postDelayed(this, ms);
        }
    };

    public WallpaperRotator(Context c, ImageView iv) {
        this.app = c.getApplicationContext();
        this.iv = iv;
    }

    private static boolean fixed(Context c) { return HomePrefs.wallMinutes(c) <= 0; }

    /** 重新扫描文件夹并开始显示；"不切换"模式下显示上次固定的那张 */
    public void start() {
        stop();
        running = true;
        scan();
        if (files.isEmpty()) {
            shared = null;
            iv.setImageResource(R.drawable.bg);
            return;
        }
        if (fixed(app)) {
            String keep = HomePrefs.raw(app).getString("wall_current", "");
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).getAbsolutePath().equals(keep)) { idx = i - 1; break; }
            }
        }
        main.post(tick);
    }

    /** 设置里的「立即换一张」：任何模式都换到下一张（不切换模式下，换完就固定这张） */
    public void changeNow() {
        if (files.isEmpty()) {
            start();
            return;
        }
        main.removeCallbacks(tick);
        running = true;
        main.post(tick);
    }

    /** 桌面不可见时暂停 */
    public void pause() {
        main.removeCallbacks(tick);
    }

    /** 回到桌面时继续：到时间了就换，否则等剩余时间；不切换模式什么都不做 */
    public void resume() {
        if (!running || files.isEmpty() || fixed(app)) return;
        main.removeCallbacks(tick);
        long wait = nextAt - System.currentTimeMillis();
        if (wait <= 0) main.post(tick);
        else main.postDelayed(tick, wait);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    public int count() { return files.size(); }

    private void scan() {
        files.clear();
        File d = new File(HomePrefs.wallDir(app));
        if (d.equals(HomePrefs.defaultWallDir()) && !d.exists()) d.mkdirs();
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) files.add(f);
            }
        }
        Collections.shuffle(files);
        idx = -1;
        LxLog.i("壁纸文件夹 " + d + "：" + files.size() + " 张");
    }

    /** 立即换下一张 */
    public void next() {
        if (files.isEmpty()) return;
        idx = (idx + 1) % files.size();
        final File f = files.get(idx);
        HomePrefs.raw(app).edit().putString("wall_current", f.getAbsolutePath()).apply();
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        final int tw = Math.max(dm.widthPixels, dm.heightPixels), th = Math.min(dm.widthPixels, dm.heightPixels);
        HttpUtil.POOL.execute(() -> {
            Bitmap b = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= tw && o.outHeight / (s * 2) >= th) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                o2.inPreferredConfig = Bitmap.Config.RGB_565;
                b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            } catch (Throwable e) {
                LxLog.w("壁纸解码失败 " + f + "：" + e);
            }
            final Bitmap fb = b;
            main.post(() -> {
                if (fb == null || !running) return;
                shared = fb;
                iv.setImageBitmap(fb);
            });
        });
    }
}
