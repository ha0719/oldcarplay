package com.mcool.player.music;

import com.mcool.player.MusicBean;
import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 导入洛雪导出的歌单文件（.lxmc 为 gzip 压缩的 JSON，也兼容未压缩的 .json）。
 * 兼容 playListPart / playListPart_v2 / playList(_v2) / allData(_v2) 几种结构，
 * v2 歌曲信息转换成洛雪旧格式（toOldMusicInfo 的做法），这样音源脚本能直接取链。
 */
public final class LxListImporter {

    public static final class Result {
        public List<PlaylistStore.Playlist> lists = new ArrayList<>();
        public int skippedLocal;
    }

    private LxListImporter() {}

    public static Result importFile(File f) throws Exception {
        byte[] raw = HttpUtil.readAll(new FileInputStream(f));
        String text;
        try {
            text = new String(HttpUtil.readAll(new GZIPInputStream(new ByteArrayInputStream(raw))), "UTF-8");
        } catch (Exception notGzip) {
            text = new String(raw, "UTF-8");
        }
        text = text.trim();
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);
        Result r = new Result();
        Object root = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        collect(root, r, 0);
        if (r.lists.isEmpty()) throw new Exception("文件里没有找到歌单（不是洛雪导出的列表文件？）");
        LxLog.i("洛雪文件导入：" + r.lists.size() + " 个歌单，跳过本地歌曲 " + r.skippedLocal + " 首 ← " + f);
        return r;
    }

    /** 递归找 {name, list:[...]} 结构 */
    private static void collect(Object node, Result r, int depth) throws Exception {
        if (depth > 6 || node == null) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), r, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        JSONArray list = o.optJSONArray("list");
        if (list != null && (o.has("name") || o.has("id"))) {
            PlaylistStore.Playlist p = new PlaylistStore.Playlist();
            p.meta.name = o.optString("name", "导入的歌单");
            if (p.meta.name.isEmpty()) p.meta.name = "导入的歌单";
            p.meta.platform = "lx";
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) continue;
                MusicBean b = toBean(s);
                if (b == null) r.skippedLocal++;
                else p.songs.add(b);
            }
            if (!p.songs.isEmpty()) r.lists.add(p);
            return;
        }
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject || v instanceof JSONArray) collect(v, r, depth + 1);
        }
    }

    private static MusicBean toBean(JSONObject s) throws Exception {
        String source = s.optString("source");
        if (source.isEmpty() || "local".equals(source)) return null;
        JSONObject mi;
        JSONObject meta = s.optJSONObject("meta");
        if (meta != null) {
            // v2 → 旧格式
            mi = new JSONObject();
            mi.put("name", s.optString("name"));
            mi.put("singer", s.optString("singer"));
            mi.put("source", source);
            mi.put("songmid", meta.opt("songId") == null ? "" : String.valueOf(meta.opt("songId")));
            mi.put("interval", s.optString("interval"));
            mi.put("albumName", meta.optString("albumName"));
            mi.put("img", meta.optString("picUrl"));
            mi.put("albumId", meta.opt("albumId") == null ? "" : String.valueOf(meta.opt("albumId")));
            mi.put("types", meta.optJSONArray("qualitys") == null ? new JSONArray() : meta.optJSONArray("qualitys"));
            mi.put("_types", meta.optJSONObject("_qualitys") == null ? new JSONObject() : meta.optJSONObject("_qualitys"));
            mi.put("typeUrl", new JSONObject());
            mi.put("lrc", JSONObject.NULL);
            mi.put("otherSource", JSONObject.NULL);
            // 各平台额外字段（kg 的 hash、tx 的 strMediaMid、mg 的 copyrightId 等）原样带上
            Iterator<String> it = meta.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!mi.has(k) && !"qualitys".equals(k) && !"_qualitys".equals(k) && !"songId".equals(k) && !"picUrl".equals(k)) {
                    mi.put(k, meta.opt(k));
                }
            }
        } else {
            mi = s; // 旧格式本身就是洛雪 musicInfo
        }
        String songmid = mi.optString("songmid");
        if (songmid.isEmpty()) songmid = mi.optString("hash", s.optString("id"));
        MusicBean b = MusicBean.online(source, songmid, mi.optString("name"), mi.optString("singer"),
                mi.optString("albumName"), parseInterval(mi.optString("interval")));
        JSONArray types = mi.optJSONArray("types");
        if (types != null) {
            for (int i = 0; i < types.length(); i++) {
                JSONObject t = types.optJSONObject(i);
                String q = t != null ? t.optString("type") : types.optString(i);
                if (!q.isEmpty() && !b.types.contains(q)) b.types.add(q);
            }
        }
        b.musicInfoJson = mi.toString();
        return b;
    }

    private static long parseInterval(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            String[] p = s.split(":");
            long sec = 0;
            for (String x : p) sec = sec * 60 + Long.parseLong(x.trim().toLowerCase(Locale.US));
            return sec * 1000;
        } catch (Exception e) {
            return 0;
        }
    }
}
