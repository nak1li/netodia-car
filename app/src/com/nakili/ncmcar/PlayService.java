package com.nakili.ncmcar;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;

/**
 * 后台播放的前台服务。
 *
 * 为什么必须有：车机场景下用户切到导航（DiPlay / 高德）时音乐要接着放。
 * 只有 Activity 的话，进程一进后台就是 cached 状态，车机内存紧张时被系统
 * 直接收掉，歌就断了。前台服务把进程提到 fg-service 优先级，并在通知栏
 * 给出歌名 + 上一首/播放暂停/下一首。
 *
 * 不在这里持有 Player 引用：Player 是进程级单例，服务只负责
 * 1) 把进程钉住 2) 轮询状态刷新通知 3) 接收通知按钮的动作。
 */
public class PlayService extends Service {

    private static final int NOTIF_ID = 1001;

    static final String A_TOGGLE = "com.nakili.ncmcar.action.TOGGLE";
    static final String A_NEXT = "com.nakili.ncmcar.action.NEXT";
    static final String A_PREV = "com.nakili.ncmcar.action.PREV";
    static final String A_STOP = "com.nakili.ncmcar.action.STOP";

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean foreground = false;
    private String lastSig = "";
    private int idleTicks = 0;

    /** 播放要开始时调一下，保证服务在跑 */
    public static void ensure(Context c) {
        try {
            c.startService(new Intent(c, PlayService.class));
        } catch (Exception e) {
            // 被系统限制后台启动时不影响播放本身
        }
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

        startForegroundSafely(buildNotification(p));
        main.removeCallbacks(tick);
        main.post(tick);
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Player p = Player.get(PlayService.this);
            Song s = p.getCurrent();
            if (s == null) {
                if (++idleTicks >= 5) {         // 5 秒没有曲目就退出，别留个空通知
                    stopForeground(true);
                    foreground = false;
                    stopSelf();
                    return;
                }
            } else if (p.getState() == Player.STATE_IDLE) {
                // 播完了（或用户点了停止）：别让前台服务和不可清除通知一直挂着
                if (++idleTicks >= 3) {
                    stopForeground(true);
                    foreground = false;
                    stopSelf();
                    return;
                }
            } else {
                idleTicks = 0;
                String sig = s.id + "|" + p.getState();
                if (!sig.equals(lastSig)) {
                    lastSig = sig;
                    Notification n = buildNotification(p);
                    if (foreground) {
                        try { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                                .notify(NOTIF_ID, n); } catch (Exception e) { }
                    } else {
                        startForegroundSafely(n);
                    }
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    private void startForegroundSafely(Notification n) {
        try {
            startForeground(NOTIF_ID, n);
            foreground = true;
        } catch (Exception e) { }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(Player p) {
        Song s = p.getCurrent();
        boolean playing = p.getState() == Player.STATE_PLAYING;

        RemoteViews v = new RemoteViews(getPackageName(), R.layout.notif_player);
        v.setTextViewText(R.id.n_title, s == null ? "未在播放" : s.name);
        v.setTextViewText(R.id.n_sub, s == null ? "" : (s.artist == null ? "" : s.artist));
        v.setImageViewResource(R.id.n_play, playing ? R.drawable.ic_pause : R.drawable.ic_play);
        v.setOnClickPendingIntent(R.id.n_prev, pi(A_PREV));
        v.setOnClickPendingIntent(R.id.n_play, pi(A_TOGGLE));
        v.setOnClickPendingIntent(R.id.n_next, pi(A_NEXT));
        v.setOnClickPendingIntent(R.id.n_stop, pi(A_STOP));

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_note)
                .setContentTitle(s == null ? "网易云" : s.name)
                .setContentText(s == null ? "" : s.artist)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setContent(v);
        Notification n = b.build();
        n.flags |= Notification.FLAG_NO_CLEAR;
        return n;
    }

    private PendingIntent pi(String action) {
        Intent i = new Intent(this, PlayService.class);
        i.setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        super.onDestroy();
    }
}
