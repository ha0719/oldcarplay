package com.mcool.player.lx;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.mcool.player.net.HttpUtil;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 洛雪自定义源脚本执行引擎（QuickJS）。
 * 所有 JS 操作都在单独的 "lx-js" 线程上执行；回调统一切回主线程。
 */
public final class LxEngine {

    public static final int ST_IDLE = 0, ST_LOADING = 1, ST_READY = 2, ST_FAILED = 3;

    public interface StateListener { void onStateChanged(); }

    public interface ResultCallback { void onResult(boolean ok, JSONObject data, String msg); }

    private static final int REQUEST_TIMEOUT_MS = 20000;
    private static final int INIT_TIMEOUT_MS = 20000;
    private static final String[] QUALITY_ORDER = {"flac24bit", "flac", "320k", "128k"};

    private static LxEngine inst;

    public static synchronized LxEngine get(Context c) {
        if (inst == null) inst = new LxEngine(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final Handler js;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    // ---- 以下字段只在 JS 线程读写 ----
    private QuickJSContext ctx;
    private boolean loaderInited;
    private int generation;
    private int nextReqId = 1;
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final List<Runnable> waitingReady = new ArrayList<>();
    private final Set<Integer> cancelledHttp = new HashSet<>();

    // ---- 跨线程读取 ----
    private volatile int state = ST_IDLE;
    private volatile String stateMsg = "未加载音源";
    private volatile String sourceId;
    private volatile String sourceName;
    private volatile JSONObject sources;
    private volatile String updateAlert;

    private static final class Pending {
        ResultCallback cb;
        Runnable timeout;
    }

    private LxEngine(Context c) {
        app = c;
        // 混淆过的音源脚本递归较深，JS 线程给 8MB 栈（默认线程栈较小，容易栈溢出崩溃）
        final Looper[] lp = new Looper[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(null, () -> {
            Looper.prepare();
            lp[0] = Looper.myLooper();
            latch.countDown();
            Looper.loop();
        }, "lx-js", 8L * 1024 * 1024);
        t.start();
        try { latch.await(); } catch (InterruptedException ignored) {}
        js = new Handler(lp[0]);
    }

    // ================================================================= 状态

    public int getState() { return state; }
    public boolean isReady() { return state == ST_READY; }
    public String getStateMsg() { return stateMsg; }
    public String getSourceId() { return sourceId; }
    public String getSourceName() { return sourceName; }
    public String getUpdateAlert() { return updateAlert; }

    public void addListener(StateListener l) { listeners.add(l); }
    public void removeListener(StateListener l) { listeners.remove(l); }

    private void setState(int st, String msg) {
        state = st;
        stateMsg = msg;
        LxLog.i("音源状态：" + msg);
        main.post(() -> { for (StateListener l : listeners) l.onStateChanged(); });
    }

    /** 当前音源对某平台支持的音质 */
    public List<String> qualitiesOf(String platform) {
        List<String> out = new ArrayList<>();
        JSONObject s = sources;
        if (s == null) return out;
        JSONObject p = s.optJSONObject(platform);
        if (p == null) return out;
        JSONArray q = p.optJSONArray("qualitys");
        if (q != null) for (int i = 0; i < q.length(); i++) out.add(q.optString(i));
        return out;
    }

    public boolean supportsPlatform(String platform) {
        JSONObject s = sources;
        return s != null && s.optJSONObject(platform) != null;
    }

    /** 已就绪音源支持的平台，例如 "kw(128k/320k) tx(128k)" */
    public String describeSources() {
        JSONObject s = sources;
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = s.keys();
        while (it.hasNext()) {
            String k = it.next();
            List<String> q = qualitiesOf(k);
            sb.append(k);
            if (!q.isEmpty()) sb.append('(').append(android.text.TextUtils.join("/", q)).append(')');
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * 音质尝试顺序（只是"优先"，取不到就换下一个）：
     * 128k 优先 → 128k、320k、flac（默认，逐级升级）；320k 优先 → 320k、128k、flac；flac 优先 → flac、320k、128k
     */
    public static List<String> orderFor(String preferred) {
        List<String> o = new ArrayList<>();
        if ("flac".equals(preferred)) { o.add("flac"); o.add("320k"); o.add("128k"); }
        else if ("320k".equals(preferred)) { o.add("320k"); o.add("128k"); o.add("flac"); }
        else { o.add("128k"); o.add("320k"); o.add("flac"); }
        return o;
    }

    /** 取链用的音质尝试顺序：按优先顺序，只保留音源和歌曲都支持的 */
    public List<String> qualityChain(String platform, String preferred, List<String> songTypes) {
        List<String> srcQ = qualitiesOf(platform);
        List<String> out = new ArrayList<>();
        for (String q : orderFor(preferred)) {
            if (!srcQ.isEmpty() && !srcQ.contains(q)) continue;
            if (songTypes != null && !songTypes.isEmpty() && !songTypes.contains(q)) continue;
            out.add(q);
        }
        if (out.isEmpty()) out.add(srcQ.isEmpty() ? "128k" : srcQ.get(0));
        return out;
    }

    // ================================================================= 加载

    public void load(final SourceManager.Source s, final String script) {
        sourceId = s.id;
        sourceName = s.name;
        sources = null;
        updateAlert = null;
        setState(ST_LOADING, "正在初始化音源：" + s.name);
        js.post(() -> doLoad(s, script));
    }

    private void doLoad(SourceManager.Source s, String script) {
        final int gen = ++generation;
        destroyCtx("音源已切换");
        try {
            if (!loaderInited) {
                LxLog.mark("加载 QuickJS 原生库");
                QuickJSLoader.init();
                loaderInited = true;
                LxLog.i("QuickJS 原生库加载成功");
            }
            LxLog.mark("创建 QuickJS 环境");
            ctx = QuickJSContext.create();
            installNatives(ctx, gen);
            ctx.evaluate(readAsset("lx/lx-preload.js"));
            JSONObject info = new JSONObject();
            info.put("id", s.id);
            info.put("name", s.name);
            info.put("description", s.description);
            info.put("version", s.version);
            info.put("author", s.author);
            info.put("homepage", s.homepage);
            info.put("rawScript", script);
            callGlobal("__lx_setup", info.toString());
            js.postDelayed(() -> {
                if (gen == generation && state == ST_LOADING) {
                    failLoad("初始化超时：脚本 " + (INIT_TIMEOUT_MS / 1000) + " 秒内没有发送 inited 事件，请看日志里的请求/报错");
                }
            }, INIT_TIMEOUT_MS);
            LxLog.mark("执行音源脚本 " + s.name);
            ctx.evaluate(script);
            LxLog.unmark();
            LxLog.i("音源脚本已执行，等待 inited：" + s.name);
        } catch (Throwable t) {
            LxLog.unmark();
            LxLog.e("音源脚本加载失败", t);
            failLoad((t instanceof UnsatisfiedLinkError ? "JS 引擎原生库加载失败（需用 minSdk 19 源码编译 QuickJS）：" : "加载失败：")
                    + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private void failLoad(String msg) {
        setState(ST_FAILED, msg);
        List<Runnable> w = new ArrayList<>(waitingReady);
        waitingReady.clear();
        for (Runnable r : w) r.run(); // request() 里会因为状态非 READY 直接回调失败
    }

    private void destroyCtx(String reason) {
        for (Pending p : pending.values()) {
            js.removeCallbacks(p.timeout);
            deliver(p.cb, false, null, reason);
        }
        pending.clear();
        cancelledHttp.clear();
        if (ctx != null) {
            try {
                ctx.destroy();
            } catch (Throwable t) {
                LxLog.e("销毁 QuickJS 失败", t);
            }
            ctx = null;
        }
    }

    // ================================================================= 原生函数

    private static String str(Object[] a, int i) { return a != null && a.length > i && a[i] != null ? String.valueOf(a[i]) : ""; }

    private static int num(Object[] a, int i) {
        if (a == null || a.length <= i || a[i] == null) return 0;
        if (a[i] instanceof Number) return ((Number) a[i]).intValue();
        try { return (int) Double.parseDouble(String.valueOf(a[i])); } catch (Exception e) { return 0; }
    }

    private static boolean bool(Object[] a, int i) {
        if (a == null || a.length <= i || a[i] == null) return false;
        if (a[i] instanceof Boolean) return (Boolean) a[i];
        return "true".equals(String.valueOf(a[i]));
    }

    private void installNatives(QuickJSContext c, final int gen) {
        c.getGlobalObject().setProperty("__n_log", (JSCallFunction) args -> {
            LxLog.i("[JS " + str(args, 0) + "] " + str(args, 1));
            return null;
        });
        c.getGlobalObject().setProperty("__n_timer", (JSCallFunction) args -> {
            final int id = num(args, 0);
            js.postDelayed(() -> { if (gen == generation && ctx != null) callGlobal("__lx_fire_timer", id); }, num(args, 1));
            return null;
        });
        c.getGlobalObject().setProperty("__n_event", (JSCallFunction) args -> {
            onEvent(gen, str(args, 0), str(args, 1));
            return null;
        });
        c.getGlobalObject().setProperty("__n_request", (JSCallFunction) args -> {
            startHttp(gen, num(args, 0), str(args, 1), str(args, 2), str(args, 3), str(args, 4), bool(args, 5), num(args, 6), bool(args, 7));
            return null;
        });
        c.getGlobalObject().setProperty("__n_cancel", (JSCallFunction) args -> {
            cancelledHttp.add(num(args, 0));
            return null;
        });
        c.getGlobalObject().setProperty("__n_md5", (JSCallFunction) args -> LxCrypto.md5Hex(LxCrypto.b64d(str(args, 0))));
        c.getGlobalObject().setProperty("__n_aes", (JSCallFunction) args -> {
            try {
                return LxCrypto.b64e(LxCrypto.aes(LxCrypto.b64d(str(args, 0)), str(args, 1), LxCrypto.b64d(str(args, 2)), LxCrypto.b64d(str(args, 3))));
            } catch (Throwable e) {
                LxLog.e("aesEncrypt 失败", e);
                return "";
            }
        });
        c.getGlobalObject().setProperty("__n_rsa", (JSCallFunction) args -> {
            try {
                return LxCrypto.b64e(LxCrypto.rsa(LxCrypto.b64d(str(args, 0)), str(args, 1)));
            } catch (Throwable e) {
                LxLog.e("rsaEncrypt 失败", e);
                return "";
            }
        });
        c.getGlobalObject().setProperty("__n_random", (JSCallFunction) args -> LxCrypto.b64e(LxCrypto.random(num(args, 0))));
        c.getGlobalObject().setProperty("__n_inflate", (JSCallFunction) args -> {
            try {
                return LxCrypto.b64e(LxCrypto.inflate(LxCrypto.b64d(str(args, 0))));
            } catch (Throwable e) {
                LxLog.e("inflate 失败", e);
                return null;
            }
        });
        c.getGlobalObject().setProperty("__n_deflate", (JSCallFunction) args -> {
            try {
                return LxCrypto.b64e(LxCrypto.deflate(LxCrypto.b64d(str(args, 0))));
            } catch (Throwable e) {
                LxLog.e("deflate 失败", e);
                return null;
            }
        });
    }

    /** 调用 JS 全局函数（只能在 JS 线程） */
    private void callGlobal(String name, Object... a) {
        if (ctx == null) return;
        JSFunction f = null;
        try {
            f = ctx.getGlobalObject().getJSFunction(name);
            if (f == null) {
                LxLog.w("JS 函数不存在：" + name);
                return;
            }
            f.call(a);
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            if (m.length() > 600) m = m.substring(0, 600) + "...";
            LxLog.w("调用 JS 出错 " + name + "：" + m);
        } finally {
            if (f != null) {
                try { f.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void startHttp(final int gen, final int id, final String url, final String method, final String headersJson,
                           final String data, final boolean hasBody, final int timeout, final boolean binary) {
        LxLog.i("音源请求 → " + method + " " + url);
        HttpUtil.POOL.execute(() -> {
            String err = null;
            HttpUtil.Response r = null;
            long t0 = System.currentTimeMillis();
            try {
                Map<String, String> h = new HashMap<>();
                JSONObject ho = new JSONObject(headersJson.isEmpty() ? "{}" : headersJson);
                Iterator<String> it = ho.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    h.put(k, ho.optString(k));
                }
                r = HttpUtil.request(url, method, h, hasBody ? data.getBytes("UTF-8") : null, timeout > 0 ? timeout : 60000);
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final long cost = System.currentTimeMillis() - t0;
            final String fErr = err;
            final HttpUtil.Response fr = r;
            js.post(() -> {
                if (gen != generation || ctx == null) return;
                if (cancelledHttp.remove(id)) return;
                if (fErr != null) {
                    LxLog.w("音源请求失败 (" + cost + "ms) " + url + "  " + fErr);
                    callGlobal("__lx_on_response", id, fErr, 0, "", "{}", "", "");
                } else {
                    LxLog.i("音源请求完成 HTTP " + fr.code + " (" + cost + "ms) " + url);
                    String hj = new JSONObject(fr.headers).toString();
                    callGlobal("__lx_on_response", id, "", fr.code, fr.message, hj,
                            binary ? "" : fr.text(), binary ? LxCrypto.b64e(fr.body) : "");
                }
            });
        });
    }

    private void onEvent(int gen, String name, String json) {
        if (gen != generation) return;
        try {
            JSONObject o = new JSONObject(json.isEmpty() ? "{}" : json);
            switch (name) {
                case "inited": {
                    if (state != ST_LOADING) return;
                    JSONObject src = o.optJSONObject("sources");
                    sources = src != null ? src : new JSONObject();
                    setState(ST_READY, "已就绪：" + sourceName + "　支持 " + describeSources());
                    List<Runnable> w = new ArrayList<>(waitingReady);
                    waitingReady.clear();
                    for (Runnable r : w) r.run();
                    break;
                }
                case "updateAlert":
                    updateAlert = o.optString("log", "") + (o.optString("updateUrl", "").isEmpty() ? "" : "\n" + o.optString("updateUrl"));
                    LxLog.i("音源更新提示：" + updateAlert);
                    break;
                case "response": {
                    int id = o.optInt("id");
                    Pending p = pending.remove(id);
                    if (p == null) return;
                    js.removeCallbacks(p.timeout);
                    boolean ok = o.optBoolean("ok");
                    JSONObject data = o.optJSONObject("data");
                    String msg = o.optString("msg", "");
                    LxLog.i("音源响应 #" + id + " " + (ok ? "成功" : "失败：" + msg));
                    deliver(p.cb, ok, data, msg);
                    break;
                }
                default:
                    LxLog.w("未知事件 " + name);
            }
        } catch (Throwable t) {
            LxLog.e("处理事件出错 " + name, t);
        }
    }

    // ================================================================= 对外请求

    /**
     * 向当前音源请求。action: musicUrl / lyric / pic；info = {type, musicInfo}
     * 音源还在初始化时会排队等待。回调在主线程。
     */
    public void request(final String platform, final String action, final JSONObject info, final ResultCallback cb) {
        js.post(() -> {
            if (state == ST_LOADING) {
                waitingReady.add(() -> request(platform, action, info, cb));
                return;
            }
            if (state != ST_READY || ctx == null) {
                deliver(cb, false, null, "音源未就绪（" + stateMsg + "）");
                return;
            }
            if (!supportsPlatform(platform)) {
                deliver(cb, false, null, "当前音源不支持平台 " + platform);
                return;
            }
            final int id = nextReqId++;
            final Pending p = new Pending();
            p.cb = cb;
            p.timeout = () -> {
                Pending x = pending.remove(id);
                if (x != null) {
                    LxLog.w("音源请求超时 #" + id);
                    deliver(x.cb, false, null, "音源响应超时");
                }
            };
            pending.put(id, p);
            js.postDelayed(p.timeout, REQUEST_TIMEOUT_MS);
            LxLog.i("请求音源 #" + id + " " + action + " " + platform + " " + info.optString("type"));
            callGlobal("__lx_handle_request", id, platform, action, info.toString());
        });
    }

    private void deliver(final ResultCallback cb, final boolean ok, final JSONObject data, final String msg) {
        if (cb == null) return;
        main.post(() -> cb.onResult(ok, data, msg));
    }

    // ================================================================= 自检

    /** 不依赖音源，单独验证 QuickJS 能否加载、是否支持 async/await 等语法 */
    public void selfTest(final ResultCallback cb) {
        js.post(() -> {
            QuickJSContext t = null;
            try {
                if (!loaderInited) {
                    LxLog.mark("引擎自检：加载 QuickJS 原生库");
                    QuickJSLoader.init();
                    loaderInited = true;
                    LxLog.i("QuickJS 原生库加载成功");
                }
                LxLog.mark("引擎自检：执行测试脚本");
                t = QuickJSContext.create();
                final String[] got = {null};
                t.getGlobalObject().setProperty("__report", (JSCallFunction) args -> {
                    got[0] = str(args, 0);
                    return null;
                });
                t.evaluate("(async () => {"
                        + " const a = await Promise.resolve(40);"
                        + " const o = { x: { y: 2 } };"
                        + " const r = [1, 2, 3].map(v => v ** 2).join(',');"
                        + " __report(`ok async=${a + 2} opt=${o?.x?.y} nullish=${null ?? 'n'} pow=${r}`);"
                        + "})().catch(e => __report('err ' + e))");
                final QuickJSContext tt = t;
                t = null;
                // Promise 回调由引擎在 evaluate 结束时执行；稍等后读结果
                js.postDelayed(() -> {
                    try { tt.destroy(); } catch (Throwable ignored) {}
                    LxLog.unmark();
                    String r = got[0];
                    LxLog.i("QuickJS 自检结果：" + r);
                    deliver(cb, r != null && r.startsWith("ok"), null, r == null ? "没有收到 Promise 结果（异步任务未执行）" : r);
                }, 300);
            } catch (Throwable e) {
                LxLog.unmark();
                LxLog.e("QuickJS 自检失败", e);
                if (t != null) try { t.destroy(); } catch (Throwable ignored) {}
                deliver(cb, false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private String readAsset(String name) throws Exception {
        InputStream in = app.getAssets().open(name);
        return new String(HttpUtil.readAll(in), "UTF-8");
    }
}
