package com.mcool.player;

import java.util.ArrayList;
import java.util.List;

public class MusicBean {
    public String path;
    public String title;
    public String artist;
    public String album;
    public long duration;
    public long id;

    // ---- 在线歌曲（洛雪音源）----
    /** true = 在线歌曲，path 为 "online://平台/songmid"，只作唯一标识用 */
    public boolean online;
    /** 平台：kw / kg / tx / wy / mg */
    public String source = "";
    public String songmid = "";
    /** 洛雪格式的 musicInfo（JSON），取链时原样传给音源脚本 */
    public String musicInfoJson = "{}";
    /** 歌曲可用音质，如 128k / 320k / flac */
    public List<String> types = new ArrayList<>();

    public MusicBean(long id, String path, String title, String artist, String album, long duration) {
        this.id = id;
        this.path = path;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
    }

    public static MusicBean online(String source, String songmid, String title, String artist, String album, long durationMs) {
        MusicBean b = new MusicBean(System.currentTimeMillis(), "online://" + source + "/" + songmid,
                title, artist == null ? "" : artist, album == null ? "" : album, durationMs);
        b.online = true;
        b.source = source;
        b.songmid = songmid;
        return b;
    }
}
