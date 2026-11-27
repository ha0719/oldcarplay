package com.mcool.player.lx;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一日志 + 崩溃记录：
 * 1. logcat（TAG=CarMusic）
 * 2. 内存环形缓冲（「音乐源」页右侧实时查看）
 * 3. 文件：/sdcard/CarMusic/logs/carmusic-yyyyMMdd.log（每天一个，保留 7 天，单文件超过 5MB 不再追加）
 * 4. Java 崩溃：自动写 crash-yyyyMMdd-HHmmss.txt（完整堆栈 + 设备信息 + 崩溃前 200 行日志）
 * 5. 原生崩溃（如 QuickJS so 在 4.4 上崩）Java 捕获不到：危险操作前写"步骤标记"，
 *    下次启动发现标记还在，就记录"上次在 xxx 时异常退出"
 * 所有内容都经过 redact，不含 API Key。
 */
public final class LxLog {
    public static final String TAG = "CarMusic";
    private static final int MAX = 400;
    private static final long MAX_FILE = 5L * 1024 * 1024;
    private static final int KEEP_DAYS = 7;
    private static final LinkedList<String> LINES = new LinkedList<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static volatile File dir;
    private static volatile String lastCrash;

    private LxLog() {}

    // ================================================================= 初始化

    /** 在 Application.onCreate 最开始调用 */
    public static void init(Context ctx) {
        final Context app = ctx.getApplicationContext();
        dir = pickDir(app);
        checkLastRun();
        cleanOld();
        installCrashHandler();
        i("========== 应用启动 " + deviceInfo().replace('\n', ' ') + " 日志目录=" + (dir == null ? "无" : dir.getAbsolutePath()));
    }

    private static File pickDir(Context app) {
        File[] cands = {
                new File(Environment.getExternalStorageDirectory(), "CarMusic/logs"), // 方便 adb pull / U 盘拷
                app.getExternalFilesDir("logs"),
                new File(app.getFilesDir(), "logs"),
        };
        for (File d : cands) {
            if (d == null) continue;
            try {
                if (!d.exists()) d.mkdirs();
                File t = new File(d, ".w");
                new FileOutputStream(t).close();
                t.delete();
                return d;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static File logDir() { return dir; }

    /** 上次异常退出的说明（没有则为 null），在音乐源页顶部显示 */
    public static String lastCrash() { return lastCrash; }

    public static void clearLastCrash() { lastCrash = null; }

    // ================================================================= 写日志

    /** 日志里隐藏 API Key、播放凭据（拍照/发日志时不泄露） */
    public static String redact(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)([?&](?:key|apikey|vkey|token|sign|mask|guid)=)[^&\\s\"]*", "$1***")
                .replaceAll("([A-Z0-9_]*KEY-)[A-Za-z0-9-]+", "$1***")
                .replaceAll("(?i)(\"x-api-key\"\\s*:\\s*\")[^\"]*", "$1***");
    }

    public static void i(String msg) { msg = redact(msg); add("I", msg, null); Log.i(TAG, msg); }

    public static void w(String msg) { msg = redact(msg); add("W", msg, null); Log.w(TAG, msg); }

    public static void e(String msg, Throwable t) {
        msg = redact(msg);
        add("E", t == null ? msg : msg + " : " + t, t == null ? null : redact(stack(t)));
        Log.e(TAG, msg, t);
    }

    private static synchronized void add(String level, String msg, final String stack) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        final String line = ts + " " + level + " " + msg;
        LINES.add(line);
        while (LINES.size() > MAX) LINES.removeFirst();
        if (dir != null) IO.execute(() -> append(stack == null ? line : line + "\n" + stack));
    }

    private static void append(String text) {
        File d = dir;
        if (d == null) return;
        File f = new File(d, "carmusic-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".log");
        if (f.length() > MAX_FILE) return;
        FileWriter w = null;
        try {
            w = new FileWriter(f, true);
            w.write(text);
            w.write('\n');
        } catch (Throwable ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }

    /** 最新的在最上面 */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (int i = LINES.size() - 1; i >= 0; i--) sb.append(LINES.get(i)).append('\n');
        return sb.toString();
    }

    private static synchronized String tail(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, LINES.size() - n); i < LINES.size(); i++) sb.append(LINES.get(i)).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() { LINES.clear(); }

    // ================================================================= 崩溃

    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                String report = "崩溃时间：" + ts + "\n线程：" + thread.getName() + "\n" + deviceInfo()
                        + "\n\n==== 堆栈 ====\n" + stack(ex)
                        + "\n==== 崩溃前日志 ====\n" + tail(200);
                report = redact(report);
                Log.e(TAG, "应用崩溃", ex);
                File d = dir;
                if (d != null) {
                    // 崩溃时进程马上要退出，必须同步写
                    FileWriter w = new FileWriter(new File(d, "crash-" + ts + ".txt"));
                    w.write(report);
                    w.close();
                    synchronized (LxLog.class) {
                        append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " E 应用崩溃 → crash-" + ts + ".txt\n" + redact(stack(ex)));
                    }
                }
            } catch (Throwable ignored) {
            }
            if (def != null) def.uncaughtException(thread, ex);
            else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /**
     * 原生崩溃探测：执行可能导致 so 崩溃的操作前 mark("加载 QuickJS")，成功后 unmark()。
     * 如果进程在中间直接死掉，下次启动会在 checkLastRun 里发现。
     */
    public static void mark(String step) {
        File d = dir;
        if (d == null) return;
        try {
            FileWriter w = new FileWriter(new File(d, ".running_step"));
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + step);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public static void unmark() {
        File d = dir;
        if (d != null) new File(d, ".running_step").delete();
    }

    private static void checkLastRun() {
        File d = dir;
        if (d == null) return;
        StringBuilder sb = new StringBuilder();
        File step = new File(d, ".running_step");
        if (step.exists()) {
            try {
                String s = new String(com.mcool.player.net.HttpUtil.readAll(new java.io.FileInputStream(step)), "UTF-8");
                sb.append("上次运行在「").append(s).append("」时异常退出（可能是原生库崩溃，Java 层无堆栈）。\n");
            } catch (Throwable ignored) {
            }
            step.delete();
        }
        File[] crashes = d.listFiles((dd, name) -> name.startsWith("crash-") && !name.endsWith(".seen"));
        if (crashes != null && crashes.length > 0) {
            Arrays.sort(crashes);
            File last = crashes[crashes.length - 1];
            sb.append("发现 ").append(crashes.length).append(" 个新的崩溃报告，最新：").append(last.getName());
            // 标记为已看过，下次启动不再提示
            for (File c : crashes) c.renameTo(new File(d, c.getName() + ".seen"));
        }
        if (sb.length() > 0) {
            lastCrash = sb.toString().trim();
            append("---- 启动检查：" + lastCrash.replace('\n', ' '));
        }
    }

    private static void cleanOld() {
        File d = dir;
        if (d == null) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000;
        for (File f : fs) if (f.isFile() && f.lastModified() < cutoff) f.delete();
    }

    // ================================================================= 工具

    public static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static String deviceInfo() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\n设备：" + Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.PRODUCT
                + "\nABI：" + Build.CPU_ABI + "\n固件：" + Build.DISPLAY
                + "\n内存上限：" + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + "MB";
    }
}
