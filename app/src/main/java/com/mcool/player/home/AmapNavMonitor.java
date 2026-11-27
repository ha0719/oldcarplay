package com.mcool.player.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.mcool.player.lx.LxLog;

/**
 * 监听高德地图车机版（com.autonavi.amapauto）标准导航广播：
 * - Action: AUTONAVI_STANDARD_BROADCAST_SEND
 * - KEY_TYPE = 10001: 实时引导信息（TBT 转向、距离、路名、剩余时间/距离、限速）
 * - KEY_TYPE = 10019: 导航状态变化（开始/结束/退出）
 */
public class AmapNavMonitor {

    public static final String ACTION_AMAP_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String TAG = "AmapNav";
    private static final long STALE_TIMEOUT_MS = 25000L;

    public static class NavState {
        public boolean navigating;
        public boolean demo;
        public int iconType = 9;
        public int segRemainDis = -1;
        public int routeRemainDis = -1;
        public int routeRemainTime = -1;
        public int limitedSpeed = 0;
        public String curRoad = "";
        public String nextRoad = "";
        public long updateElapsedMs = 0L;
    }

    public interface Listener {
        void onNavStateChanged(NavState state);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NavState state = new NavState();

    private boolean registered;
    private boolean dumpedFirstPacket;
    private int lastLoggedIcon = -1;
    private String lastLoggedNextRoad = "";

    private final Runnable staleCheck = new Runnable() {
        @Override
        public void run() {
            if (state.navigating && !state.demo) {
                long idle = SystemClock.elapsedRealtime() - state.updateElapsedMs;
                if (idle >= STALE_TIMEOUT_MS) {
                    LxLog.i("高德导航广播超时(" + (idle / 1000) + "s)，切回快捷入口");
                    clearNav();
                } else {
                    handler.postDelayed(this, STALE_TIMEOUT_MS - idle + 500L);
                }
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_AMAP_SEND.equals(intent.getAction())) return;
            Bundle extras = intent.getExtras();
            if (extras == null) return;

            int keyType = getInt(extras, "KEY_TYPE", -1);
            if (!dumpedFirstPacket && (keyType == 10001 || keyType == 10019)) {
                dumpedFirstPacket = true;
                String dump = dumpExtras(extras);
                Log.d(TAG, "First packet: " + dump);
                LxLog.i("收到高德广播首包：" + dump);
            }

            if (keyType == 10001) {
                handleGuideInfo(extras);
            } else if (keyType == 10019) {
                handleAppState(extras);
            }
        }
    };

    public AmapNavMonitor(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (registered) return;
        try {
            ctx.registerReceiver(receiver, new IntentFilter(ACTION_AMAP_SEND));
            registered = true;
        } catch (Throwable t) {
            LxLog.w("注册高德广播失败：" + t);
        }
    }

    public void stop() {
        handler.removeCallbacks(staleCheck);
        if (!registered) return;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        registered = false;
    }

    public NavState getState() {
        return state;
    }

    /** 在桌面设置里提供本地演示切换，方便未开车时测试卡片效果 */
    public void toggleDemo() {
        if (state.navigating && state.demo) {
            clearNav();
            return;
        }
        state.navigating = true;
        state.demo = true;
        state.iconType = 2; // 左转
        state.segRemainDis = 320;
        state.routeRemainDis = 12400;
        state.routeRemainTime = 18 * 60;
        state.limitedSpeed = 60;
        state.curRoad = "人民路";
        state.nextRoad = "中山大道";
        state.updateElapsedMs = SystemClock.elapsedRealtime();
        handler.removeCallbacks(staleCheck);
        notifyListener();
    }

    private void handleGuideInfo(Bundle b) {
        int icon = getInt(b, "ICON", 9);
        int segDis = getInt(b, "SEG_REMAIN_DIS", -1);
        int routeDis = getInt(b, "ROUTE_REMAIN_DIS", -1);
        int routeTime = getInt(b, "ROUTE_REMAIN_TIME", -1);
        int limit = getInt(b, "LIMITED_SPEED", 0);
        String curRoad = getStr(b, "CUR_ROAD_NAME");
        String nextRoad = getStr(b, "NEXT_ROAD_NAME");

        state.navigating = true;
        state.demo = false;
        state.iconType = icon;
        state.segRemainDis = segDis;
        state.routeRemainDis = routeDis;
        state.routeRemainTime = routeTime;
        state.limitedSpeed = limit;
        state.curRoad = curRoad;
        state.nextRoad = nextRoad;
        state.updateElapsedMs = SystemClock.elapsedRealtime();

        if (icon != lastLoggedIcon || !nextRoad.equals(lastLoggedNextRoad)) {
            lastLoggedIcon = icon;
            lastLoggedNextRoad = nextRoad;
            Log.d(TAG, "TBT icon=" + icon + " seg=" + segDis + "m next=" + nextRoad + " remain=" + routeDis + "m/" + routeTime + "s");
        }

        handler.removeCallbacks(staleCheck);
        handler.postDelayed(staleCheck, STALE_TIMEOUT_MS);
        notifyListener();
    }

    private void handleAppState(Bundle b) {
        int extraState = getInt(b, "EXTRA_STATE", -1);
        Log.d(TAG, "AppState EXTRA_STATE=" + extraState);
        // 8=开始导航, 10=开始模拟导航; 2=退出, 9=结束导航, 12=结束模拟导航, 39=到达目的地
        if (extraState == 2 || extraState == 9 || extraState == 12 || extraState == 39) {
            LxLog.i("高德导航结束(EXTRA_STATE=" + extraState + ")");
            clearNav();
        }
    }

    private void clearNav() {
        state.navigating = false;
        state.demo = false;
        handler.removeCallbacks(staleCheck);
        notifyListener();
    }

    private void notifyListener() {
        if (listener != null) {
            handler.post(() -> listener.onNavStateChanged(state));
        }
    }

    public static String actionText(int iconType) {
        switch (iconType) {
            case 2: return "左转";
            case 3: return "右转";
            case 4: return "左前方";
            case 5: return "右前方";
            case 6: return "左后方";
            case 7: return "右后方";
            case 8: return "左转掉头";
            case 19: return "右转掉头";
            case 9: return "直行";
            case 20: return "顺行";
            case 10: return "到达途经点";
            case 11: return "进入环岛";
            case 12: return "驶出环岛";
            case 13: return "前方服务区";
            case 14: return "前方收费站";
            case 15: return "到达目的地";
            case 16: return "进入隧道";
            default: return "沿路行驶";
        }
    }

    public static String formatDistance(int meters) {
        if (meters < 0) return "--";
        if (meters < 1000) return meters + "米";
        float km = meters / 1000f;
        if (km >= 100f) return Math.round(km) + "公里";
        return String.format(java.util.Locale.US, "%.1f公里", km);
    }

    public static String formatDuration(int seconds) {
        if (seconds < 0) return "";
        int mins = Math.max(1, (seconds + 30) / 60);
        if (mins < 60) return mins + "分钟";
        int h = mins / 60;
        int m = mins % 60;
        return m == 0 ? (h + "小时") : (h + "小时" + m + "分");
    }

    private static int getInt(Bundle b, String key, int def) {
        Object v = b.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt(((String) v).trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static String getStr(Bundle b, String key) {
        Object v = b.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String dumpExtras(Bundle b) {
        StringBuilder sb = new StringBuilder();
        for (String k : b.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(k).append('=').append(b.get(k));
        }
        return sb.toString();
    }
}
