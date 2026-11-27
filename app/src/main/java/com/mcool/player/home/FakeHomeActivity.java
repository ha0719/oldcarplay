package com.mcool.player.home;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * 只用来"重新弹出桌面选择框"的辅助组件，平时是禁用的。
 * Android 4.4 没有设置默认桌面的接口；临时启用一个新的 HOME 组件后，系统发现候选桌面变了，
 * 会作废原来的默认设置，按 Home 时重新弹出选择框，用户选「车机桌面」→「始终」即可。
 * 万一被选中：禁用自己并转到真正的桌面。
 */
public class FakeHomeActivity extends Activity {

    public static ComponentName component(android.content.Context c) {
        return new ComponentName(c, FakeHomeActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getPackageManager().setComponentEnabledSetting(component(this),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
        }
        startActivity(new Intent(this, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
