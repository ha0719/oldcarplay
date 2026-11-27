package com.mcool.player.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;

/** 卡片背景：把所有 tag="card" 的 View 换成按「卡片透明度」生成的半透明圆角背景 */
public final class Cards {

    private Cards() {}

    public static void apply(View root) {
        if (root == null) return;
        Context c = root.getContext();
        int pct = Math.max(10, Math.min(95, HomePrefs.cardAlpha(c)));
        walk(root, c, pct);
    }

    private static void walk(View v, Context c, int pct) {
        if ("card".equals(v.getTag())) v.setBackground(make(c, pct));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), c, pct);
        }
    }

    private static StateListDrawable make(Context c, int pct) {
        float d = c.getResources().getDisplayMetrics().density;
        int a = Math.round(255 * pct / 100f);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.argb(a, 0, 0, 0));
        normal.setCornerRadius(20 * d);
        normal.setStroke(Math.max(1, Math.round(d)), 0x33FFFFFF);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.argb(Math.min(255, a + 50), 0, 0, 0));
        pressed.setCornerRadius(20 * d);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }
}
