package com.mcool.player.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;

import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面向 Android 4.4 / i.MX6Q / 1.7GB RAM 优化的轻量 2D 栅格瓦片小地图：
 * - 零第三方地图 SDK，纯 2D Canvas 绘制
 * - 内置 WGS-84 -> GCJ-02 坐标纠偏，直接对接高德 2D 路网瓦片（复用了 Conscrypt TLS）
 * - 内存严格限制为 24 张 RGB_565 瓦片（约 3MB），退后台调用 evictMemory() 立刻归还内存
 * - 本地磁盘缓存常用路线瓦片，二次加载免流量
 */
public class MiniMapView extends View {

    private static final int MAX_MEM_TILES = 24;
    private static final int MAX_DISK_TILES = 400;
    private static final long FAIL_RETRY_MS = 20000L;

    // 默认演示坐标（上海人民广场），获取到 GPS 或上次缓存坐标后自动覆盖
    private double gcjLat = 31.2304;
    private double gcjLon = 121.4737;
    private float bearing = 0f;
    private boolean hasBearing = false;
    private boolean hasGpsFix = false;
    private int zoom = 16;
    private boolean active = true;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService tilePool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memCache = new LruCache<>(MAX_MEM_TILES);
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> failTimes = Collections.synchronizedMap(new HashMap<>());
    private final File diskCacheDir;

    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final Path arrowPath = new Path();
    private final RectF viewRect = new RectF();
    private final Rect dstRect = new Rect();

    private float cornerRadiusPx;
    private int tileDrawPx;
    private boolean loggedFirstTileError;

    public MiniMapView(Context context) {
        this(context, null);
    }

    public MiniMapView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MiniMapView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        cornerRadiusPx = 20f * density;
        // 240dpi 下放大约 1.25 倍，让车机上看路名更清晰
        tileDrawPx = Math.round(256f * Math.max(1f, density * 0.85f));

        diskCacheDir = new File(context.getCacheDir(), "map_tiles");
        if (!diskCacheDir.exists()) diskCacheDir.mkdirs();

