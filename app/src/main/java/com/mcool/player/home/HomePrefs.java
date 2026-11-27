package com.mcool.player.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Environment;

import java.io.File;

/** 桌面相关设置 */
public final class HomePrefs {

    /** Dock 上可自定义的应用位（第 4 个固定是桌面设置） */
    public static final int SLOT_COUNT = 3;

    /** 导航应用候选：车机版高德、高德车机 lite、手机版高德 */
    public static final String[] NAV_CANDIDATES = {
            "com.autonavi.amapauto", "com.autonavi.amapautolite", "com.autonavi.minimap",
    };

    /** 系统原桌面（博泰） */
    public static final String SYSTEM_LAUNCHER = "com.bdstar.packages.launcher";

    private HomePrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    // ---------------- 常用应用 / 导航 ----------------

    /** 返回 ComponentName.flattenToString()，空串表示未设置 */
    public static String slot(Context c, int i) { return sp(c).getString("slot" + i, ""); }

    /** 换应用时同时清掉自定义名字和图标 */
    public static void setSlot(Context c, int i, String comp) {
        sp(c).edit().putString("slot" + i, comp == null ? "" : comp).remove("slot" + i + "_name").apply();
        //noinspection ResultOfMethodCallIgnored
        slotIconFile(c, i).delete();
    }

    /** 自定义显示名，空串 = 用应用自己的名字 */
    public static String slotName(Context c, int i) { return sp(c).getString("slot" + i + "_name", ""); }

    public static void setSlotName(Context c, int i, String name) {
        sp(c).edit().putString("slot" + i + "_name", name == null ? "" : name.trim()).apply();
    }

    /** 自定义图标（已缩放到 128px，存在应用私有目录，拔掉 U 盘也不丢） */
    public static File slotIconFile(Context c, int i) {
        File d = new File(c.getFilesDir(), "icons");
        if (!d.exists()) d.mkdirs();
        return new File(d, "slot" + i + ".png");
    }

    /** 选图标时扫描的文件夹 */
    public static File defaultIconDir() {
        return new File(Environment.getExternalStorageDirectory(), "CarMusic/icons");
    }

    public static String nav(Context c) {
        String v = sp(c).getString("nav", "");
        if (!v.isEmpty()) return v;
        for (String p : NAV_CANDIDATES) {
            ComponentName cn = launchComponent(c, p);
            if (cn != null) return cn.flattenToString();
        }
        return "";
    }

    public static void setNav(Context c, String comp) { sp(c).edit().putString("nav", comp == null ? "" : comp).apply(); }

    public static ComponentName launchComponent(Context c, String pkg) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            return i == null ? null : i.getComponent();
        } catch (Exception e) {
            return null;
        }
    }

    public static CharSequence label(Context c, ComponentName cn) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getActivityInfo(cn, 0).loadLabel(pm);
        } catch (Exception e) {
            return null;
        }
    }

    public static Drawable icon(Context c, ComponentName cn) {
        try {
            return c.getPackageManager().getActivityIcon(cn);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 天气 ----------------

    public static final String DEFAULT_CITY = "北京";

    /** 天气城市，默认北京 */
    public static String city(Context c) {
        String v = sp(c).getString("city", DEFAULT_CITY);
        return v == null || v.trim().isEmpty() ? DEFAULT_CITY : v.trim();
    }

    public static void setCity(Context c, String v) {
        sp(c).edit().putString("city", v == null || v.trim().isEmpty() ? DEFAULT_CITY : v.trim()).apply();
    }

    /** 天气是否用 GPS 定位（只读系统缓存的最后位置，不主动开 GPS），默认关 */
    public static boolean weatherGps(Context c) { return sp(c).getBoolean("weather_gps", false); }

    public static void setWeatherGps(Context c, boolean on) { sp(c).edit().putBoolean("weather_gps", on).apply(); }

    /** 天气刷新间隔（分钟） */
    public static int weatherMinutes(Context c) { return sp(c).getInt("weather_min", 60); }

    public static void setWeatherMinutes(Context c, int v) { sp(c).edit().putInt("weather_min", v).apply(); }

    public static SharedPreferences raw(Context c) { return sp(c); }

    // ---------------- 壁纸 ----------------

    public static File defaultWallDir() {
        return new File(Environment.getExternalStorageDirectory(), "CarMusic/wallpapers");
    }

    public static String wallDir(Context c) { return sp(c).getString("wall_dir", defaultWallDir().getAbsolutePath()); }

    public static void setWallDir(Context c, String v) { sp(c).edit().putString("wall_dir", v).apply(); }

    /** 壁纸更换间隔（分钟） */
    public static int wallMinutes(Context c) { return sp(c).getInt("wall_min", 10); }

    public static void setWallMinutes(Context c, int v) { sp(c).edit().putInt("wall_min", v).apply(); }

    // ---------------- 巡航小地图 ----------------

    public static boolean miniMapEnabled(Context c) { return sp(c).getBoolean("mini_map_on", true); }

    public static void setMiniMapEnabled(Context c, boolean on) { sp(c).edit().putBoolean("mini_map_on", on).apply(); }

    public static double[] lastLatLon(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains("map_lat") || !p.contains("map_lon")) return null;
        return new double[]{
                Double.longBitsToDouble(p.getLong("map_lat", 0L)),
                Double.longBitsToDouble(p.getLong("map_lon", 0L))
        };
    }

    public static void setLastLatLon(Context c, double lat, double lon) {
        sp(c).edit()
                .putLong("map_lat", Double.doubleToRawLongBits(lat))
                .putLong("map_lon", Double.doubleToRawLongBits(lon))
                .apply();
    }

    /** 应用位图标：自定义图标优先，其次应用自己的图标 */
    public static Drawable slotDrawable(Context c, int slot, ComponentName cn) {
        File f = slotIconFile(c, slot);
        if (f.exists()) {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return new android.graphics.drawable.BitmapDrawable(c.getResources(), b);
        }
        return icon(c, cn);
    }

    // ---------------- 外观 / 播放 ----------------

    /** 卡片透明度（不透明程度，百分比），默认 50 */
    public static int cardAlpha(Context c) { return sp(c).getInt("card_alpha", 50); }

    public static void setCardAlpha(Context c, int v) { sp(c).edit().putInt("card_alpha", v).apply(); }

    /** 歌词提前显示的毫秒数，默认 2 秒 */
    public static int lyricLeadMs(Context c) { return sp(c).getInt("lyric_lead_ms", 2000); }

    public static void setLyricLeadMs(Context c, int v) { sp(c).edit().putInt("lyric_lead_ms", v).apply(); }

    /** 歌曲缓存上限（MB），0 = 不限，默认 2GB */
    public static int cacheMaxMb(Context c) { return sp(c).getInt("cache_max_mb", 2048); }

    public static void setCacheMaxMb(Context c, int v) { sp(c).edit().putInt("cache_max_mb", v).apply(); }
}
