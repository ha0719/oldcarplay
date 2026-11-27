package com.mcool.player.music;

import android.content.Context;
import android.content.SharedPreferences;

import com.mcool.player.MusicBean;
import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地歌单与播放队列存储（应用私有目录 files/playlists/*.json）。
 * 固定歌单：我喜欢（love）、本地音乐（local）；其余为导入的歌单。
 * 播放队列单独保存：歌曲列表写文件（变化时），序号/进度/模式写 SharedPreferences（频繁）。
 */
public final class PlaylistStore {

    public static final String LOVE = "love";
    public static final String LOCAL = "local";
    private static final String QUEUE_FILE = "queue";

    public static final class Meta {
        public String id = "", name = "";
        public int count;
        /** 导入来源：kw 歌单 ID 或文件名，用于"同步" */
        public String platform = "", extId = "", origin = "";
        public long updatedAt;

        public boolean fixed() { return LOVE.equals(id) || LOCAL.equals(id); }
    }

    public static final class Playlist {
        public Meta meta = new Meta();
        public List<MusicBean> songs = new ArrayList<>();
    }

    public static final class QueueState {
        public List<MusicBean> songs = new ArrayList<>();
        public int index = -1;
        public long posMs;
        public int mode;
    }

    private static Set<String> loveKeys;

