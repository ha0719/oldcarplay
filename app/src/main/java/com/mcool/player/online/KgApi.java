package com.mcool.player.online;

import android.util.Base64;

import com.mcool.player.MusicBean;
import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 酷狗（kg）榜单 / 歌词。接口与洛雪 musicSdk/kg 一致，2026-11-27 在电脑浏览器实测：
 * - 榜单：TOP500 rankid=8888（"酷狗热歌"）、新歌榜 74534；mobilecdnbj 的 https 证书不对，只用 http
 * - 歌词：krcs.kugou.com 按 hash 搜索 → lyrics.kugou.com 下载（base64 LRC）
 * 歌曲信息按洛雪格式生成（songmid=audio_id，hash 为 128k 的 hash，各音质 hash 在 types/_types 里）
 */
public final class KgApi {

    public static final String TOP500 = "8888";
    public static final String NEW_SONGS = "74534";

    private KgApi() {}

    private static String get(String... urls) throws Exception {
        Exception last = null;
        for (String u : urls) {
            try {
                HttpUtil.Response r = HttpUtil.request(u, "GET", null, null, 20000);
                if (r.code >= 400) throw new Exception("HTTP " + r.code);
                return r.text();
            } catch (Exception e) {
                last = e;
                LxLog.w("酷狗接口失败：" + u.replaceAll("\\?.*", "") + " " + e);
            }
        }
        throw last;
    }

    public static List<MusicBean> chart(String rankid, int size) throws Exception {
        String q = "/api/v3/rank/song?version=9108&ranktype=1&plat=0&pagesize=" + size
                + "&area_code=1&page=1&rankid=" + rankid + "&with_res_tag=0";
        JSONObject o = new JSONObject(get("http://mobilecdnbj.kugou.com" + q, "http://mobilecdn.kugou.com" + q));
        JSONObject data = o.optJSONObject("data");
        JSONArray info = data == null ? null : data.optJSONArray("info");
        List<MusicBean> out = new ArrayList<>();
        if (info != null) {
            for (int i = 0; i < info.length(); i++) {
                MusicBean b = toBean(info.optJSONObject(i));
                if (b != null) out.add(b);
            }
        }
        if (out.isEmpty()) throw new Exception("酷狗榜单为空 rankid=" + rankid);
        return out;
    }

    private static MusicBean toBean(JSONObject it) {
        if (it == null) return null;
        String hash = it.optString("hash");
        if (hash.isEmpty()) return null;
        try {
            String audioId = it.optString("audio_id");
            if (audioId.isEmpty() || "0".equals(audioId)) audioId = hash;
            StringBuilder singer = new StringBuilder();
            JSONArray authors = it.optJSONArray("authors");
            if (authors != null) {
                for (int i = 0; i < authors.length(); i++) {
                    JSONObject a = authors.optJSONObject(i);
                    if (a == null) continue;
                    if (singer.length() > 0) singer.append('、');
                    singer.append(a.optString("author_name"));
                }
            }
            String name = it.optString("songname");
            String filename = it.optString("filename");
            int dash = filename.indexOf(" - ");
            if (dash > 0) {
                if (singer.length() == 0) singer.append(filename.substring(0, dash).trim());
                if (name.isEmpty()) name = filename.substring(dash + 3).trim();
            }
            if (name.isEmpty()) name = filename;
            int sec = it.optInt("duration", 0);
            String albumName = it.optString("remark");
            String img = it.optString("album_sizable_cover").replace("{size}", "480");

            MusicBean b = MusicBean.online("kg", audioId, name, singer.toString(), albumName, sec * 1000L);
            JSONArray types = new JSONArray();
            JSONObject typesMap = new JSONObject();
            addType(b, types, typesMap, "128k", it.optLong("filesize"), hash);
            addType(b, types, typesMap, "320k", it.optLong("320filesize"), it.optString("320hash"));
            addType(b, types, typesMap, "flac", it.optLong("sqfilesize"), it.optString("sqhash"));
            addType(b, types, typesMap, "flac24bit", it.optLong("filesize_high"), it.optString("hash_high"));

            JSONObject mi = new JSONObject();
            mi.put("singer", singer.toString());
            mi.put("name", name);
            mi.put("albumName", albumName);
            mi.put("albumId", it.optString("album_id"));
            mi.put("songmid", audioId);
            mi.put("source", "kg");
            mi.put("interval", String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
            mi.put("img", img.startsWith("http") ? img : JSONObject.NULL);
            mi.put("lrc", JSONObject.NULL);
            mi.put("otherSource", JSONObject.NULL);
            mi.put("hash", hash);
            mi.put("types", types);
            mi.put("_types", typesMap);
            mi.put("typeUrl", new JSONObject());
            b.musicInfoJson = mi.toString();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static void addType(MusicBean b, JSONArray types, JSONObject map, String t, long size, String hash) throws Exception {
        if (size <= 0 || hash == null || hash.isEmpty()) return;
        String s = size >= 1048576 ? String.format(Locale.US, "%.2fM", size / 1048576.0) : String.format(Locale.US, "%.2fK", size / 1024.0);
        b.types.add(t);
        JSONObject ty = new JSONObject();
        ty.put("type", t);
        ty.put("size", s);
        ty.put("hash", hash);
        types.put(ty);
        JSONObject sz = new JSONObject();
        sz.put("size", s);
        sz.put("hash", hash);
        map.put(t, sz);
    }

    /** 按歌曲 hash 取歌词（LRC 文本），没有返回 null */
    public static String lyric(String hash) throws Exception {
        if (hash == null || hash.isEmpty()) return null;
        String sq = "/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=" + hash + "&album_audio_id=";
        JSONObject o = new JSONObject(get("https://krcs.kugou.com" + sq, "http://krcs.kugou.com" + sq));
        JSONArray c = o.optJSONArray("candidates");
        JSONObject first = c == null ? null : c.optJSONObject(0);
        if (first == null) return null;
        String dq = "/download?ver=1&client=pc&id=" + first.optString("id") + "&accesskey=" + first.optString("accesskey")
                + "&fmt=lrc&charset=utf8";
        JSONObject d = new JSONObject(get("https://lyrics.kugou.com" + dq, "http://lyrics.kugou.com" + dq));
        String b64 = d.optString("content", "");
        if (b64.isEmpty()) return null;
        String lrc = new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8");
        return lrc.trim().isEmpty() ? null : lrc;
    }
}
