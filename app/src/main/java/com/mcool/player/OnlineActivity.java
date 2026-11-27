package com.mcool.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
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
import com.mcool.player.online.KuwoApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 在线搜索页（demo：酷我搜索 + 洛雪音源取链） */
public class OnlineActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 30;

    private EditText etKeyword;
    private TextView tvStatus, tvPage;
    private Button btnQuality;
    private final List<MusicBean> results = new ArrayList<>();
    private SongAdapter adapter;
    private int page = 1;
    private String keyword = "";
    private boolean searching;

    private MusicService svc;
    private boolean bound;
    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            svc = ((MusicService.MusicBinder) service).getService();
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            bound = false;
        }
    };

    private final LxEngine.StateListener stateListener = this::refreshStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online);

        etKeyword = findViewById(R.id.etKeyword);
        tvStatus = findViewById(R.id.tvStatus);
        tvPage = findViewById(R.id.tvPage);
        btnQuality = findViewById(R.id.btnQuality);
        ListView lv = findViewById(R.id.lvResults);
        adapter = new SongAdapter();
        lv.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSearch).setOnClickListener(v -> search(etKeyword.getText().toString().trim(), 1));
        etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search(etKeyword.getText().toString().trim(), 1);
                return true;
            }
            return false;
        });
        findViewById(R.id.btnPrevPage).setOnClickListener(v -> { if (page > 1) search(keyword, page - 1); });
        findViewById(R.id.btnNextPage).setOnClickListener(v -> { if (!keyword.isEmpty()) search(keyword, page + 1); });
        findViewById(R.id.btnSources).setOnClickListener(v -> startActivity(new Intent(this, SourceActivity.class)));
        findViewById(R.id.btnNowPlaying).setOnClickListener(v -> goPlayer());
        btnQuality.setOnClickListener(v -> {
            String cur = OnlineSettings.quality(this);
            String[] qs = OnlineSettings.QUALITIES;
            int i = 0;
            for (int k = 0; k < qs.length; k++) if (qs[k].equals(cur)) i = k;
            OnlineSettings.setQuality(this, qs[(i + 1) % qs.length]);
            updateQualityBtn();
        });
        lv.setOnItemClickListener((parent, view, position, id) -> playFrom(position));

        bindService(new Intent(this, MusicService.class), conn, Context.BIND_AUTO_CREATE);
        SourceManager.get(this).ensureActiveLoaded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LxEngine.get(this).addListener(stateListener);
        refreshStatus();
        updateQualityBtn();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LxEngine.get(this).removeListener(stateListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            try { unbindService(conn); } catch (Exception ignored) {}
            bound = false;
        }
    }

    private void updateQualityBtn() {
        btnQuality.setText("音质 " + OnlineSettings.quality(this));
    }

    private void refreshStatus() {
        if (searching) return;
        LxEngine e = LxEngine.get(this);
        String msg = e.getStateMsg();
        if (e.getState() == LxEngine.ST_IDLE && SourceManager.get(this).list().isEmpty()) {
            msg = "还没有音源：点右上角「音乐源」导入（远程JS / U盘 / 预设）";
        }
        tvStatus.setText("音源：" + msg + "　|　搜索：酷我　|　" + HttpUtil.tlsInfo());
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etKeyword.getWindowToken(), 0);
    }

    private void search(final String kw, final int p) {
        if (kw.isEmpty()) {
            Toast.makeText(this, "请输入歌名或歌手", Toast.LENGTH_SHORT).show();
            return;
        }
        if (searching) return;
        hideKeyboard();
        searching = true;
        tvStatus.setText("搜索中：" + kw + " 第 " + p + " 页…");
        HttpUtil.POOL.execute(() -> {
            List<MusicBean> list = null;
            String err = null;
            long t0 = System.currentTimeMillis();
            try {
                list = KuwoApi.search(kw, p, PAGE_SIZE);
            } catch (Throwable e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
                LxLog.e("搜索失败 " + kw, e);
            }
            final long cost = System.currentTimeMillis() - t0;
            final List<MusicBean> fList = list;
            final String fErr = err;
            runOnUiThread(() -> {
                searching = false;
                if (isFinishing()) return;
                if (fErr != null) {
                    refreshStatus();
                    Toast.makeText(this, "搜索失败：" + fErr, Toast.LENGTH_LONG).show();
                    return;
                }
                if (fList.isEmpty() && p > 1) {
                    Toast.makeText(this, "没有更多了", Toast.LENGTH_SHORT).show();
                    refreshStatus();
                    return;
                }
                keyword = kw;
                page = p;
                results.clear();
                results.addAll(fList);
                adapter.notifyDataSetChanged();
                tvPage.setText("第 " + page + " 页");
                refreshStatus();
                LxLog.i("搜索 " + kw + " 第" + p + "页：" + fList.size() + " 首，" + cost + "ms");
                if (fList.isEmpty()) Toast.makeText(this, "没有搜到结果", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** 搜索结果：插到当前歌后面并马上播放，不清空原来的播放队列 */
    private void playFrom(int position) {
        if (svc == null) {
            Toast.makeText(this, "播放服务未连接，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        svc.user().insertNextAndPlay(results.get(position));
        goPlayer();
    }

    /** 回到音乐页（本页是从音乐页打开的） */
    private void goPlayer() {
        Intent i = new Intent(this, com.mcool.player.music.MusicActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    /** 方控在本页面前台时也能用（与 MainActivity 一致） */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (svc != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_NEXT: svc.onMediaKey(keyCode); return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: svc.onMediaKey(keyCode); return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    svc.onMediaKey(keyCode);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY: svc.onMediaKey(keyCode); return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: svc.pausePlayer(); return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int position) { return results.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(OnlineActivity.this).inflate(R.layout.item_online_song, parent, false);
            MusicBean s = results.get(position);
            ((TextView) v.findViewById(R.id.tvTitle)).setText(((page - 1) * PAGE_SIZE + position + 1) + ". " + s.title);
            long sec = s.duration / 1000;
            StringBuilder sub = new StringBuilder(s.artist);
            if (!s.album.isEmpty()) sub.append(" · ").append(s.album);
            sub.append(" · ").append(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
            if (!s.types.isEmpty()) sub.append(" · ").append(android.text.TextUtils.join("/", s.types));
            ((TextView) v.findViewById(R.id.tvSub)).setText(sub.toString());
            return v;
        }
    }
}
