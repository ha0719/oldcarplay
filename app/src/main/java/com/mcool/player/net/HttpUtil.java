package com.mcool.player.net;

import android.content.Context;
import android.content.res.AssetManager;

import com.mcool.player.lx.LxLog;
import com.mcool.player.lx.OnlineSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 网络层：解决 Android 4.4 的 HTTPS 问题。
 * 1. 优先用 Conscrypt 提供的 TLS 实现（TLS1.2/1.3、新加密套件），失败则退回系统 TLS 并强制开启 TLS1.2
 * 2. 信任 = 系统根证书 + assets/certs 里内置的新根证书（ISRG Root X1 等，4.4 系统里没有）
 * 3. 设为 HttpsURLConnection 全局默认，ExoPlayer 的 DefaultHttpDataSource 也会自动用上
 */
public final class HttpUtil {

    public static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    public static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    private static volatile String tlsInfo = "未初始化";
    private static volatile boolean trustAll;
    private static X509TrustManager systemTm;
    private static X509TrustManager bundledTm;
    private static HostnameVerifier defaultVerifier;

    private HttpUtil() {}

    public static String tlsInfo() { return tlsInfo; }

    private static volatile Context appCtx;

    /** 是否有网络连接（只看系统网络状态；连着没有外网的热点时仍返回 true） */
    public static boolean isOnline(Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable e) {
            return true;
        }
    }

    /** 不需要传 Context 的版本（init 之后可用） */
    public static boolean isOnlineQuick() {
        Context c = appCtx;
        return c == null || isOnline(c);
    }

    /** 可重复调用（修改设置后重新初始化） */
    public static synchronized void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        trustAll = OnlineSettings.trustAll(ctx);
        boolean wantConscrypt = OnlineSettings.useConscrypt(ctx);
        try {
            systemTm = findTm(null);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            AssetManager am = ctx.getAssets();
            String[] names = am.list("certs");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            if (names != null) {
                for (String name : names) {
                    InputStream in = null;
                    try {
                        in = am.open("certs/" + name);
                        Collection<? extends Certificate> cs = cf.generateCertificates(in);
                        for (Certificate c : cs) ks.setCertificateEntry("bundled_" + (n++), c);
                    } catch (Exception e) {
                        LxLog.e("内置证书加载失败 " + name, e);
                    } finally {
                        if (in != null) try { in.close(); } catch (IOException ignored) {}
                    }
                }
            }
            bundledTm = n > 0 ? findTm(ks) : null;

            SSLContext sc = null;
            String provider = "系统";
            if (wantConscrypt) {
                try {
                    Provider p = org.conscrypt.Conscrypt.newProvider();
                    sc = SSLContext.getInstance("TLS", p);
                    provider = "Conscrypt";
                } catch (Throwable t) {
                    LxLog.e("Conscrypt 不可用，退回系统 TLS", t);
                    sc = null;
                }
            }
            if (sc == null) sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, new TrustManager[]{new CompositeTm()}, new SecureRandom());

            HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
            if (defaultVerifier == null) defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
            if (trustAll) {
                HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
            } else {
                HttpsURLConnection.setDefaultHostnameVerifier(defaultVerifier);
            }
            tlsInfo = provider + " TLS，内置根证书 " + n + " 个" + (trustAll ? "，⚠宽松证书校验已开启" : "");
            LxLog.i("网络初始化完成：" + tlsInfo);
        } catch (Throwable e) {
            tlsInfo = "网络初始化失败：" + e;
            LxLog.e("网络初始化失败", e);
        }
    }

    private static X509TrustManager findTm(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /** 系统根证书校验失败时，再用内置根证书校验一次 */
    private static final class CompositeTm implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            systemTm.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (trustAll) return;
            try {
                systemTm.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                if (bundledTm != null) {
                    try {
                        bundledTm.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException ignored) {
                    }
                }
                throw e;
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>(Arrays.asList(systemTm.getAcceptedIssuers()));
            if (bundledTm != null) all.addAll(Arrays.asList(bundledTm.getAcceptedIssuers()));
            return all.toArray(new X509Certificate[0]);
        }
    }

    /** 4.4 默认没开 TLS1.2，这里在每个 socket 上强制打开 */
    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private static final List<String> WANT = Arrays.asList("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1");
        private final SSLSocketFactory d;

        Tls12SocketFactory(SSLSocketFactory d) { this.d = d; }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> en = new ArrayList<>();
                for (String p : ss.getSupportedProtocols()) if (WANT.contains(p)) en.add(p);
                if (!en.isEmpty()) ss.setEnabledProtocols(en.toArray(new String[0]));
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return patch(d.createSocket()); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return patch(d.createSocket(s, host, port, autoClose)); }
        @Override public Socket createSocket(String host, int port) throws IOException { return patch(d.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress lh, int lp) throws IOException { return patch(d.createSocket(host, port, lh, lp)); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return patch(d.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress a, int port, InetAddress la, int lp) throws IOException { return patch(d.createSocket(a, port, la, lp)); }
    }

    // ------------------------------------------------------------------ 请求

    public static final class Response {
        public int code;
        public String message = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        public String url;

        public String text() { return decode(body, headers.get("content-type")); }
    }

    /** 同步请求（不要在主线程调用）。自动跟随重定向（含 http/https 互跳），超时单位毫秒。 */
    public static Response request(String url, String method, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        String cur = url;
        String m = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.US);
        byte[] b = body;
        if (timeoutMs <= 0) timeoutMs = 30000;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(Math.min(timeoutMs, 15000));
                c.setReadTimeout(timeoutMs);
                c.setUseCaches(false);
                c.setRequestMethod(m);
                boolean hasUa = false;
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        if ("user-agent".equalsIgnoreCase(e.getKey())) hasUa = true;
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                if (!hasUa) c.setRequestProperty("User-Agent", UA);
                if (b != null && !"GET".equals(m) && !"HEAD".equals(m)) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(b.length);
                    OutputStream os = c.getOutputStream();
                    os.write(b);
                    os.close();
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("重定向缺少 Location");
                    cur = new URL(new URL(cur), loc).toString();
                    if (code == 303 || ((code == 301 || code == 302) && "POST".equals(m))) {
                        m = "GET";
                        b = null;
                    }
                    continue;
                }
                Response r = new Response();
                r.code = code;
                r.message = c.getResponseMessage() == null ? "" : c.getResponseMessage();
                r.url = cur;
                for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                    StringBuilder sb = new StringBuilder();
                    for (String v : e.getValue()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(v);
                    }
                    r.headers.put(e.getKey().toLowerCase(Locale.US), sb.toString());
                }
                InputStream in;
                try {
                    in = c.getInputStream();
                } catch (IOException ex) {
                    in = c.getErrorStream();
                }
                if (in != null && "gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                r.body = in == null ? new byte[0] : readAll(in);
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("重定向次数过多");
    }

    public static String getText(String url) throws IOException {
        Response r = request(url, "GET", null, null, 30000);
        if (r.code >= 400) throw new IOException("HTTP " + r.code + " " + r.message);
        return r.text();
    }

    public static byte[] getBytes(String url) throws IOException {
        Response r = request(url, "GET", null, null, 30000);
        if (r.code >= 400) throw new IOException("HTTP " + r.code + " " + r.message);
        return r.body;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    public static String decode(byte[] body, String contentType) {
        String cs = "UTF-8";
        if (contentType != null) {
            int i = contentType.toLowerCase(Locale.US).indexOf("charset=");
            if (i >= 0) {
                cs = contentType.substring(i + 8).replace("\"", "").trim();
                int sc = cs.indexOf(';');
                if (sc > 0) cs = cs.substring(0, sc).trim();
            }
        }
        String s;
        try {
            s = new String(body, Charset.forName(cs));
        } catch (Exception e) {
            s = new String(body, Charset.forName("UTF-8"));
        }
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s;
    }
}
