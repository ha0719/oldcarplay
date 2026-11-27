package com.mcool.player;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mcool.player.lx.LxEngine;
import com.mcool.player.lx.LxLog;
import com.mcool.player.lx.OnlineSettings;
import com.mcool.player.lx.SourceManager;
import com.mcool.player.net.HttpUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 音乐源管理 + 网络/引擎自检 + 日志查看（上车排查用） */
public class SourceActivity extends AppCompatActivity {

    /** 网络与 TLS 自检地址（仅公共基础服务，用于排查 Android 4.4 联网与 Conscrypt TLS 状态） */
    private static final String[] TEST_URLS = {
            "https://www.baidu.com/",
            "https://api.open-meteo.com/v1/forecast?latitude=31.23&longitude=121.47&current=temperature_2m",
            "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=54909&y=26780&z=16",
            "https://github.com/",
    };

    /** 车机上常见的 U 盘挂载点（来自 player-final） */
    private static final String[] USB_PATHS = {
            "/mnt/usb_storage", "/mnt/usbhost", "/mnt/usbhost1", "/mnt/udisk", "/storage/usb0", "/storage/usb1",
            "/storage/udisk", "/storage/usbdisk", "/mnt/media_rw/udisk", "/mnt/media_rw/usb_storage",
    };

    private TextView tvEngine, tvLog, tvTls;
    private final List<SourceManager.Source> items = new ArrayList<>();
    private SrcAdapter adapter;
    private final LxEngine.StateListener stateListener = this::refreshEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sources);
        tvEngine = findViewById(R.id.tvEngine);
        tvLog = findViewById(R.id.tvLog);
        tvTls = findViewById(R.id.tvTls);

        ListView lv = findViewById(R.id.lvSources);
        adapter = new SrcAdapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            SourceManager.Source s = items.get(pos);
            SourceManager.get(this).setActive(s.id);
            Toast.makeText(this, "切换到：" + s.name, Toast.LENGTH_SHORT).show();
            reload();
        });
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            final SourceManager.Source s = items.get(pos);
            if (s.builtin) {
                toast("内置音源不能删除，可以切换到其他音源");
                return true;
            }
            new AlertDialog.Builder(this)
                    .setTitle(s.name)
                    .setMessage("删除这个音源？")
                    .setPositiveButton("删除", (d, w) -> {
                        SourceManager.get(this).delete(s.id);
                        reload();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPresets).setOnClickListener(v -> {
            toast("正在下载预设音源…");
            SourceManager.get(this).importPresets((r, err) -> {
                toast(r + (err == null ? "" : "\n" + err));
                reload();
            });
        });
        findViewById(R.id.btnUrl).setOnClickListener(v -> showUrlDialog());
        findViewById(R.id.btnFile).setOnClickListener(v -> showFileDialog());
        findViewById(R.id.btnUpdate).setOnClickListener(v -> {
            toast("正在更新…");
            SourceManager.get(this).updateAll((r, err) -> {
                toast(r + (err == null ? "" : "\n" + err));
                reload();
            });
        });
        findViewById(R.id.btnNetTest).setOnClickListener(v -> netTest());
        findViewById(R.id.btnJsTest).setOnClickListener(v -> {
            toast("引擎自检中…");
            LxEngine.get(this).selfTest((ok, data, msg) -> {
                toast((ok ? "✅ QuickJS 正常：" : "❌ QuickJS 异常：") + msg);
                refreshLog();
            });
        });
        findViewById(R.id.btnRefreshLog).setOnClickListener(v -> refreshLog());
        findViewById(R.id.btnRefreshLog).setOnLongClickListener(v -> { showLatestCrash(); return true; });
        findViewById(R.id.btnClearLog).setOnClickListener(v -> { LxLog.clear(); refreshLog(); });

        CheckBox cbCon = findViewById(R.id.cbConscrypt);
        CheckBox cbTrust = findViewById(R.id.cbTrustAll);
        cbCon.setChecked(OnlineSettings.useConscrypt(this));
        cbTrust.setChecked(OnlineSettings.trustAll(this));
        cbCon.setOnCheckedChangeListener((b, c) -> { OnlineSettings.setUseConscrypt(this, c); HttpUtil.init(this); refreshEngine(); });
        cbTrust.setOnCheckedChangeListener((b, c) -> { OnlineSettings.setTrustAll(this, c); HttpUtil.init(this); refreshEngine(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        LxEngine.get(this).addListener(stateListener);
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LxEngine.get(this).removeListener(stateListener);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
        refreshLog();
    }

    private void reload() {
        items.clear();
        items.addAll(SourceManager.get(this).list());
        adapter.notifyDataSetChanged();
        refreshEngine();
        refreshLog();
    }

    private void refreshEngine() {
        LxEngine e = LxEngine.get(this);
        String alert = e.getUpdateAlert();
        String crash = LxLog.lastCrash();
        tvEngine.setText("当前：" + e.getStateMsg() + (alert == null ? "" : "\n更新提示：" + alert)
                + (crash == null ? "" : "\n⚠ " + crash + "（长按「刷新日志」查看）"));
        File d = LxLog.logDir();
        tvTls.setText("网络：" + HttpUtil.tlsInfo() + "\n日志文件：" + (d == null ? "无法写入" : d.getAbsolutePath()));
        refreshLog();
    }

    /** 显示最新的崩溃报告 */
    private void showLatestCrash() {
        File d = LxLog.logDir();
        File latest = null;
        if (d != null) {
            File[] fs = d.listFiles((dd, n) -> n.startsWith("crash-"));
            if (fs != null) for (File f : fs) if (latest == null || f.getName().compareTo(latest.getName()) > 0) latest = f;
        }
        String text;
        if (latest == null) {
            text = LxLog.lastCrash() != null ? LxLog.lastCrash() : "没有崩溃记录";
        } else {
            try {
                text = latest.getName() + "\n\n" + new String(HttpUtil.readAll(new java.io.FileInputStream(latest)), "UTF-8");
                if (text.length() > 20000) text = text.substring(0, 20000) + "\n...";
            } catch (Exception ex) {
                text = "读取失败：" + ex.getMessage();
            }
        }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextIsSelectable(true);
        tv.setPadding(16, 16, 16, 16);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("崩溃报告")
                .setView(sv)
                .setPositiveButton("关闭", (dd, w) -> { LxLog.clearLastCrash(); refreshEngine(); })
                .show();
    }

    private void refreshLog() {
        if (tvLog != null) tvLog.setText(LxLog.dump());
    }

    private void showUrlDialog() {
        final EditText et = new EditText(this);
        et.setTextSize(20);
        et.setSingleLine(true);
        String pre = "https://";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {
                    String t = cd.getItemAt(0).getText().toString().trim();
                    if (t.startsWith("http")) pre = t;
                }
            }
        } catch (Exception ignored) {}
        et.setText(pre);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("远程 JS：输入音源脚本链接（.js）")
                .setView(et)
                .setPositiveButton("导入", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (!url.startsWith("http")) { toast("链接需以 http 开头"); return; }
                    toast("下载中…");
                    SourceManager.get(this).importFromUrl(url, (s, err) -> {
                        if (s != null) {
                            SourceManager.get(this).setActive(s.id);
                            toast("导入成功：" + s.name);
                        } else toast("导入失败：" + err);
                        reload();
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 扫描 U 盘 / 存储里的 .js 文件（推荐放在 U 盘或 /sdcard/CarMusic/sources/ 下） */
    private void showFileDialog() {
        List<File> roots = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        roots.add(new File(ext, "CarMusic/sources"));
        roots.add(new File(ext, "Download"));
        roots.add(ext);
        for (String p : USB_PATHS) roots.add(new File(p));
        final List<File> found = new ArrayList<>();
        for (File r : roots) scanJs(r, 0, found);
        if (found.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("没有找到 .js 音源文件")
                    .setMessage("请把音源脚本放到 U 盘根目录，或用 adb 推送：\nadb push xxx.js /sdcard/CarMusic/sources/")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < found.size(); i++) names[i] = found.get(i).getAbsolutePath();
        new AlertDialog.Builder(this)
                .setTitle("选择音源文件")
                .setItems(names, (d, which) -> SourceManager.get(this).importFromFile(found.get(which), (s, err) -> {
                    if (s != null) {
                        SourceManager.get(this).setActive(s.id);
                        toast("导入成功：" + s.name);
                    } else toast("导入失败：" + err);
                    reload();
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void scanJs(File dir, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory() || !dir.canRead() || depth > 2 || out.size() > 50) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) scanJs(f, depth + 1, out);
            else if (f.getName().toLowerCase().endsWith(".js") && f.length() < 9 * 1024 * 1024 && !out.contains(f)) out.add(f);
        }
    }

    private void netTest() {
        toast("网络自检开始，结果写到右侧日志");
        LxLog.i("===== 网络自检开始（" + HttpUtil.tlsInfo() + "）=====");
        HttpUtil.POOL.execute(() -> {
            for (String u : TEST_URLS) {
                long t0 = System.currentTimeMillis();
                String r;
                try {
                    HttpUtil.Response resp = HttpUtil.request(u, "GET", null, null, 15000);
                    r = "HTTP " + resp.code + "，" + resp.body.length + " 字节";
                } catch (Throwable e) {
                    r = "失败 " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                LxLog.i("自检 " + (System.currentTimeMillis() - t0) + "ms  " + r + "  ← " + u);
                runOnUiThread(this::refreshLog);
            }
            LxLog.i("===== 网络自检结束 =====");
            runOnUiThread(this::refreshLog);
        });
    }

    private class SrcAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(SourceActivity.this).inflate(R.layout.item_online_song, parent, false);
            SourceManager.Source s = items.get(position);
            boolean active = s.id.equals(SourceManager.get(SourceActivity.this).getActiveId());
            ((TextView) v.findViewById(R.id.tvTitle)).setText((active ? "● " : "○ ") + s.name
                    + (s.version.isEmpty() ? "" : "  v" + s.version) + (s.author.isEmpty() ? "" : "  by " + s.author));
            ((TextView) v.findViewById(R.id.tvSub)).setText(s.builtin ? "内置（APK 自带）"
                    : s.url.isEmpty() ? "本地文件导入" : "远程：" + s.url);
            v.setBackgroundColor(active ? 0x332E5E8C : 0x00000000);
            return v;
        }
    }
}
