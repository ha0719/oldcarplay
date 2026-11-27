package com.mcool.player.home;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mcool.player.R;
import com.mcool.player.net.HttpUtil;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 全部应用。两种模式：
 * - 普通：点图标打开应用
 * - 选择（EXTRA_PICK=true）：点图标返回该应用给桌面，用于设置常用应用/导航
 */
public class AllAppsActivity extends AppCompatActivity {

    public static final String EXTRA_PICK = "pick";
    public static final String EXTRA_TITLE = "title";
    public static final String RESULT_COMPONENT = "component";

    private static final class App {
        ComponentName cn;
        String label;
        Drawable icon;
    }

    private final List<App> apps = new ArrayList<>();
    private Adapter adapter;
    private boolean pick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_apps);
        pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        ((TextView) findViewById(R.id.tvTitle)).setText(title != null ? title : (pick ? "选择应用" : "全部应用"));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        View clear = findViewById(R.id.btnClear);
        if (pick) {
            clear.setVisibility(View.VISIBLE);
            clear.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra(RESULT_COMPONENT, ""));
                finish();
            });
        }
        GridView g = findViewById(R.id.grid);
        adapter = new Adapter();
        g.setAdapter(adapter);
        g.setOnItemClickListener((p, v, pos, id) -> {
            App a = apps.get(pos);
            if (pick) {
                setResult(RESULT_OK, new Intent().putExtra(RESULT_COMPONENT, a.cn.flattenToString()));
                finish();
            } else {
                launch(a.cn);
            }
        });
        load();
    }

    private void launch(ComponentName cn) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void load() {
        HttpUtil.POOL.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            final List<App> list = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                App a = new App();
                a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                CharSequence l = ri.loadLabel(pm);
                a.label = l == null ? ri.activityInfo.packageName : l.toString();
                try { a.icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
                list.add(a);
            }
            final Collator col = Collator.getInstance(Locale.CHINA);
            Collections.sort(list, (x, y) -> col.compare(x.label, y.label));
            runOnUiThread(() -> {
                apps.clear();
                apps.addAll(list);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(AllAppsActivity.this).inflate(R.layout.item_app, parent, false);
            App a = apps.get(position);
            ((ImageView) v.findViewById(R.id.ivIcon)).setImageDrawable(a.icon);
            ((TextView) v.findViewById(R.id.tvLabel)).setText(a.label);
            return v;
        }
    }
}
