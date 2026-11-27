package com.mcool.player.lx;

import android.content.Context;
import android.content.SharedPreferences;

/** 在线相关设置 */
public final class OnlineSettings {
    private static final String PREF = "online_settings";
    public static final String[] QUALITIES = {"128k", "320k", "flac"};

    private OnlineSettings() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 优先音质，默认 128k（取不到再依次升级到 320k、flac） */
    public static String quality(Context c) { return sp(c).getString("quality", "128k"); }

    public static void setQuality(Context c, String q) { sp(c).edit().putString("quality", q).apply(); }

    /** 宽松证书校验（只用于诊断：确认失败原因是不是证书问题） */
    public static boolean trustAll(Context c) { return sp(c).getBoolean("trust_all", false); }

    public static void setTrustAll(Context c, boolean v) { sp(c).edit().putBoolean("trust_all", v).apply(); }

    /** 是否使用 Conscrypt TLS（关闭则使用系统 TLS + 强制开启 TLS1.2） */
    public static boolean useConscrypt(Context c) { return sp(c).getBoolean("use_conscrypt", true); }

    public static void setUseConscrypt(Context c, boolean v) { sp(c).edit().putBoolean("use_conscrypt", v).apply(); }
}
