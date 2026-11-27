package com.mcool.player.online;

import android.util.Base64;

import com.mcool.player.MusicBean;
import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐（tx）歌单 / 歌词。接口与洛雪 musicSdk/tx 一致，需要带 y.qq.com 的 Referer。
 * 已在电脑浏览器里实测（2026-11-26）：歌单 1107187399 返回 1088 首，歌词接口正常。
 */
public final class TxApi {

    private TxApi() {}

    /** 支持：y.qq.com/n/ryqq/playlist/123、i2.y.qq.com/...playlist.html?id=123、disstid=123、纯数字 */
    public static String parsePlaylistId(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(?:playlist/|[?&](?:id|disstid)=)(\\d{5,})").matcher(text);
        if (m.find()) return m.group(1);
        String t = text.trim();
        return t.matches("\\d{5,}") ? t : null;
    }

    public static boolean isQqLink(String text) {
        return text != null && (text.contains("qq.com") || text.contains("qq音乐") || text.contains("QQ音乐"));
    }

    private static Map<String, String> headers(String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("Origin", "https://y.qq.com");
        h.put("Referer", referer);
        return h;
    }

    /** https 失败时退回 http（4.4 上 TLS 万一有问题，这两个接口都支持 http） */
    private static String get(String path, String referer) throws Exception {
        Exception last = null;
        for (String scheme : new String[]{"https://", "http://"}) {
            try {
                HttpUtil.Response r = HttpUtil.request(scheme + path, "GET", headers(referer), null, 20000);
                if (r.code >= 400) throw new Exception("HTTP " + r.code);
                return r.text();
            } catch (Exception e) {
                last = e;
                LxLog.w("QQ 接口失败（" + scheme + "）：" + e);
            }
        }
        throw last;
    }

