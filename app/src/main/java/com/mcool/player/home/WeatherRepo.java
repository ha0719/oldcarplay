package com.mcool.player.home;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * 天气：免费接口，无需 Key。
 * 主：Open-Meteo（城市名 → 经纬度用它的免费地理编码接口）；备：wttr.in。
 * 城市留空时用 GPS 的"最后已知位置"（只读缓存，不主动开 GPS，不耗性能）。
 * 默认 60 分钟刷新一次，结果缓存，断网时显示上次结果。
 */
public final class WeatherRepo {

    public interface Cb { void done(Weather w); }

    public static final class Weather {
        public boolean ok;
        public String city = "";
        public String desc = "";
        public double temp, min, max;
        public long time;
        public String source = "";
        /** 最近一次刷新的错误（有缓存时仍显示缓存数据） */
        public String error;
        /** WMO 天气代码，-1 = 未知（没图标） */
        public int code = -1;
        public boolean day = true;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    private WeatherRepo() {}

    public static Weather cached(Context c) {
        String j = HomePrefs.raw(c).getString("weather_json", null);
        if (j == null) return null;
        try {
            JSONObject o = new JSONObject(j);
            Weather w = new Weather();
            w.ok = true;
            w.city = o.optString("city");
            w.desc = o.optString("desc");
            w.temp = o.optDouble("temp");
            w.min = o.optDouble("min");
            w.max = o.optDouble("max");
            w.time = o.optLong("time");
            w.source = o.optString("source");
            w.code = o.optInt("code", -1);
            w.day = o.optBoolean("day", true);
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    private static void save(Context c, Weather w) {
        try {
            JSONObject o = new JSONObject();
            o.put("city", w.city); o.put("desc", w.desc); o.put("temp", w.temp);
            o.put("min", w.min); o.put("max", w.max); o.put("time", w.time); o.put("source", w.source); o.put("code", w.code); o.put("day", w.day);
            HomePrefs.raw(c).edit().putString("weather_json", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** force=false 时缓存未过期直接返回缓存 */
    public static void refresh(Context ctx, boolean force, final Cb cb) {
        final Context app = ctx.getApplicationContext();
        final Weather c = cached(app);
        long age = c == null ? Long.MAX_VALUE : System.currentTimeMillis() - c.time;
        // 旧版本缓存里没有天气代码（不显示图标），当作过期重新获取
        if (!force && c != null && c.code >= 0 && age < HomePrefs.weatherMinutes(app) * 60000L) {
            cb.done(c);
            return;
        }
        if (running) return;
        running = true;
        HttpUtil.POOL.execute(() -> {
            Weather w;
            try {
                w = fetch(app);
                w.ok = true;
                w.time = System.currentTimeMillis();
                save(app, w);
                LxLog.i("天气更新：" + w.city + " " + w.desc + " " + w.temp + "°（" + w.source + "，代码 " + w.code + (w.day ? " 白天" : " 夜间") + "）");
            } catch (Throwable e) {
                LxLog.w("天气获取失败：" + e);
                w = c != null ? c : new Weather();
                w.error = e.getMessage();
            }
            running = false;
            final Weather r = w;
            MAIN.post(() -> cb.done(r));
        });
    }

    private static Weather fetch(Context app) throws Exception {
        String city = HomePrefs.city(app);
        if (HomePrefs.weatherGps(app)) {
            Location l = lastLocation(app);
            if (l != null) {
                String ll = String.format(Locale.US, "%.4f,%.4f", l.getLatitude(), l.getLongitude());
                try {
                    return openMeteo(l.getLatitude(), l.getLongitude(), "当前位置");
                } catch (Exception e) {
                    LxLog.w("Open-Meteo 失败，改用 wttr.in：" + e);
                    return wttr(ll, "当前位置");
                }
            }
            LxLog.i("天气：没有 GPS 缓存位置，改用城市 " + city);
        }
        try {
            double[] ll = geocode(app, city);
            return openMeteo(ll[0], ll[1], city);
        } catch (Exception e) {
            LxLog.w("Open-Meteo 失败，改用 wttr.in：" + e);
            return wttr(city, city);
        }
    }

    private static Location lastLocation(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Exception ignored) {
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] geocode(Context app, String city) throws Exception {
        String key = "geo_" + city;
        String cached = HomePrefs.raw(app).getString(key, null);
        if (cached != null) {
            String[] p = cached.split(",");
            return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
        }
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name=" + URLEncoder.encode(city, "UTF-8");
        JSONObject o = new JSONObject(HttpUtil.getText(url));
        JSONArray r = o.optJSONArray("results");
        if (r == null || r.length() == 0) throw new Exception("找不到城市：" + city);
        JSONObject f = r.getJSONObject(0);
        double lat = f.getDouble("latitude"), lon = f.getDouble("longitude");
        HomePrefs.raw(app).edit().putString(key, lat + "," + lon).apply();
        return new double[]{lat, lon};
    }

    private static Weather openMeteo(double lat, double lon, String label) throws Exception {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", lat, lon);
        JSONObject o = new JSONObject(HttpUtil.getText(url));
        JSONObject cur = o.getJSONObject("current");
        JSONObject daily = o.getJSONObject("daily");
        Weather w = new Weather();
        w.city = label;
        w.temp = cur.getDouble("temperature_2m");
        w.code = cur.optInt("weather_code", -1);
        w.day = cur.optInt("is_day", 1) == 1;
        w.desc = wmo(w.code);
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.source = "Open-Meteo";
        return w;
    }

    private static Weather wttr(String q, String label) throws Exception {
        String url = "https://wttr.in/" + URLEncoder.encode(q, "UTF-8").replace("+", "%20") + "?format=j1&lang=zh";
        JSONObject o = new JSONObject(HttpUtil.getText(url));
        JSONObject cur = o.getJSONArray("current_condition").getJSONObject(0);
        JSONObject day = o.getJSONArray("weather").getJSONObject(0);
        Weather w = new Weather();
        w.city = label;
        w.temp = cur.getDouble("temp_C");
        JSONArray zh = cur.optJSONArray("lang_zh");
        if (zh != null && zh.length() > 0) w.desc = zh.getJSONObject(0).optString("value");
        else w.desc = cur.getJSONArray("weatherDesc").getJSONObject(0).optString("value");
        w.max = day.getDouble("maxtempC");
        w.min = day.getDouble("mintempC");
        w.source = "wttr.in";
        w.code = wwoToWmo(cur.optInt("weatherCode", -1));
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        w.day = hour >= 6 && hour < 18;
        return w;
    }

    /** wttr.in（WWO）天气代码 → 对应的 WMO 代码（只用来选图标） */
    private static int wwoToWmo(int c) {
        switch (c) {
            case 113: return 0;
            case 116: return 2;
            case 119: case 122: return 3;
            case 143: case 248: case 260: return 45;
            case 200: case 386: case 389: case 392: case 395: return 95;
            case 179: case 227: case 230: case 323: case 326: case 329: case 332: case 335: case 338:
            case 368: case 371: case 182: case 317: case 320: case 350: case 362: case 365: case 374: case 377:
                return 71;
            default:
                return c > 0 ? 61 : -1; // 其余都是各种雨
        }
    }

    /** WMO 天气代码 → 中文 */
    private static String wmo(int c) {
        switch (c) {
            case 0: return "晴";
            case 1: return "晴间多云";
            case 2: return "多云";
            case 3: return "阴";
            case 45: case 48: return "雾";
            case 51: case 53: case 55: return "毛毛雨";
            case 56: case 57: return "冻毛毛雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66: case 67: return "冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "雪粒";
            case 80: case 81: case 82: return "阵雨";
            case 85: case 86: return "阵雪";
            case 95: return "雷阵雨";
            case 96: case 99: return "雷阵雨伴冰雹";
            default: return "未知";
        }
    }

    /** 天气图标（drawable-nodpi/weather_*.png），未知返回 0 */
    public static int iconRes(int code, boolean day) {
        if (code < 0) return 0;
        if (code == 0) return day ? com.mcool.player.R.drawable.weather_sunny : com.mcool.player.R.drawable.weather_sunny_night;
        if (code == 1 || code == 2) return day ? com.mcool.player.R.drawable.weather_partly : com.mcool.player.R.drawable.weather_partly_night;
        if (code == 3) return com.mcool.player.R.drawable.weather_cloudy;
        if (code == 45 || code == 48) return com.mcool.player.R.drawable.weather_fog;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return com.mcool.player.R.drawable.weather_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return com.mcool.player.R.drawable.weather_snow;
        if (code >= 95) return com.mcool.player.R.drawable.weather_thunder;
        return 0;
    }
}
