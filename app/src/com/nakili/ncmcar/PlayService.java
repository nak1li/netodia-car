package com.nakili.ncmcar;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 后台播放服务。
 *
 * 为什么要有：车机场景下用户切到导航（DiPlay / 高德）时音乐要接着放。
 * 只有 Activity 的话，进程一进后台就是 cached 状态，车机内存紧张时被系统
 * 直接收掉，歌就断了。
 *
 * 为什么**不**用 startForeground：车主明确要求「车机的下拉菜单里面不要显示
 * 音乐软件的播放状态」。Android 4.4 上前台服务必须挂一条通知，且没法隐藏
 * （VISIBILITY_SECRET 是 API 21 才有的），所以只能退回到普通的 started
 * service —— 不给通知，下拉里干干净净。
 *
 * 代价与兜底：没有 fg-service 优先级，进程只是普通后台服务 + 正在出声
 * （MediaPlayer 持音频焦点时进程本身是 perceptible，被杀的概率不高）。
 * 服务这里额外做两件事：
 *  1) START_STICKY，被回收后系统会尝试重建；
 *  2) 持一个 PARTIAL_WAKE_LOCK，免得息屏/低电时被挂起断音。
 */
public class PlayService extends Service {

    /** 老版本用 startForeground 挂过通知；升级后那条通知会残留，启动时清掉 */
    static final int LEGACY_NOTIF_ID = 1001;

    static void clearLegacyNotification(Context c) {
        try {
            ((android.app.NotificationManager) c.getSystemService(NOTIFICATION_SERVICE))
                    .cancel(LEGACY_NOTIF_ID);
        } catch (Exception e) { }
    }

    static final String A_TOGGLE = "com.nakili.ncmcar.action.TOGGLE";
    static final String A_NEXT = "com.nakili.ncmcar.action.NEXT";
    static final String A_PREV = "com.nakili.ncmcar.action.PREV";
    static final String A_STOP = "com.nakili.ncmcar.action.STOP";

    private final Handler main = new Handler(Looper.getMainLooper());
    private int idleTicks = 0;
    private android.os.PowerManager.WakeLock wake;

    /** 播放要开始时调一下，保证服务在跑 */
    public static void ensure(Context c) {
        try {
            c.startService(new Intent(c, PlayService.class));
        } catch (Exception e) {
            // 被系统限制后台启动时不影响播放本身
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        clearLegacyNotification(this);
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Player p = Player.get(this);

        if (A_TOGGLE.equals(action)) p.toggle();
        else if (A_NEXT.equals(action)) p.next();
        else if (A_PREV.equals(action)) p.prev();
        else if (A_STOP.equals(action)) {
            p.stop();
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireWake();
        main.removeCallbacks(tick);
        main.post(tick);
        return START_STICKY;
    }

    /** 出声期间别让系统把进程挂起；一停就放掉，不白耗电 */
    private void acquireWake() {
        try {
            if (wake == null) {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "ncmcar:play");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire(60 * 60 * 1000L);   // 1 小时封顶
        } catch (Exception e) { }
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception e) { }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Player p = Player.get(PlayService.this);
            Song s = p.getCurrent();
            boolean alive = s != null && p.getState() != Player.STATE_IDLE;
            if (!alive) {
                if (++idleTicks >= 5) {      // 5 秒没有在放就退出，别留个空服务
                    releaseWake();
                    stopSelf();
                    return;
                }
            } else {
                idleTicks = 0;
                acquireWake();
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        releaseWake();
        super.onDestroy();
    }
}
