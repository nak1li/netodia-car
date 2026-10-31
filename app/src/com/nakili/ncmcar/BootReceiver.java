package com.nakili.ncmcar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：车机点火 / 系统启动后拉起应用。
 *
 * 车机场景下这很重要 —— 用户上车就希望音乐已经就绪，而不是先去点图标。
 */
public class BootReceiver extends BroadcastReceiver {
    private static long lastLaunch = 0;
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        // USER_PRESENT 会在开机解锁后再来一次，连着拉两次启动页像是应用重启了。
        // 只认开机广播；短时间内重复的广播也丢掉。
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            long now = System.currentTimeMillis();
            if (now - lastLaunch < 30000) return;
            lastLaunch = now;
            try {
                // 必须过 SplashActivity：那里会重新解析服务器地址。
                // 直接拉 MainActivity 会用上次缓存的公网 IP，换了网络就一路失败。
                Intent i = new Intent(context, SplashActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Exception e) {
                // 车机系统可能限制后台启动 Activity，失败不致命
            }
        }
    }
}