        bgPaint.setColor(0xFF1A222D);
        gridPaint.setColor(0xFF253040);
        gridPaint.setStrokeWidth(1f);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0x33FFFFFF);
        borderPaint.setStrokeWidth(density);

        markerHaloPaint.setStyle(Paint.Style.FILL);
        markerHaloPaint.setColor(0x442979FF);

        markerRingPaint.setStyle(Paint.Style.FILL);
        markerRingPaint.setColor(0xFFFFFFFF);

        markerFillPaint.setStyle(Paint.Style.FILL);
        markerFillPaint.setColor(0xFF1E88E5);
    }

    public void setWgsLocation(double wgsLat, double wgsLon, float bearingDeg, boolean hasBearing, boolean liveFix) {
        double[] gcj = wgs84ToGcj02(wgsLat, wgsLon);
        this.gcjLat = gcj[0];
        this.gcjLon = gcj[1];
        this.bearing = bearingDeg;
        this.hasBearing = hasBearing;
        this.hasGpsFix = liveFix;
        invalidate();
    }

    public void setZoom(int z) {
        int clamped = Math.max(13, Math.min(18, z));
        if (this.zoom != clamped) {
            this.zoom = clamped;
            invalidate();
        }
    }

    public void setActive(boolean active) {
        this.active = active;
        if (active) invalidate();
    }

    /** 退到后台或切去高德时释放内存中的瓦片缓存，优先把内存留给高德 */
    public void evictMemory() {
        memCache.evictAll();
    }

    public void release() {
        active = false;
        memCache.evictAll();
        tilePool.shutdownNow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewRect.set(0, 0, w, h);
        clipPath.reset();
        clipPath.addRoundRect(viewRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.save();
        try {
            canvas.clipPath(clipPath);
        } catch (UnsupportedOperationException ignored) {
        }

        // 底色 + 细网格（无网或瓦片加载中兜底）
        canvas.drawRect(0, 0, w, h, bgPaint);
        int step = tileDrawPx / 4;
        for (int gx = step; gx < w; gx += step) canvas.drawLine(gx, 0, gx, h, gridPaint);
        for (int gy = step; gy < h; gy += step) canvas.drawLine(0, gy, w, gy, gridPaint);

        // 计算当前中心点在 Web Mercator 下的连续瓦片坐标
        int n = 1 << zoom;
        double latRad = Math.toRadians(gcjLat);
        double tileXFloat = (gcjLon + 180.0) / 360.0 * n;
        double tileYFloat = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;

        int centerTileX = (int) Math.floor(tileXFloat);
        int centerTileY = (int) Math.floor(tileYFloat);
        float offsetX = (float) ((tileXFloat - centerTileX) * tileDrawPx);
        float offsetY = (float) ((tileYFloat - centerTileY) * tileDrawPx);

        float cx = w * 0.5f;
        float cy = h * 0.5f;

        int halfCols = (int) Math.ceil(cx / tileDrawPx) + 1;
        int halfRows = (int) Math.ceil(cy / tileDrawPx) + 1;

        for (int dx = -halfCols; dx <= halfCols; dx++) {
            int tx = ((centerTileX + dx) % n + n) % n;
            int left = Math.round(cx - offsetX + dx * tileDrawPx);
            if (left >= w || left + tileDrawPx <= 0) continue;

            for (int dy = -halfRows; dy <= halfRows; dy++) {
                int ty = centerTileY + dy;
                if (ty < 0 || ty >= n) continue;
                int top = Math.round(cy - offsetY + dy * tileDrawPx);
                if (top >= h || top + tileDrawPx <= 0) continue;

                String key = zoom + "_" + tx + "_" + ty;
                Bitmap bmp = memCache.get(key);
                if (bmp != null && !bmp.isRecycled()) {
                    dstRect.set(left, top, left + tileDrawPx, top + tileDrawPx);
                    canvas.drawBitmap(bmp, null, dstRect, tilePaint);
                } else if (active) {
                    requestTile(zoom, tx, ty, key);
                }
            }
        }

        // 柔和暗色遮罩，避免白底地图在夜间车机上过于刺眼
        canvas.drawColor(0x1F0B1018);

        // 中心车标 / 定位点
        drawVehicleMarker(canvas, cx, cy);

        canvas.restore();

        // 卡片细描边（与 card_bg 风格统一）
        canvas.drawRoundRect(viewRect, cornerRadiusPx, cornerRadiusPx, borderPaint);
    }

    private void drawVehicleMarker(Canvas canvas, float cx, float cy) {
        float density = getResources().getDisplayMetrics().density;
        float haloR = 20f * density;
        float outerR = 11f * density;
        float innerR = 8f * density;

        canvas.drawCircle(cx, cy, haloR, markerHaloPaint);

        if (hasBearing && hasGpsFix) {
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(bearing);
            float tip = 13f * density;
            float wing = 9f * density;
            float tail = 5f * density;

            arrowPath.reset();
            arrowPath.moveTo(0f, -tip - 2f * density);
            arrowPath.lineTo(wing + 2f * density, tip * 0.72f + 2f * density);
            arrowPath.lineTo(0f, tail + 2f * density);
            arrowPath.lineTo(-wing - 2f * density, tip * 0.72f + 2f * density);
            arrowPath.close();
            canvas.drawPath(arrowPath, markerRingPaint);

            arrowPath.reset();
            arrowPath.moveTo(0f, -tip);
            arrowPath.lineTo(wing, tip * 0.7f);
            arrowPath.lineTo(0f, tail);
            arrowPath.lineTo(-wing, tip * 0.7f);
            arrowPath.close();
            canvas.drawPath(arrowPath, markerFillPaint);
            canvas.restore();
        } else {
            canvas.drawCircle(cx, cy, outerR, markerRingPaint);
            markerFillPaint.setColor(hasGpsFix ? 0xFF1E88E5 : 0xFF78909C);
            canvas.drawCircle(cx, cy, innerR, markerFillPaint);
            markerFillPaint.setColor(0xFF1E88E5);
        }
    }

    private void requestTile(final int z, final int x, final int y, final String key) {
        if (inFlight.contains(key)) return;
        Long failedAt = failTimes.get(key);
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < FAIL_RETRY_MS) return;

        inFlight.add(key);
        tilePool.execute(() -> {
            try {
                File f = new File(diskCacheDir, key + ".png");
                byte[] data = null;
                if (f.exists() && f.length() > 100) {
                    data = readFile(f);
                }
                if (data == null) {
                    int sub = ((x + y) & 3) + 1; // webrd01 ~ webrd04
                    String url = String.format(Locale.US,
                            "https://webrd0%d.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=%d&y=%d&z=%d",
                            sub, x, y, z);
                    data = HttpUtil.getBytes(url);
                    if (data != null && data.length > 100) {
                        saveDiskTile(f, data);
                    }
                }
                if (data != null && data.length > 100) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                    if (bmp != null) {
                        failTimes.remove(key);
                        memCache.put(key, bmp);
                        if (active) mainHandler.post(MiniMapView.this::invalidate);
                        return;
                    }
                }
                failTimes.put(key, SystemClock.elapsedRealtime());
            } catch (Throwable t) {
                failTimes.put(key, SystemClock.elapsedRealtime());
                if (!loggedFirstTileError) {
                    loggedFirstTileError = true;
                    LxLog.w("小地图瓦片加载失败：" + t);
                }
            } finally {
                inFlight.remove(key);
            }
        });
    }

    private void saveDiskTile(File f, byte[] data) {
        try {
            String[] list = diskCacheDir.list();
            if (list != null && list.length >= MAX_DISK_TILES) return;
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static byte[] readFile(File f) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            return HttpUtil.readAll(fis);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------- WGS-84 -> GCJ-02（中国大陆坐标纠偏） ----------------

    private static double[] wgs84ToGcj02(double lat, double lon) {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) {
            return new double[]{lat, lon};
        }
        double a = 6378245.0;
        double ee = 0.00669342162296594323;
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1.0 - ee * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((a * (1.0 - ee)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
