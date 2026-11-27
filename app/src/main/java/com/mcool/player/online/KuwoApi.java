package com.mcool.player.online;

import com.mcool.player.MusicBean;
import com.mcool.player.net.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 酷我（kw）搜索 / 歌单 / 榜单 / 歌词 / 封面。接口参考洛雪 musicSdk/kw，均为 HTTP 接口，不受 4.4 HTTPS 问题影响。
 */
public final class KuwoApi {

    /** 酷我榜单 ID（2026-11-26 实测）：热歌 16、新歌 17、飙升 93 */
    public static final String HOT_CHART_ID = "16";

    private KuwoApi() {}

    public static final class PlaylistInfo {
        public String name = "";
        public int total;
        public List<MusicBean> songs = new ArrayList<>();
    }

    // ================================================================= 搜索

    public static List<MusicBean> search(String keyword, int page, int limit) throws Exception {
        String url = "http://search.kuwo.cn/r.s?client=kt&all=" + URLEncoder.encode(keyword, "UTF-8")
                + "&pn=" + (page - 1) + "&rn=" + limit
                + "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music"
                + "&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1";
        JSONObject o = getJson(url);
        JSONArray arr = o.optJSONArray("abslist");
        List<MusicBean> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            MusicBean b = toBean(it.optString("MUSICRID").replace("MUSIC_", ""), it.optString("SONGNAME"),
                    it.optString("ARTIST"), it.optString("ALBUM"), it.optString("ALBUMID"),
                    parseInt(it.optString("DURATION")), it.optString("N_MINFO"), null);
            if (b != null) out.add(b);
        }
        return out;
    }

    // ================================================================= 歌单 / 榜单

    /** 从分享链接或纯数字中取出酷我歌单 ID，取不到返回 null */
    public static String parsePlaylistId(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:playlist(?:_detail)?/|pid=|[?&]id=)(\\d{4,})").matcher(text);
        if (m.find()) return m.group(1);
        String t = text.trim();
        return t.matches("\\d{4,}") ? t : null;
    }

    /** 歌单全部歌曲（分页拉取，最多 maxSongs 首） */
    public static PlaylistInfo playlist(String pid, int maxSongs) throws Exception {
        PlaylistInfo info = new PlaylistInfo();
        int rn = 100;
        for (int pn = 0; pn < 50 && info.songs.size() < maxSongs; pn++) {
            String url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=" + pid + "&pn=" + pn + "&rn=" + rn
                    + "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1";
            JSONObject o = getJson(url);
            if (pn == 0) {
                info.name = html(o.optString("title", "酷我歌单 " + pid));
                info.total = o.optInt("total", 0);
            }
            JSONArray list = o.optJSONArray("musiclist");
            if (list == null || list.length() == 0) break;
            addAll(list, info.songs);
            if (info.total > 0 && info.songs.size() >= info.total) break;
            if (list.length() < rn) break;
        }
        if (info.songs.isEmpty()) throw new Exception("歌单为空或不存在（ID " + pid + "）");
        return info;
    }

    /** 榜单（默认热歌榜） */
    public static List<MusicBean> chart(String id, int limit) throws Exception {
        String url = "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=0&rn=" + limit
                + "&type=bang&data=content&id=" + id + "&show_copyright_off=0&pcmp4=1&isbang=1";
        JSONObject o = getJson(url);
        List<MusicBean> out = new ArrayList<>();
        JSONArray list = o.optJSONArray("musiclist");
        if (list != null) addAll(list, out);
        return out;
    }

    /** 歌单 / 榜单的歌曲条目，字段名在不同接口里大小写不一，逐个兼容 */
    private static void addAll(JSONArray list, List<MusicBean> out) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject it = list.optJSONObject(i);
            if (it == null) continue;
            String rid = first(it, "id", "musicrid", "MUSICRID", "rid").replace("MUSIC_", "");
            // 榜单接口的 duration 不是时长（实测为 13 这类值），真实时长在 song_duration
            int dur = parseInt(first(it, "song_duration", "DURATION", "duration"));
            MusicBean b = toBean(rid, first(it, "name", "NAME", "SONGNAME"), first(it, "artist", "ARTIST"),
                    first(it, "album", "ALBUM"), first(it, "albumid", "albumId", "ALBUMID"), dur,
                    first(it, "N_MINFO", "MINFO", "minfo"), first(it, "formats", "FORMATS"));
            if (b != null) out.add(b);
        }
    }

    /** 生成洛雪格式 musicInfo 的 MusicBean */
    private static MusicBean toBean(String rid, String name, String artist, String album, String albumId,
                                    int sec, String minfo, String formats) {
        if (rid == null || rid.isEmpty()) return null;
        try {
            name = html(name);
            String singer = html(artist).replace("&", "、");
            album = html(album);
            MusicBean b = MusicBean.online("kw", rid, name, singer, album, sec * 1000L);
            JSONArray types = new JSONArray();
            JSONObject typesMap = new JSONObject();
            if (minfo != null && !minfo.isEmpty()) {
                for (String seg : minfo.split(";")) {
                    String t = null;
                    switch (field(seg, "bitrate")) {
                        case "4000": t = "flac24bit"; break;
                        case "2000": t = "flac"; break;
                        case "320": t = "320k"; break;
                        case "128": t = "128k"; break;
                    }
                    addType(b, types, typesMap, t, field(seg, "size").toUpperCase(Locale.US));
                }
            } else if (formats != null && !formats.isEmpty()) {
                if (formats.contains("MP3128")) addType(b, types, typesMap, "128k", "");
                if (formats.contains("MP3H")) addType(b, types, typesMap, "320k", "");
                if (formats.contains("ALFLAC")) addType(b, types, typesMap, "flac", "");
                if (formats.contains("HIRFLAC")) addType(b, types, typesMap, "flac24bit", "");
            }
            if (b.types.isEmpty()) {
                addType(b, types, typesMap, "128k", "");
                addType(b, types, typesMap, "320k", "");
            }
            JSONObject mi = new JSONObject();
            mi.put("name", name);
            mi.put("singer", singer);
            mi.put("source", "kw");
            mi.put("songmid", rid);
            mi.put("albumId", albumId == null ? "" : albumId);
            mi.put("interval", String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
            mi.put("albumName", album);
            mi.put("lrc", JSONObject.NULL);
            mi.put("img", JSONObject.NULL);
            mi.put("otherSource", JSONObject.NULL);
            mi.put("types", types);
            mi.put("_types", typesMap);
            mi.put("typeUrl", new JSONObject());
            b.musicInfoJson = mi.toString();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static void addType(MusicBean b, JSONArray types, JSONObject map, String t, String size) {
        if (t == null || b.types.contains(t)) return;
        try {
            b.types.add(t);
            JSONObject ty = new JSONObject();
            ty.put("type", t);
            ty.put("size", size);
            types.put(ty);
            JSONObject sz = new JSONObject();
            sz.put("size", size);
            map.put(t, sz);
        } catch (Exception ignored) {
        }
    }

    // ================================================================= 歌词 / 封面

    /** 返回 LRC 文本，没有则返回 null */
    public static String lyric(String rid) throws Exception {
        String url = "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" + rid + "&httpsStatus=1";
        HttpUtil.Response r = HttpUtil.request(url, "GET", null, null, 15000);
        if (r.code >= 400) return null;
        JSONObject o = new JSONObject(r.text());
        JSONObject data = o.optJSONObject("data");
        if (data == null) return null;
        JSONArray list = data.optJSONArray("lrclist");
        if (list == null || list.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            if (l == null) continue;
            double t = 0;
            try { t = Double.parseDouble(l.optString("time", "0")); } catch (Exception ignored) {}
            int min = (int) (t / 60);
            double s = t - min * 60;
            sb.append(String.format(Locale.US, "[%02d:%05.2f]", min, s)).append(html(l.optString("lineLyric"))).append('\n');
        }
        return sb.toString();
    }

    /** 返回封面图片 URL，没有则返回 null */
    public static String picUrl(String rid) throws Exception {
        String url = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=" + rid;
        HttpUtil.Response r = HttpUtil.request(url, "GET", null, null, 15000);
        String t = r.text().trim();
        return t.startsWith("http") ? t : null;
    }

    // ================================================================= 工具

    private static JSONObject getJson(String url) throws Exception {
        HttpUtil.Response r = HttpUtil.request(url, "GET", null, null, 15000);
        if (r.code >= 400) throw new Exception("HTTP " + r.code);
        String text = r.text().trim();
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject(text.replace('\'', '"'));
        }
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String field(String seg, String key) {
        for (String kv : seg.split(",")) {
            int i = kv.indexOf(':');
            if (i > 0 && kv.substring(0, i).trim().equals(key)) return kv.substring(i + 1).trim();
        }
        return "";
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String html(String s) {
        if (s == null) return "";
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#039;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").trim();
    }
}
