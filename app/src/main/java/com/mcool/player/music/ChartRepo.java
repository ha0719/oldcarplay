package com.mcool.player.music;

import android.content.Context;

import com.mcool.player.MusicBean;
import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;
import com.mcool.player.online.KuwoApi;
import com.mcool.player.online.TxApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线榜单：QQ 音乐 / 酷我的热歌、新歌、飙升榜（2026-11-26 在电脑浏览器实测，各 100 首）。
 * 注意：洛雪音源脚本只负责"取播放链接"，不提供歌单；榜单和歌单都来自各平台自己的接口。
 * 缓存到 files/charts/，6 小时内直接用缓存；网络失败时用旧缓存。
 */
public final class ChartRepo {

    public static final String PREFIX = "chart_";
    private static final long TTL = 6 * 3600 * 1000L;

    /** id → 名称 */
    public static final String[][] CHARTS = {
            {"chart_tx_26", "QQ热歌"},
            {"chart_tx_27", "QQ新歌"},
            {"chart_kg_8888", "酷狗热歌"},      // 酷狗 TOP500
            {"chart_kg_74534", "酷狗新歌"},
            {"chart_tx_62", "QQ飙升"},
            {"chart_kw_16", "酷我热歌"},
            {"chart_kw_17", "酷我新歌"},
            {"chart_kw_93", "酷我飙升"},
    };

    private ChartRepo() {}

    public static boolean isChart(String id) { return id != null && id.startsWith(PREFIX); }

    public static String name(String id) {
        for (String[] c : CHARTS) if (c[0].equals(id)) return c[1];
        return id;
    }

    private static File file(Context c, String id) {
        File d = new File(c.getFilesDir(), "charts");
        if (!d.exists()) d.mkdirs();
        return new File(d, id + ".json");
    }

    /** 缓存里的歌曲数，没缓存返回 -1 */
    public static int cachedCount(Context c, String id) {
        try {
            JSONObject o = new JSONObject(new String(HttpUtil.readAll(new FileInputStream(file(c, id))), "UTF-8"));
            JSONArray a = o.optJSONArray("songs");
            return a == null ? -1 : a.length();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 读取榜单（后台线程调用）。force=true 时忽略缓存时间强制刷新 */
    public static List<MusicBean> load(Context c, String id, boolean force) throws Exception {
        File f = file(c, id);
        List<MusicBean> cached = null;
        long age = Long.MAX_VALUE;
        if (f.exists()) {
            try {
                JSONObject o = new JSONObject(new String(HttpUtil.readAll(new FileInputStream(f)), "UTF-8"));
                age = System.currentTimeMillis() - o.optLong("t");
                cached = new ArrayList<>();
                JSONArray a = o.optJSONArray("songs");
                if (a != null) for (int i = 0; i < a.length(); i++) cached.add(PlaylistStore.fromJson(a.getJSONObject(i)));
            } catch (Exception e) {
                cached = null;
            }
        }
        if (!force && cached != null && !cached.isEmpty() && age < TTL) return cached;
        try {
            List<MusicBean> songs = fetch(id);
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            JSONArray a = new JSONArray();
            for (MusicBean s : songs) a.put(PlaylistStore.toJson(s));
            o.put("songs", a);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(o.toString().getBytes("UTF-8"));
            fo.close();
            LxLog.i("榜单 " + name(id) + " 更新：" + songs.size() + " 首");
            return songs;
        } catch (Exception e) {
            LxLog.w("榜单 " + name(id) + " 获取失败：" + e);
            if (cached != null && !cached.isEmpty()) return cached;
            throw e;
        }
    }

    private static List<MusicBean> fetch(String id) throws Exception {
        String[] p = id.substring(PREFIX.length()).split("_");
        if (p.length != 2) throw new Exception("未知榜单 " + id);
        if ("tx".equals(p[0])) return TxApi.chart(Integer.parseInt(p[1]), 100);
        if ("kw".equals(p[0])) return KuwoApi.chart(p[1], 100);
        if ("kg".equals(p[0])) return com.mcool.player.online.KgApi.chart(p[1], 100);
        throw new Exception("未知平台 " + p[0]);
    }
}
