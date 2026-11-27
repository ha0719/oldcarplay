package com.mcool.player.music;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mcool.player.FilePickerDialog;
import com.mcool.player.MusicBean;
import com.mcool.player.MusicService;
import com.mcool.player.R;
import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;
import com.mcool.player.online.KuwoApi;
import com.mcool.player.online.TxApi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 播放队列 + 我的歌单。左边歌单列表（播放队列 / 我喜欢 / 本地音乐 / 导入的歌单），右边歌曲。
 * 点歌单里的歌：用该歌单替换播放队列并从这首开始播；点队列里的歌：直接跳过去。
 */
public class PlaylistActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN = "open";
    public static final String QUEUE = "__queue__";

    private final List<PlaylistStore.Meta> lists = new ArrayList<>();
    private final List<MusicBean> songs = new ArrayList<>();
    private String currentId = PlaylistStore.LOVE;
    private TextView tvTitle;
    private ListView lvLists;
    private ListAdapter listAdapter;
    private SongAdapter songAdapter;
    private boolean busy;

    private MusicService svc;
    private boolean bound;
    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            svc = ((MusicService.MusicBinder) service).getService();
            bound = true;
            if (QUEUE.equals(currentId)) showList(QUEUE);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist);
        tvTitle = findViewById(R.id.tvTitle);
        lvLists = findViewById(R.id.lvLists);
        ListView lvSongs = findViewById(R.id.lvSongs);
        listAdapter = new ListAdapter();
        songAdapter = new SongAdapter();
        lvLists.setAdapter(listAdapter);
        lvSongs.setAdapter(songAdapter);

        String open = getIntent().getStringExtra(EXTRA_OPEN);
        if (open != null) currentId = open;

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlayAll).setOnClickListener(v -> playFrom(0));
        findViewById(R.id.btnAction).setOnClickListener(v -> showActions());
        findViewById(R.id.btnImport).setOnClickListener(v -> showImport());
        findViewById(R.id.btnStartup).setOnClickListener(v -> showStartupOrder());
        lvLists.setOnItemClickListener((p, v, pos, id) -> showList(lists.get(pos).id));
        lvSongs.setOnItemClickListener((p, v, pos, id) -> playFrom(pos));
        lvSongs.setOnItemLongClickListener((p, v, pos, id) -> { songMenu(pos); return true; });

        bindService(new Intent(this, MusicService.class), conn, Context.BIND_AUTO_CREATE);
        reloadLists();
        showList(currentId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            try { unbindService(conn); } catch (Exception ignored) {}
            bound = false;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ================================================================= 列表

    private void reloadLists() {
        lists.clear();
        PlaylistStore.Meta q = new PlaylistStore.Meta();
        q.id = QUEUE;
        q.name = "▶ 播放队列";
        q.count = svc == null ? -1 : svc.getQueue().size();
        lists.add(q);
        List<PlaylistStore.Meta> mine = PlaylistStore.list(this);
        for (PlaylistStore.Meta m : mine) if (m.fixed()) lists.add(m);
        PlaylistStore.Meta cached = new PlaylistStore.Meta();
        cached.id = PlaylistStore.CACHED;
        cached.name = "已缓存（离线可播）";
        cached.count = -1;
        cached.origin = "听过的在线歌曲";
        lists.add(cached);
        for (String[] ch : ChartRepo.CHARTS) {
            PlaylistStore.Meta m = new PlaylistStore.Meta();
            m.id = ch[0];
            m.name = "🔥 " + ch[1];
            m.count = ChartRepo.cachedCount(this, ch[0]);
            m.origin = "在线榜单";
            lists.add(m);
        }
        for (PlaylistStore.Meta m : mine) if (!m.fixed()) lists.add(m);
        listAdapter.notifyDataSetChanged();
    }

    private PlaylistStore.Meta meta(String id) {
        for (PlaylistStore.Meta m : lists) if (m.id.equals(id)) return m;
        return null;
    }

    private void showList(final String id) {
        currentId = id;
        for (int i = 0; i < lists.size(); i++) if (lists.get(i).id.equals(id)) lvLists.setItemChecked(i, true);
        PlaylistStore.Meta m = meta(id);
        tvTitle.setText(m == null ? "" : m.name);
        if (QUEUE.equals(id)) {
            songs.clear();
            if (svc != null) songs.addAll(svc.getQueue());
            tvTitle.setText("播放队列（" + songs.size() + " 首）");
            songAdapter.notifyDataSetChanged();
            return;
        }
        if (ChartRepo.isChart(id)) {
            loadChart(id, false);
            return;
        }
        if (PlaylistStore.CACHED.equals(id)) {
            loadCached();
            return;
        }
        HttpUtil.POOL.execute(() -> {
            final PlaylistStore.Playlist p = PlaylistStore.load(this, id);
            runOnUiThread(() -> {
                if (!id.equals(currentId)) return;
                songs.clear();
                songs.addAll(p.songs);
                tvTitle.setText(p.meta.name + "（" + songs.size() + " 首）");
                songAdapter.notifyDataSetChanged();
                if (songs.isEmpty() && PlaylistStore.LOCAL.equals(id)) tvTitle.setText("本地音乐（点「操作」扫描）");
            });
        });
    }

    private void playFrom(int pos) {
        if (svc == null || songs.isEmpty()) return;
        if (QUEUE.equals(currentId)) svc.user().playSong(pos);
        else svc.user().setQueue(songs, pos, true, PlaylistStore.listName(this, currentId));
        finish(); // 回到音乐页
    }

    private void songMenu(final int pos) {
        final MusicBean s = songs.get(pos);
        final boolean loved = PlaylistStore.isLoved(this, s);
        List<String> items = new ArrayList<>();
        items.add("下一首播放");
        items.add(loved ? "取消收藏" : "收藏到「我喜欢」");
        if (QUEUE.equals(currentId)) items.add("从播放队列移除");
        else if (!PlaylistStore.LOCAL.equals(currentId) && !ChartRepo.isChart(currentId)
                && !PlaylistStore.CACHED.equals(currentId)) items.add("从这个歌单删除");
        new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        if (svc != null) svc.user().insertNextAndPlay(s);
                    } else if (w == 1) {
                        PlaylistStore.toggleLove(this, s);
                        reloadLists();
                        if (PlaylistStore.LOVE.equals(currentId)) showList(currentId);
                    } else if (QUEUE.equals(currentId)) {
                        if (svc != null) svc.removeFromQueue(pos);
                        reloadLists();
                        showList(QUEUE);
                    } else {
                        PlaylistStore.Playlist p = PlaylistStore.load(this, currentId);
                        if (pos < p.songs.size()) p.songs.remove(pos);
                        PlaylistStore.save(this, p);
                        reloadLists();
                        showList(currentId);
                    }
                })
                .show();
    }

    // ================================================================= 操作

    private void showActions() {
        final PlaylistStore.Meta m = meta(currentId);
        if (m == null) return;
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (QUEUE.equals(currentId)) {
            items.add("清空播放队列");
            acts.add(() -> { if (svc != null) { svc.stopPlayback(); svc.user().setQueue(new ArrayList<>(), 0, false); } reloadLists(); showList(QUEUE); });
            items.add("把队列保存为歌单");
            acts.add(() -> askName("保存为歌单", "我的歌单", name -> {
                PlaylistStore.Playlist p = new PlaylistStore.Playlist();
                p.meta.name = name;
                p.songs = new ArrayList<>(songs);
                PlaylistStore.save(this, p);
                reloadLists();
                toast("已保存：" + name);
            }));
        } else if (PlaylistStore.LOCAL.equals(currentId)) {
            items.add("扫描全部（内部存储 + U 盘）");
            acts.add(() -> scanLocal(null));
            items.add("选择目录扫描");
            acts.add(() -> new FilePickerDialog(this, this::scanLocal).show());
        } else if (PlaylistStore.CACHED.equals(currentId)) {
            items.add("清空全部缓存");
            acts.add(() -> new AlertDialog.Builder(this).setTitle("清空全部已缓存的歌？")
                    .setPositiveButton("清空", (d, w) -> MusicCache.clear(this, svc == null ? null : svc.getPlayingCacheKey(),
                            () -> { toast("缓存已清空"); showList(PlaylistStore.CACHED); }))
                    .setNegativeButton("取消", null).show());
        } else if (ChartRepo.isChart(currentId)) {
            items.add("刷新榜单");
            acts.add(() -> loadChart(currentId, true));
            items.add("保存为我的歌单（固定当前这一版）");
            acts.add(() -> askName("保存为歌单", ChartRepo.name(currentId), name -> {
                PlaylistStore.Playlist p = new PlaylistStore.Playlist();
                p.meta.name = name;
                p.songs = new ArrayList<>(songs);
                PlaylistStore.save(this, p);
                reloadLists();
                toast("已保存：" + name);
            }));
        } else if (!m.fixed()) {
            if (("kw".equals(m.platform) || "tx".equals(m.platform)) && !m.extId.isEmpty()) {
                items.add("从" + ("tx".equals(m.platform) ? "QQ 音乐" : "酷我") + "同步最新歌曲");
                acts.add(() -> importLink(m.platform, m.extId, m.id));
            }
            items.add("重命名");
            acts.add(() -> askName("重命名歌单", m.name, name -> {
                PlaylistStore.Playlist p = PlaylistStore.load(this, m.id);
                p.meta.name = name;
                PlaylistStore.save(this, p);
                reloadLists();
                showList(m.id);
            }));
            items.add("删除这个歌单");
            acts.add(() -> new AlertDialog.Builder(this).setTitle("删除「" + m.name + "」？")
                    .setPositiveButton("删除", (d, w) -> { PlaylistStore.delete(this, m.id); reloadLists(); showList(PlaylistStore.LOVE); })
                    .setNegativeButton("取消", null).show());
        }
        if (!QUEUE.equals(currentId)) {
            items.add("启动时优先播放这个歌单");
            acts.add(() -> {
                List<String> order = PlaylistStore.startupOrder(this);
                order.remove(m.id);
                order.add(0, m.id);
                PlaylistStore.setStartupOrder(this, order);
                toast("已设为启动时第一个播放：" + m.name);
            });
        }
        if (items.isEmpty()) {
            toast("这个歌单没有可用操作");
            return;
        }
        new AlertDialog.Builder(this).setTitle(m.name)
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("关闭", null).show();
    }

    private interface NameCb { void done(String name); }

    private void askName(String title, String def, final NameCb cb) {
        final EditText et = new EditText(this);
        et.setTextSize(22);
        et.setSingleLine(true);
        et.setText(def);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this).setTitle(title).setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) cb.done(n);
                })
                .setNegativeButton("取消", null).show();
    }

    private void scanLocal(final File root) {
        if (busy) return;
        busy = true;
        tvTitle.setText("扫描中…");
        HttpUtil.POOL.execute(() -> {
            List<MusicBean> found = LocalMusic.scan(this, root);
            PlaylistStore.Playlist p = PlaylistStore.load(this, PlaylistStore.LOCAL);
            p.songs = found;
            PlaylistStore.save(this, p);
            runOnUiThread(() -> {
                busy = false;
                toast("找到 " + found.size() + " 首本地歌曲");
                reloadLists();
                showList(PlaylistStore.LOCAL);
            });
        });
    }

    // ================================================================= 导入

    private void loadCached() {
        tvTitle.setText("已缓存（读取中…）");
        songs.clear();
        songAdapter.notifyDataSetChanged();
        HttpUtil.POOL.execute(() -> {
            final List<MusicBean> l = MusicCache.cachedSongs(this);
            final long used = MusicCache.usedBytes(this);
            runOnUiThread(() -> {
                if (!PlaylistStore.CACHED.equals(currentId)) return;
                songs.clear();
                songs.addAll(l);
                tvTitle.setText("已缓存（" + l.size() + " 首，占用 " + (used / 1048576) + "MB，没网也能播）");
                songAdapter.notifyDataSetChanged();
                for (PlaylistStore.Meta m : lists) if (m.id.equals(PlaylistStore.CACHED)) m.count = l.size();
                listAdapter.notifyDataSetChanged();
            });
        });
    }

    private void loadChart(final String id, final boolean force) {
        tvTitle.setText(ChartRepo.name(id) + "（加载中…）");
        songs.clear();
        songAdapter.notifyDataSetChanged();
        HttpUtil.POOL.execute(() -> {
            List<MusicBean> l = null;
            String err = null;
            try {
                l = ChartRepo.load(this, id, force);
            } catch (Throwable e) {
                err = e.getMessage();
            }
            final List<MusicBean> fl = l;
            final String fe = err;
            runOnUiThread(() -> {
                if (!id.equals(currentId)) return;
                if (fl == null) {
                    tvTitle.setText(ChartRepo.name(id) + "（获取失败）");
                    toast("榜单获取失败：" + fe);
                    return;
                }
                songs.clear();
                songs.addAll(fl);
                tvTitle.setText(ChartRepo.name(id) + "（" + fl.size() + " 首）");
                songAdapter.notifyDataSetChanged();
                for (PlaylistStore.Meta m : lists) if (m.id.equals(id)) m.count = fl.size();
                listAdapter.notifyDataSetChanged();
            });
        });
    }

    // ================================================================= 启动顺序

    /**
     * 启动时默认播放顺序：从上到下依次尝试，第一个有歌的作为播放队列。
     * 点一项：上移 / 下移 / 移到最前 / 移除；最后一行"添加"把其他歌单加进来。
     */
    private void showStartupOrder() {
        final List<String> order = PlaylistStore.startupOrder(this);
        final List<String> rows = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) rows.add((i + 1) + ". " + PlaylistStore.startupName(this, order.get(i)));
        final List<String> rest = new ArrayList<>();
        for (String id : PlaylistStore.startupCandidates(this)) if (!order.contains(id)) rest.add(id);
        if (!rest.isEmpty()) rows.add("＋ 添加其他歌单…");
        rows.add("↺ 恢复默认（上次的队列 → 我的收藏 → QQ热歌 → QQ新歌 → 酷狗热歌 → 酷狗新歌）");
        new AlertDialog.Builder(this)
                .setTitle("播放列表顺序：开机从上往下第一个有歌的开始播；播放页「切换歌单」按此顺序循环")
                .setItems(rows.toArray(new String[0]), (d, w) -> {
                    if (w < order.size()) {
                        startupItemMenu(order, w);
                    } else if (!rest.isEmpty() && w == order.size()) {
                        addStartupItem(order, rest);
                    } else {
                        PlaylistStore.setStartupOrder(this, PlaylistStore.defaultOrder());
                        showStartupOrder();
                    }
                })
                .setNegativeButton("完成", null)
                .show();
    }

    private void startupItemMenu(final List<String> order, final int i) {
        final String id = order.get(i);
        String[] items = {"上移", "下移", "移到最前", "从顺序里移除"};
        new AlertDialog.Builder(this)
                .setTitle(PlaylistStore.startupName(this, id))
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: if (i > 0) { order.remove(i); order.add(i - 1, id); } break;
                        case 1: if (i < order.size() - 1) { order.remove(i); order.add(i + 1, id); } break;
                        case 2: order.remove(i); order.add(0, id); break;
                        case 3:
                            if (order.size() <= 1) { toast("至少保留一项"); break; }
                            order.remove(i);
                            break;
                    }
                    PlaylistStore.setStartupOrder(this, order);
                    showStartupOrder();
                })
                .setNegativeButton("返回", (d, w) -> showStartupOrder())
                .show();
    }

    private void addStartupItem(final List<String> order, final List<String> rest) {
        String[] names = new String[rest.size()];
        for (int i = 0; i < rest.size(); i++) names[i] = PlaylistStore.startupName(this, rest.get(i));
        new AlertDialog.Builder(this)
                .setTitle("添加到启动顺序（加在最后）")
                .setItems(names, (d, w) -> {
                    order.add(rest.get(w));
                    PlaylistStore.setStartupOrder(this, order);
                    showStartupOrder();
                })
                .setNegativeButton("返回", (d, w) -> showStartupOrder())
                .show();
    }

    // ================================================================= 导入（续）

    private void showImport() {
        String[] items = {
                "粘贴歌单链接（QQ 音乐 / 酷我）",
                "从文件导入（洛雪导出的 .lxmc / .json）",
                "网易云 / 酷狗歌单链接（开发中）",
        };
        new AlertDialog.Builder(this).setTitle("导入歌单")
                .setItems(items, (d, w) -> {
                    if (w == 0) askLink();
                    else if (w == 1) pickLxFile();
                    else toast("网易云 / 酷狗歌单导入还在开发中；可以先在手机洛雪里导出歌单文件，用第 2 种方式导入");
                })
                .setNegativeButton("取消", null).show();
    }

    private void askLink() {
        final EditText et = new EditText(this);
        et.setTextSize(20);
        et.setSingleLine(true);
        et.setHint("QQ 音乐或酷我的歌单分享链接");
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) et.setText(cd.getItemAt(0).getText());
            }
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this).setTitle("粘贴歌单链接").setView(et)
                .setPositiveButton("导入", (d, w) -> {
                    String text = et.getText().toString();
                    if (TxApi.isQqLink(text)) {
                        String pid = TxApi.parsePlaylistId(text);
                        if (pid == null) toast("没识别出 QQ 歌单 ID，请检查链接");
                        else importLink("tx", pid, null);
                    } else {
                        String pid = KuwoApi.parsePlaylistId(text);
                        if (pid == null) toast("没识别出歌单 ID：目前支持 QQ 音乐和酷我的歌单链接");
                        else importLink("kw", pid, null);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** platform: kw / tx；existingId 不为空时为"同步"：覆盖该歌单的歌曲 */
    private void importLink(final String platform, final String pid, final String existingId) {
        if (busy) return;
        busy = true;
        toast("正在获取歌单…");
        HttpUtil.POOL.execute(() -> {
            String err = null;
            PlaylistStore.Playlist p = null;
            try {
                KuwoApi.PlaylistInfo info = "tx".equals(platform) ? TxApi.playlist(pid) : KuwoApi.playlist(pid, 3000);
                p = existingId == null ? new PlaylistStore.Playlist() : PlaylistStore.load(this, existingId);
                if (existingId == null) {
                    p.meta.name = info.name;
                    p.meta.platform = platform;
                    p.meta.extId = pid;
                    p.meta.origin = ("tx".equals(platform) ? "QQ 歌单 " : "酷我歌单 ") + pid;
                }
                p.songs = info.songs;
                PlaylistStore.save(this, p);
            } catch (Throwable e) {
                err = e.getMessage();
                LxLog.e("导入歌单失败 " + platform + " " + pid, e);
            }
            final String fErr = err;
            final PlaylistStore.Playlist fp = p;
            runOnUiThread(() -> {
                busy = false;
                if (fErr != null) { toast("导入失败：" + fErr); return; }
                toast((existingId == null ? "已导入：" : "已同步：") + fp.meta.name + "，" + fp.songs.size() + " 首");
                reloadLists();
                showList(fp.meta.id);
            });
        });
    }

    private void pickLxFile() {
        List<File> roots = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        roots.add(new File(ext, "CarMusic"));
        roots.add(new File(ext, "Download"));
        roots.add(ext);
        for (String p : LocalMusic.USB_PATHS) roots.add(new File(p));
        final List<File> found = new ArrayList<>();
        for (File r : roots) scanFiles(r, 0, found);
        if (found.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("没有找到歌单文件")
                    .setMessage("在手机洛雪里：我的列表 → 导出列表，把文件放到 U 盘根目录，或者：\nadb push xxx.lxmc /sdcard/CarMusic/")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < found.size(); i++) names[i] = found.get(i).getAbsolutePath();
        new AlertDialog.Builder(this).setTitle("选择歌单文件")
                .setItems(names, (d, w) -> importLxFile(found.get(w)))
                .setNegativeButton("取消", null).show();
    }

    private void scanFiles(File dir, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory() || !dir.canRead() || depth > 2 || out.size() > 50) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) scanFiles(f, depth + 1, out);
            else {
                String n = f.getName().toLowerCase(Locale.US);
                if ((n.endsWith(".lxmc") || (n.endsWith(".json") && n.contains("lx"))) && !out.contains(f)) out.add(f);
            }
        }
    }

    private void importLxFile(final File f) {
        if (busy) return;
        busy = true;
        toast("正在导入…");
        HttpUtil.POOL.execute(() -> {
            String msg;
            String firstId = null;
            try {
                LxListImporter.Result r = LxListImporter.importFile(f);
                int total = 0;
                for (PlaylistStore.Playlist p : r.lists) {
                    p.meta.origin = f.getName();
                    PlaylistStore.save(this, p);
                    total += p.songs.size();
                    if (firstId == null) firstId = p.meta.id;
                }
                msg = "导入 " + r.lists.size() + " 个歌单，共 " + total + " 首" + (r.skippedLocal > 0 ? "（跳过本地歌曲 " + r.skippedLocal + " 首）" : "");
            } catch (Throwable e) {
                msg = "导入失败：" + e.getMessage();
                LxLog.e("导入洛雪歌单文件失败 " + f, e);
            }
            final String fm = msg;
            final String fid = firstId;
            runOnUiThread(() -> {
                busy = false;
                toast(fm);
                reloadLists();
                if (fid != null) showList(fid);
            });
        });
    }

    // ================================================================= 适配器

    private View row(View convertView, ViewGroup parent, String title, String sub, boolean highlight) {
        View v = convertView != null ? convertView
                : LayoutInflater.from(this).inflate(R.layout.item_online_song, parent, false);
        ((TextView) v.findViewById(R.id.tvTitle)).setText(title);
        ((TextView) v.findViewById(R.id.tvSub)).setText(sub);
        v.setBackgroundColor(highlight ? 0x332E5E8C : 0x00000000);
        return v;
    }

    private class ListAdapter extends BaseAdapter {
        @Override public int getCount() { return lists.size(); }
        @Override public Object getItem(int p) { return lists.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            PlaylistStore.Meta m = lists.get(p);
            String sub = m.count >= 0 ? m.count + " 首" : "";
            if (!m.origin.isEmpty()) sub += " · " + m.origin;
            return row(cv, parent, m.name, sub, m.id.equals(currentId));
        }
    }

    private class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return songs.size(); }
        @Override public Object getItem(int p) { return songs.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            MusicBean s = songs.get(p);
            MusicBean cur = svc == null ? null : svc.getCurrentSong();
            boolean playing = cur != null && cur.path.equals(s.path);
            StringBuilder sub = new StringBuilder(s.artist == null ? "" : s.artist);
            if (s.online) sub.append(sub.length() > 0 ? " · " : "").append(s.source);
            else sub.append(sub.length() > 0 ? " · " : "").append("本地");
            if (s.duration > 0) sub.append(" · ").append(MusicActivity.fmt(s.duration));
            return row(cv, parent, (playing ? "♪ " : "") + (p + 1) + ". " + s.title, sub.toString(), playing);
        }
    }
}
