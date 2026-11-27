package com.mcool.player.home;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.mcool.player.R;
import com.mcool.player.lx.LxLog;


/**
 * 左侧竖向 Dock（CarPlay 样式）：时间日期 / 3 个自定义应用 / 桌面设置 / 8 个小图标（全部应用）。
 * 桌面和全屏播放页共用（布局 layout_dock.xml）。时钟每分钟刷新一次，页面不可见时停止。
 */
public final class Dock {

    public interface Host {
        /** 长按（或点空位）自定义应用位 */
        void onSlotEdit(int slot);

        void onSettings();
    }

    private final Activity a;
    private final Host host;
    private final ImageView[] slots = new ImageView[HomePrefs.SLOT_COUNT];
    private final LinearLayout apps;
    private boolean gridLoaded;

    public Dock(Activity a, Host host) {
        this.a = a;
        this.host = host;
        slots[0] = a.findViewById(R.id.dockSlot0);
        slots[1] = a.findViewById(R.id.dockSlot1);
        slots[2] = a.findViewById(R.id.dockSlot2);
        apps = a.findViewById(R.id.dockApps);
        a.findViewById(R.id.dockSettings).setOnClickListener(v -> host.onSettings());
        apps.setOnClickListener(v -> a.startActivity(new Intent(a, AllAppsActivity.class)));
    }

    public void onResume() {
        refresh();
    }

    public void onPause() {
    }

    /** 重新读取 3 个自定义应用位 */
    public void refresh() {
        for (int i = 0; i < slots.length; i++) {
            final int slot = i;
            ImageView iv = slots[i];
            String c = HomePrefs.slot(a, i);
            ComponentName cn = c.isEmpty() ? null : ComponentName.unflattenFromString(c);
            CharSequence label = cn == null ? null : HomePrefs.label(a, cn);
            if (cn == null || label == null) {
                iv.setImageResource(android.R.drawable.ic_input_add);
                iv.setOnClickListener(v -> host.onSlotEdit(slot));
            } else {
                final ComponentName fcn = cn;
                iv.setImageDrawable(HomePrefs.slotDrawable(a, i, cn));
                iv.setOnClickListener(v -> launch(a, fcn));
            }
            iv.setOnLongClickListener(v -> {
                host.onSlotEdit(slot);
                return true;
            });
        }
        if (!gridLoaded) buildGrid();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    /** 底部 2×4 白色圆角小方块（只是"全部应用"按钮的样子，不加载真实图标） */
    private void buildGrid() {
        gridLoaded = true;
        apps.removeAllViews();
        android.graphics.drawable.GradientDrawable sq = new android.graphics.drawable.GradientDrawable();
        sq.setColor(0xE6FFFFFF);
        sq.setCornerRadius(dp(3));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 4; k++) {
                android.view.View v = new android.view.View(a);
                v.setBackground(sq.getConstantState().newDrawable().mutate());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(11), dp(11));
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(v, lp);
            }
            apps.addView(row);
        }
    }

    public static void launch(Context c, ComponentName cn) {
        if (cn == null) return;
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception e) {
            LxLog.w("打开应用失败 " + cn + "：" + e);
            Toast.makeText(c, "无法打开，长按可重新选择", Toast.LENGTH_LONG).show();
        }
    }

    /** 从其他页面打开桌面设置：回到桌面并弹出设置 */
    public static void openHomeSettings(Activity a) {
        a.startActivity(new Intent(a, LauncherActivity.class)
                .putExtra(LauncherActivity.EXTRA_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
}
