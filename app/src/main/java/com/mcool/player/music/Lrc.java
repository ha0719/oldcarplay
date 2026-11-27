package com.mcool.player.music;

import com.mcool.player.net.HttpUtil;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC 歌词：解析 + 读取本地歌词（同名 .lrc 优先，其次 ID3 内嵌） */
public final class Lrc {

    public static final class Line {
        public final long time;
        public final String text;

        Line(long time, String text) { this.time = time; this.text = text; }
    }

    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    private Lrc() {}

    /** 支持一行多个时间标签、[mm:ss] 与 [mm:ss.xx]；返回按时间排序的行 */
    public static List<Line> parse(String lrc) {
        List<Line> list = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return list;
        for (String line : lrc.split("\\r?\\n")) {
            Matcher m = TAG.matcher(line);
            List<Long> ts = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                try {
                    ts.add((long) ((Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2))) * 1000));
                } catch (Exception ignored) {
                }
                end = m.end();
            }
            String text = line.substring(end).trim();
            if (ts.isEmpty() || text.isEmpty()) continue;
            for (Long t : ts) list.add(new Line(t, text));
        }
        Collections.sort(list, (a, b) -> Long.compare(a.time, b.time));
        return list;
    }

    /** 当前时间对应的行号（二分查找），时间早于第一行返回 -1 */
    public static int indexAt(List<Line> lines, long ms) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).time <= ms) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    /** 读取本地歌曲的歌词文本（后台线程调用），没有返回 null */
    public static String readLocal(String audioPath) {
        if (audioPath == null) return null;
        File audio = new File(audioPath);
        int dot = audioPath.lastIndexOf('.');
        if (dot > 0) {
            File lrc = new File(audioPath.substring(0, dot) + ".lrc");
            if (lrc.isFile() && lrc.canRead()) {
                try {
                    byte[] b = HttpUtil.readAll(new FileInputStream(lrc));
                    String s = new String(b, Charset.forName("UTF-8"));
                    // UTF-8 解出替换字符，说明是 GBK 编码（老歌词文件常见）
                    if (s.indexOf('\uFFFD') >= 0) s = new String(b, Charset.forName("GBK"));
                    if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
                    return s;
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            org.jaudiotagger.tag.Tag tag = org.jaudiotagger.audio.AudioFileIO.read(audio).getTag();
            if (tag != null) {
                String t = tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS);
                if (t != null && !t.trim().isEmpty()) return t;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
