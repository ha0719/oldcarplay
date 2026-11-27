package com.mcool.player;

import androidx.multidex.MultiDexApplication;

import com.mcool.player.lx.LxLog;
import com.mcool.player.net.HttpUtil;

public class App extends MultiDexApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        // 最先初始化日志：写文件 + 安装崩溃捕获 + 检查上次是否异常退出
        LxLog.init(this);
        // 只初始化网络（纯 Java，安全）；QuickJS 引擎延迟到进入在线页面/播放在线歌曲时才加载，
        // 这样即使 QuickJS 在 4.4 上崩溃，也不影响本地播放功能
        HttpUtil.init(this);
        com.mcool.player.online.OnlineRepo.init(this);
    }
}
