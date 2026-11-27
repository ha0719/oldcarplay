package com.mcool.player.lx;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** lx.utils.crypto / zlib 的原生实现（语义按洛雪桌面版 Node 实现对齐） */
public final class LxCrypto {
    private static final SecureRandom RND = new SecureRandom();

    private LxCrypto() {}

    public static byte[] b64d(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        return Base64.decode(s, Base64.NO_WRAP);
    }

    public static String b64e(byte[] b) {
        if (b == null) return "";
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    public static String md5Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** mode: aes-128-cbc / aes-128-ecb / aes-256-cbc ...，默认 PKCS 填充（与 Node createCipheriv 一致） */
    public static byte[] aes(byte[] data, String mode, byte[] key, byte[] iv) throws Exception {
        String m = mode == null ? "" : mode.toLowerCase(Locale.US);
        boolean ecb = m.contains("ecb");
        String padding = m.contains("nopadding") ? "NoPadding" : "PKCS5Padding";
        Cipher c = Cipher.getInstance("AES/" + (ecb ? "ECB" : "CBC") + "/" + padding);
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        if (ecb) c.init(Cipher.ENCRYPT_MODE, ks);
        else c.init(Cipher.ENCRYPT_MODE, ks, new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    /** RSA 公钥加密，无填充（与洛雪 RSA_NO_PADDING 一致） */
    public static byte[] rsa(byte[] data, String pem) throws Exception {
        String k = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(k, Base64.DEFAULT)));
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, pub);
        return c.doFinal(data);
    }

    public static byte[] random(int size) {
        byte[] b = new byte[Math.max(0, size)];
        RND.nextBytes(b);
        return b;
    }

    public static byte[] inflate(byte[] data) throws DataFormatException {
        try {
            return inflate(data, false);
        } catch (DataFormatException e) {
            return inflate(data, true); // 兜底：raw deflate
        }
    }

    private static byte[] inflate(byte[] data, boolean nowrap) throws DataFormatException {
        Inflater inf = new Inflater(nowrap);
        try {
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length * 3));
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                bos.write(buf, 0, n);
            }
            if (bos.size() == 0 && data.length > 0) throw new DataFormatException("empty inflate result");
            return bos.toByteArray();
        } finally {
            inf.end();
        }
    }

    public static byte[] deflate(byte[] data) {
        Deflater def = new Deflater();
        try {
            def.setInput(data);
            def.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!def.finished()) {
                int n = def.deflate(buf);
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            def.end();
        }
    }
}
