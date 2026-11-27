package com.mcool.player.lx;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.mcool.player.net.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 洛雪自定义源管理：导入（URL / 本地文件）、保存、切换、更新、首次启动自动导入预设源。
 */
public final class SourceManager {

    /** 开源版不内置任何第三方音源链接，用户可通过「远程JS」或「U盘/文件」自行导入 */
    public static final String[][] PRESETS = {};

    /** 可选：开发者本地自编译时可在 assets/lx/sources/builtin.js 放置自有脚本（已加入 .gitignore，不会提交） */
    public static final String BUILTIN_ASSET = "lx/sources/builtin.js";
    /** 内置脚本更新时把这个数字加 1，已安装的车机下次启动会自动替换成新脚本 */
    private static final int BUILTIN_VERSION = 1;

    public interface Cb<T> { void done(T result, String error); }

    public static final class Source {
        public String id = "", name = "", description = "", version = "", author = "", homepage = "", url = "";
        /** true = 来自 APK 内置脚本 */
        public boolean builtin;
        public long updatedAt;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("description", description); o.put("version", version);
            o.put("author", author); o.put("homepage", homepage); o.put("url", url); o.put("updatedAt", updatedAt);
            o.put("builtin", builtin);
            return o;
        }

        static Source fromJson(JSONObject o) {
            Source s = new Source();
            s.id = o.optString("id"); s.name = o.optString("name"); s.description = o.optString("description");
            s.version = o.optString("version"); s.author = o.optString("author"); s.homepage = o.optString("homepage");
            s.url = o.optString("url"); s.updatedAt = o.optLong("updatedAt");
            s.builtin = o.optBoolean("builtin");
            return s;
        }
    }

    private static SourceManager inst;

    public static synchronized SourceManager get(Context c) {
        if (inst == null) inst = new SourceManager(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final SharedPreferences sp;
    private final List<Source> list = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean presetImporting;

    private SourceManager(Context c) {
        app = c;
        sp = c.getSharedPreferences("lx_sources", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(sp.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) list.add(Source.fromJson(a.getJSONObject(i)));
        } catch (Exception e) {
            LxLog.e("读取音源列表失败", e);
        }
    }

    private synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Source s : list) a.put(s.toJson());
            sp.edit().putString("list", a.toString()).apply();
        } catch (Exception e) {
            LxLog.e("保存音源列表失败", e);
        }
    }

    public synchronized List<Source> list() { return new ArrayList<>(list); }

    public String getActiveId() { return sp.getString("active", ""); }

    public synchronized Source getActive() {
        String id = getActiveId();
        for (Source s : list) if (s.id.equals(id)) return s;
        return null;
    }

    public synchronized Source find(String id) {
        for (Source s : list) if (s.id.equals(id)) return s;
        return null;
    }

    private File dir() {
        File d = new File(app.getFilesDir(), "lx_sources");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File scriptFile(String id) { return new File(dir(), id + ".js"); }

    public String readScript(Source s) throws IOException {
        return new String(HttpUtil.readAll(new FileInputStream(scriptFile(s.id))), "UTF-8");
    }

    /** 解析脚本头部注释 @name @description @version @author @homepage */
    public static Source parseHeader(String script) {
        Source s = new Source();
        Matcher m = Pattern.compile("^\\s*/\\*[*!]?([\\s\\S]*?)\\*/").matcher(script);
        if (m.find()) {
            Matcher f = Pattern.compile("@(name|description|version|author|homepage)\\s+([^\\r\\n]*)").matcher(m.group(1));
            while (f.find()) {
                String v = f.group(2).trim();
                switch (f.group(1)) {
                    case "name": s.name = v; break;
                    case "description": s.description = v; break;
                    case "version": s.version = v; break;
                    case "author": s.author = v; break;
                    case "homepage": s.homepage = v; break;
                }
            }
        }
        return s;
    }

    /** 保存脚本。同一 URL 再次导入视为更新（保持 id 不变）。 */
    public Source addScript(String script, String url) throws Exception {
        if (script == null || script.trim().isEmpty()) throw new Exception("脚本内容为空");
        if (script.length() > 9 * 1024 * 1024) throw new Exception("脚本过大");
        String head = script.trim();
        if (head.startsWith("<")) throw new Exception("下载到的是网页而不是 JS 脚本，请检查链接");
        Source parsed = parseHeader(script);
        Source target = null;
        synchronized (this) {
            if (url != null && !url.isEmpty()) {
                for (Source s : list) if (url.equals(s.url)) target = s;
            }
            if (target == null) {
                target = new Source();
                target.id = "src_" + System.currentTimeMillis();
                target.url = url == null ? "" : url;
                list.add(target);
            }
            target.name = parsed.name.isEmpty() ? (target.name.isEmpty() ? "未命名音源" : target.name) : parsed.name;
            target.description = parsed.description;
            target.version = parsed.version;
            target.author = parsed.author;
            target.homepage = parsed.homepage;
            target.updatedAt = System.currentTimeMillis();
        }
        FileOutputStream fo = new FileOutputStream(scriptFile(target.id));
        fo.write(script.getBytes("UTF-8"));
        fo.close();
        if (getActive() == null) sp.edit().putString("active", target.id).apply();
        save();
        LxLog.i("音源已保存：" + target.name + " v" + target.version + (target.url.isEmpty() ? "" : " ← " + target.url));
        return target;
    }

    public synchronized void delete(String id) {
        Source s0 = find(id);
        if (s0 != null && s0.builtin) {
            LxLog.w("内置音源不能删除");
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        //noinspection ResultOfMethodCallIgnored
        scriptFile(id).delete();
        if (id.equals(getActiveId())) sp.edit().putString("active", list.isEmpty() ? "" : list.get(0).id).apply();
        save();
    }

    public void setActive(String id) {
        sp.edit().putString("active", id).apply();
        Source s = find(id);
        if (s != null) loadSource(s);
    }

    // ================================================================= 异步操作（回调在主线程）

    public void importFromUrl(final String url, final Cb<Source> cb) {
        HttpUtil.POOL.execute(() -> {
            try {
                LxLog.i("下载音源：" + url);
                String script = HttpUtil.getText(url.trim());
                Source s = addScript(script, url.trim());
                post(cb, s, null);
            } catch (Throwable e) {
                LxLog.e("下载音源失败 " + url, e);
                post(cb, null, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    public void importFromFile(final File f, final Cb<Source> cb) {
        HttpUtil.POOL.execute(() -> {
            try {
                String script = new String(HttpUtil.readAll(new FileInputStream(f)), "UTF-8");
                Source s = addScript(script, null);
                post(cb, s, null);
            } catch (Throwable e) {
                LxLog.e("读取音源文件失败 " + f, e);
                post(cb, null, e.getMessage());
            }
        });
    }

    /** 导入全部预设源（开源版默认无预设源） */
    public void importPresets(final Cb<String> cb) {
        if (PRESETS.length == 0) {
            post(cb, "开源版未内置预设音源，请通过「远程JS」或「U盘/文件」自行导入", null);
            return;
        }
        if (presetImporting) return;
        presetImporting = true;
        HttpUtil.POOL.execute(() -> {
            int ok = 0;
            StringBuilder err = new StringBuilder();
            for (String[] p : PRESETS) {
                try {
                    Source s = addScript(HttpUtil.getText(p[1]), p[1]);
                    if (s.name.equals("未命名音源")) s.name = p[0];
                    ok++;
                } catch (Throwable e) {
                    LxLog.e("预设源下载失败 " + p[1], e);
                    err.append(p[0]).append("：").append(e.getMessage()).append('\n');
                }
            }
            save();
            sp.edit().putBoolean("presets_done", ok > 0).apply();
            presetImporting = false;
            final int fOk = ok;
            post(cb, "预设源导入成功 " + fOk + "/" + PRESETS.length, err.length() == 0 ? null : err.toString());
            if (fOk > 0) main.post(this::ensureActiveLoaded);
        });
    }

    /** 重新下载所有带 URL 的音源 */
    public void updateAll(final Cb<String> cb) {
        HttpUtil.POOL.execute(() -> {
            int ok = 0, total = 0;
            StringBuilder err = new StringBuilder();
            for (Source s : list()) {
                if (s.url.isEmpty()) continue;
                total++;
                try {
                    addScript(HttpUtil.getText(s.url), s.url);
                    ok++;
                } catch (Throwable e) {
                    err.append(s.name).append("：").append(e.getMessage()).append('\n');
                }
            }
            post(cb, "更新完成 " + ok + "/" + total, err.length() == 0 ? null : err.toString());
            Source a = getActive();
            if (a != null) main.post(() -> loadSource(a));
        });
    }

    /**
     * 导入（或按 BUILTIN_VERSION 升级）APK 内置音源。返回内置音源，没有内置脚本时返回 null。
     * 可在主线程调用（只读 assets、写本地小文件）。
     */
    public synchronized Source ensureBuiltin() {
        try {
            Source existing = null;
            for (Source s : list) if (s.builtin) existing = s;
            if (existing != null && sp.getInt("builtin_ver", 0) >= BUILTIN_VERSION) return existing;

            String script = new String(HttpUtil.readAll(app.getAssets().open(BUILTIN_ASSET)), "UTF-8");
            Source parsed = parseHeader(script);
            Source t = existing;
            if (t == null) {
                t = new Source();
                t.id = "src_builtin";
                t.builtin = true;
                list.add(0, t);
            }
            t.name = parsed.name.isEmpty() ? "内置音源" : parsed.name;
            t.description = parsed.description;
            t.version = parsed.version;
            t.author = parsed.author;
            t.homepage = parsed.homepage;
            t.updatedAt = System.currentTimeMillis();
            FileOutputStream fo = new FileOutputStream(scriptFile(t.id));
            fo.write(script.getBytes("UTF-8"));
            fo.close();
            sp.edit().putInt("builtin_ver", BUILTIN_VERSION).apply();
            if (getActive() == null || existing == null) sp.edit().putString("active", t.id).apply();
            save();
            LxLog.i("内置音源已导入：" + t.name + " " + t.version);
            return t;
        } catch (java.io.FileNotFoundException e) {
            return null; // 没有打包内置脚本
        } catch (Throwable e) {
            LxLog.e("导入内置音源失败", e);
            return null;
        }
    }

    /** 确保当前音源已加载到引擎；首次使用时若本地打包了内置脚本则自动导入 */
    public void ensureActiveLoaded() {
        Source builtin = ensureBuiltin();
        Source a = getActive();
        if (a == null) {
            if (builtin == null && PRESETS.length > 0 && list().isEmpty() && !sp.getBoolean("presets_done", false)) {
                LxLog.i("首次使用，自动导入预设音源");
                importPresets(null);
            }
            return;
        }
        LxEngine e = LxEngine.get(app);
        if (a.id.equals(e.getSourceId()) && (e.getState() == LxEngine.ST_READY || e.getState() == LxEngine.ST_LOADING)) return;
        loadSource(a);
    }

    public void loadSource(final Source s) {
        HttpUtil.POOL.execute(() -> {
            try {
                String script = readScript(s);
                LxEngine.get(app).load(s, script);
            } catch (Throwable e) {
                LxLog.e("读取音源脚本失败 " + s.name, e);
            }
        });
    }

    private <T> void post(final Cb<T> cb, final T r, final String err) {
        if (cb != null) main.post(() -> cb.done(r, err));
    }
}