    private PlaylistStore() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "playlists");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("playlists", Context.MODE_PRIVATE);
    }

    // ================================================================= MusicBean <-> JSON

    public static JSONObject toJson(MusicBean b) throws Exception {
        JSONObject o = new JSONObject();
        o.put("path", b.path);
        o.put("title", b.title);
        o.put("artist", b.artist);
        o.put("album", b.album);
        o.put("duration", b.duration);
        if (b.online) {
            o.put("online", true);
            o.put("source", b.source);
            o.put("songmid", b.songmid);
            o.put("mi", b.musicInfoJson);
            o.put("types", new JSONArray(b.types));
        }
        return o;
    }

    public static MusicBean fromJson(JSONObject o) {
        MusicBean b = new MusicBean(System.nanoTime(), o.optString("path"), o.optString("title"),
                o.optString("artist"), o.optString("album"), o.optLong("duration"));
        if (o.optBoolean("online")) {
            b.online = true;
            b.source = o.optString("source");
            b.songmid = o.optString("songmid");
            b.musicInfoJson = o.optString("mi", "{}");
            JSONArray t = o.optJSONArray("types");
            if (t != null) for (int i = 0; i < t.length(); i++) b.types.add(t.optString(i));
        }
        return b;
    }

    private static JSONArray songsToJson(List<MusicBean> songs) throws Exception {
        JSONArray a = new JSONArray();
        for (MusicBean s : songs) a.put(toJson(s));
        return a;
    }

    private static List<MusicBean> songsFromJson(JSONArray a) {
        List<MusicBean> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(fromJson(o));
        }
        return out;
    }

    private static void writeFile(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        fo.write(s.getBytes("UTF-8"));
        fo.close();
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }

    private static String readFile(File f) throws Exception {
        return new String(HttpUtil.readAll(new FileInputStream(f)), "UTF-8");
    }

    // ================================================================= 歌单

    /** 歌单列表：我喜欢、本地音乐在前，导入的歌单按导入时间 */
    public static synchronized List<Meta> list(Context c) {
        List<Meta> out = new ArrayList<>();
        Meta love = new Meta();
        love.id = LOVE;
        love.name = "我喜欢";
        love.count = sp(c).getInt("count_" + LOVE, 0);
        out.add(love);
        Meta local = new Meta();
        local.id = LOCAL;
        local.name = "本地音乐";
        local.count = sp(c).getInt("count_" + LOCAL, 0);
        out.add(local);
        try {
            JSONArray a = new JSONArray(sp(c).getString("index", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Meta m = new Meta();
                m.id = o.optString("id");
                m.name = o.optString("name");
                m.count = o.optInt("count");
                m.platform = o.optString("platform");
                m.extId = o.optString("extId");
                m.origin = o.optString("origin");
                m.updatedAt = o.optLong("updatedAt");
                out.add(m);
            }
        } catch (Exception e) {
            LxLog.e("读取歌单索引失败", e);
        }
        return out;
    }

    public static synchronized Playlist load(Context c, String id) {
        Playlist p = new Playlist();
        for (Meta m : list(c)) if (m.id.equals(id)) p.meta = m;
        File f = new File(dir(c), id + ".json");
        if (!f.exists()) return p;
        try {
            JSONObject o = new JSONObject(readFile(f));
            p.songs = songsFromJson(o.optJSONArray("songs"));
        } catch (Exception e) {
            LxLog.e("读取歌单失败 " + id, e);
        }
        return p;
    }

    /** 保存歌单（新导入的歌单 meta.id 为空时自动生成） */
    public static synchronized void save(Context c, Playlist p) {
        try {
            if (p.meta.id.isEmpty()) p.meta.id = "pl_" + System.currentTimeMillis();
            p.meta.count = p.songs.size();
            p.meta.updatedAt = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            o.put("songs", songsToJson(p.songs));
            writeFile(new File(dir(c), p.meta.id + ".json"), o.toString());
            if (p.meta.fixed()) {
                sp(c).edit().putInt("count_" + p.meta.id, p.meta.count).apply();
                if (LOVE.equals(p.meta.id)) loveKeys = null;
                return;
            }
            JSONArray a = new JSONArray(sp(c).getString("index", "[]"));
            JSONArray out = new JSONArray();
            boolean found = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                if (x.optString("id").equals(p.meta.id)) { x = metaJson(p.meta); found = true; }
                out.put(x);
            }
            if (!found) out.put(metaJson(p.meta));
            sp(c).edit().putString("index", out.toString()).apply();
        } catch (Exception e) {
            LxLog.e("保存歌单失败 " + p.meta.name, e);
        }
    }

    private static JSONObject metaJson(Meta m) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", m.id);
        o.put("name", m.name);
        o.put("count", m.count);
        o.put("platform", m.platform);
        o.put("extId", m.extId);
        o.put("origin", m.origin);
        o.put("updatedAt", m.updatedAt);
        return o;
    }

    public static synchronized void delete(Context c, String id) {
        if (LOVE.equals(id) || LOCAL.equals(id)) return;
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), id + ".json").delete();
        try {
            JSONArray a = new JSONArray(sp(c).getString("index", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).optString("id").equals(id)) out.put(a.get(i));
            sp(c).edit().putString("index", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ================================================================= 我喜欢

    public static synchronized boolean isLoved(Context c, MusicBean s) {
        if (s == null) return false;
        if (loveKeys == null) {
            loveKeys = new HashSet<>();
            for (MusicBean b : load(c, LOVE).songs) loveKeys.add(b.path);
        }
        return loveKeys.contains(s.path);
    }

    /** 切换收藏，返回切换后的状态 */
    public static synchronized boolean toggleLove(Context c, MusicBean s) {
        Playlist p = load(c, LOVE);
        boolean was = false;
        for (int i = p.songs.size() - 1; i >= 0; i--) {
            if (p.songs.get(i).path.equals(s.path)) { p.songs.remove(i); was = true; }
        }
        if (!was) p.songs.add(0, s);
        save(c, p);
        return !was;
    }

    // ================================================================= 播放队列

    // ---------------- 启动时默认播放顺序 ----------------
    /** 特殊项：上次的播放队列 */
    public static final String START_LAST = "__last__";
    /** 酷我热歌榜（旧版本保存的 "__hot__" 会自动换成它） */
    public static final String START_HOT = "chart_kw_16";
    private static final String LEGACY_HOT = "__hot__";

    /**
     * 启动时依次尝试的来源（第一个有歌的生效）。默认：上次的队列 → 我喜欢 → 酷我热歌榜。
     * 里面可以是歌单 id，也可以是 START_LAST / START_HOT。
     */
    /** 特殊项：名字叫「我的收藏」的歌单（由用户自己维护，只认名字） */
    public static final String START_FAV = "__fav__";
    public static final String FAV_NAME = "我的收藏";
    /** 特殊项：已完整缓存的歌（离线可播） */
    public static final String CACHED = "__cached__";

    /** 默认顺序：上次的播放队列 → 我的收藏 → QQ热歌 → QQ新歌 → 酷狗热歌 → 酷狗新歌 */
    public static List<String> defaultOrder() {
        List<String> out = new ArrayList<>();
        out.add(START_LAST);
        out.add(START_FAV);
        out.add("chart_tx_26");
        out.add("chart_tx_27");
        out.add("chart_kg_8888");
        out.add("chart_kg_74534");
        return out;
    }

    /**
     * 播放列表顺序（开机和播放页「切换歌单」共用）：开机从上到下第一个有歌的作为播放队列；
     * 「切换歌单」按这个顺序循环（跳过"上次的播放队列"）。
     */
    public static synchronized List<String> startupOrder(Context c) {
        String raw = sp(c).getString("startup_order", null);
        // 没设置过，或者还是旧版本的默认值 → 用新的默认顺序
        if (raw == null || raw.equals("[\"__last__\",\"love\",\"__hot__\"]") || raw.equals("[\"__last__\",\"love\",\"chart_kw_16\"]")) {
            return defaultOrder();
        }
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            Set<String> valid = new HashSet<>();
            valid.add(START_LAST);
            valid.add(START_FAV);
            valid.add(CACHED);
            for (String[] ch : ChartRepo.CHARTS) valid.add(ch[0]);
            for (Meta m : list(c)) valid.add(m.id);
            for (int i = 0; i < a.length(); i++) {
                String id = a.optString(i);
                if (LEGACY_HOT.equals(id)) id = START_HOT;
                if (valid.contains(id) && !out.contains(id)) out.add(id); // 已删除的歌单自动去掉
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void setStartupOrder(Context c, List<String> ids) {
        sp(c).edit().putString("startup_order", new JSONArray(ids).toString()).apply();
    }

    /** 「切换歌单」用的顺序：去掉"上次的播放队列" */
    public static List<String> cycleOrder(Context c) {
        List<String> o = startupOrder(c);
        o.remove(START_LAST);
        return o;
    }

    public static int cycleIndex(Context c) { return sp(c).getInt("cycle_idx", -1); }

    public static void setCycleIndex(Context c, int i) { sp(c).edit().putInt("cycle_idx", i).apply(); }

    /** 按名字找歌单（只找导入/自建的歌单） */
    public static Meta findByName(Context c, String name) {
        for (Meta m : list(c)) if (!m.fixed() && name.equals(m.name.trim())) return m;
        return null;
    }

    /** 列表的显示名 */
    public static String listName(Context c, String id) {
        if (START_LAST.equals(id)) return "上次的播放队列";
        if (START_FAV.equals(id)) return FAV_NAME;
        if (CACHED.equals(id)) return "已缓存";
        if (ChartRepo.isChart(id)) return ChartRepo.name(id);
        for (Meta m : list(c)) if (m.id.equals(id)) return m.name;
        return id;
    }

    /** 顺序设置里显示的名字（带说明） */
    public static String startupName(Context c, String id) {
        if (START_LAST.equals(id)) return "上次的播放队列（继续上次的歌和进度，只用于开机）";
        if (START_FAV.equals(id)) {
            Meta m = findByName(c, FAV_NAME);
            return FAV_NAME + (m == null ? "（还没有叫这个名字的歌单）" : "（" + m.count + " 首）");
        }
        if (CACHED.equals(id)) return "已缓存（离线可播）";
        if (ChartRepo.isChart(id)) return ChartRepo.name(id) + "（在线榜单）";
        for (Meta m : list(c)) if (m.id.equals(id)) return m.name + "（" + m.count + " 首）";
        return id;
    }

    /** 所有可选来源（用于"添加"） */
    public static List<String> startupCandidates(Context c) {
        List<String> out = new ArrayList<>();
        out.add(START_LAST);
        out.add(START_FAV);
        for (Meta m : list(c)) out.add(m.id);
        out.add(CACHED);
        for (String[] ch : ChartRepo.CHARTS) out.add(ch[0]);
        return out;
    }

    public static void saveQueueName(Context c, String name) { sp(c).edit().putString("q_name", name == null ? "" : name).apply(); }

    public static String queueName(Context c) { return sp(c).getString("q_name", ""); }

    public static synchronized void saveQueueSongs(Context c, List<MusicBean> songs) {
        try {
            JSONObject o = new JSONObject();
            o.put("songs", songsToJson(songs));
            writeFile(new File(dir(c), QUEUE_FILE + ".json"), o.toString());
        } catch (Exception e) {
            LxLog.e("保存播放队列失败", e);
        }
    }

    public static void saveQueuePos(Context c, int index, long posMs, int mode) {
        sp(c).edit().putInt("q_index", index).putLong("q_pos", posMs).putInt("q_mode", mode).apply();
    }

    public static synchronized QueueState loadQueue(Context c) {
        QueueState q = new QueueState();
        q.index = sp(c).getInt("q_index", -1);
        q.posMs = sp(c).getLong("q_pos", 0);
        q.mode = sp(c).getInt("q_mode", 0);
        File f = new File(dir(c), QUEUE_FILE + ".json");
        if (f.exists()) {
            try {
                q.songs = songsFromJson(new JSONObject(readFile(f)).optJSONArray("songs"));
            } catch (Exception e) {
                LxLog.e("读取播放队列失败", e);
            }
        }
        if (q.index >= q.songs.size()) q.index = q.songs.isEmpty() ? -1 : 0;
        return q;
    }
}