    public static KuwoApi.PlaylistInfo playlist(String pid) throws Exception {
        String path = "c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1"
                + "&disstid=" + pid + "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0"
                + "&platform=yqq.json&needNewCode=0";
        JSONObject o = new JSONObject(get(path, "https://y.qq.com/n/yqq/playsquare/" + pid + ".html"));
        if (o.optInt("code", -1) != 0) throw new Exception("QQ 返回错误 code=" + o.optInt("code") + " " + o.optString("msg"));
        JSONArray cds = o.optJSONArray("cdlist");
        JSONObject cd = cds == null ? null : cds.optJSONObject(0);
        if (cd == null) throw new Exception("歌单不存在或未公开（ID " + pid + "）");
        KuwoApi.PlaylistInfo info = new KuwoApi.PlaylistInfo();
        info.name = "QQ·" + cd.optString("dissname", pid);
        info.total = cd.optInt("total_song_num", cd.optInt("songnum", 0));
        JSONArray list = cd.optJSONArray("songlist");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                MusicBean b = toBean(list.optJSONObject(i));
                if (b != null) info.songs.add(b);
            }
        }
        if (info.songs.isEmpty()) throw new Exception("歌单为空（ID " + pid + "）");
        LxLog.i("QQ 歌单 " + info.name + "：" + info.songs.size() + "/" + info.total + " 首");
        return info;
    }

    /**
     * QQ 榜单（2026-11-26 实测）：热歌 26、新歌 27、飙升 62，各 100 首。
     * 返回的 songInfoList 与歌单的 songlist 格式相同，直接复用 toBean。
     */
    public static List<MusicBean> chart(int topid, int num) throws Exception {
        String data = "{\"comm\":{\"uin\":0,\"format\":\"json\",\"ct\":20,\"cv\":1859},"
                + "\"toplist\":{\"module\":\"musicToplist.ToplistInfoServer\",\"method\":\"GetDetail\","
                + "\"param\":{\"topid\":" + topid + ",\"num\":" + num + ",\"period\":\"\"}}}";
        String path = "u.y.qq.com/cgi-bin/musicu.fcg?data=" + java.net.URLEncoder.encode(data, "UTF-8");
        JSONObject o = new JSONObject(get(path, "https://y.qq.com/n/ryqq/toplist/" + topid));
        JSONObject tl = o.optJSONObject("toplist");
        JSONObject d = tl == null ? null : tl.optJSONObject("data");
        JSONArray list = d == null ? null : d.optJSONArray("songInfoList");
        if (list == null || list.length() == 0) {
            throw new Exception("QQ 榜单为空 topid=" + topid + " code=" + (tl == null ? o.optInt("code") : tl.optInt("code")));
        }
        List<MusicBean> out = new java.util.ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            MusicBean b = toBean(list.optJSONObject(i));
            if (b != null) out.add(b);
        }
        return out;
    }

    /** 生成洛雪格式的 tx musicInfo（与洛雪 tx/songList.js 的 filterListDetail 一致） */
    private static MusicBean toBean(JSONObject it) {
        if (it == null) return null;
        String mid = it.optString("mid");
        if (mid.isEmpty()) return null;
        try {
            JSONObject f = it.optJSONObject("file");
            if (f == null) f = new JSONObject();
            JSONObject album = it.optJSONObject("album");
            String albumMid = album == null ? "" : album.optString("mid");
            String albumName = album == null ? "" : album.optString("name");
            JSONArray singers = it.optJSONArray("singer");
            StringBuilder singer = new StringBuilder();
            String firstSingerMid = "";
            if (singers != null) {
                for (int i = 0; i < singers.length(); i++) {
                    JSONObject s = singers.optJSONObject(i);
                    if (s == null) continue;
                    if (singer.length() > 0) singer.append('、');
                    singer.append(s.optString("name"));
                    if (firstSingerMid.isEmpty()) firstSingerMid = s.optString("mid");
                }
            }
            String name = it.optString("title", it.optString("name"));
            int sec = it.optInt("interval", 0);
            MusicBean b = MusicBean.online("tx", mid, name, singer.toString(), albumName, sec * 1000L);

            JSONArray types = new JSONArray();
            JSONObject typesMap = new JSONObject();
            addType(b, types, typesMap, "128k", f.optLong("size_128mp3"));
            addType(b, types, typesMap, "320k", f.optLong("size_320mp3"));
            addType(b, types, typesMap, "flac", f.optLong("size_flac"));
            addType(b, types, typesMap, "flac24bit", f.optLong("size_hires"));

            JSONObject mi = new JSONObject();
            mi.put("singer", singer.toString());
            mi.put("name", name);
            mi.put("albumName", albumName);
            mi.put("albumId", albumMid);
            mi.put("source", "tx");
            mi.put("interval", String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
            mi.put("songId", it.optLong("id"));
            mi.put("albumMid", albumMid);
            mi.put("strMediaMid", f.optString("media_mid"));
            mi.put("songmid", mid);
            String img = !albumMid.isEmpty() && !"空".equals(albumMid)
                    ? "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + albumMid + ".jpg"
                    : (firstSingerMid.isEmpty() ? "" : "https://y.gtimg.cn/music/photo_new/T001R500x500M000" + firstSingerMid + ".jpg");
            mi.put("img", img);
            mi.put("lrc", JSONObject.NULL);
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

    private static void addType(MusicBean b, JSONArray types, JSONObject map, String t, long size) throws Exception {
        if (size <= 0) return;
        String s = size >= 1048576 ? String.format(Locale.US, "%.2fM", size / 1048576.0) : String.format(Locale.US, "%.2fK", size / 1024.0);
        b.types.add(t);
        JSONObject ty = new JSONObject();
        ty.put("type", t);
        ty.put("size", s);
        types.put(ty);
        JSONObject sz = new JSONObject();
        sz.put("size", s);
        map.put(t, sz);
    }

    /** 歌词（LRC 文本），没有返回 null */
    public static String lyric(String songmid) throws Exception {
        String path = "c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + songmid
                + "&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0";
        String t = get(path, "https://y.qq.com/portal/player.html").trim();
        int a = t.indexOf('{'), z = t.lastIndexOf('}');
        if (a < 0 || z <= a) return null;
        JSONObject o = new JSONObject(t.substring(a, z + 1));
        String b64 = o.optString("lyric", "");
        if (b64.isEmpty()) return null;
        String lrc = new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8");
        return lrc.trim().isEmpty() ? null : lrc;
    }

    /** 给 PlaylistActivity 复用的列表类型 */
    public static List<MusicBean> songs(KuwoApi.PlaylistInfo info) { return info.songs; }
}
