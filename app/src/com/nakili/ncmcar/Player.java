package com.nakili.ncmcar;

/*
 * Netodia Car — 网易云音乐车机播放器
 * Copyright (c) 2026 nak1li
 *
 * Required Notice: Copyright (c) 2026 nak1li (Netodia Car). This project is licensed for
 * non-commercial use only. See LICENSE for details.
 *
 * 授权：PolyForm Noncommercial License 1.0.0 —— 仅限非商业用途。
 * 商业使用（含预装售卖、SaaS 收费、集成进商业产品）须先取得著作权人书面授权。
 */

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放引擎。
 *
 * 用系统 MediaPlayer（不引 ExoPlayer/Media3）：
 * - Media3 最低 API 21，T3 是 API 19，直接排除
 * - ExoPlayer 2.x 带 native so，APK 会涨 2-3MB，与「占用小」冲突
 * - 车机只要能稳定播 mp3/aac 并且 seek 跟手，系统播放器足够
 *
 * 播放源优先级：本地缓存文件 → 远程流（服务端代理）
 * 命中缓存时播本地文件：不占流量、seek 瞬时、不受网络抖动影响。
 *
 * 并发约定（每条都对应过一次真实事故）：
 * - 只有主线程能碰 MediaPlayer 句柄（ticker / prepare 回调 / 控制方法都在 main）
 * - prepGen 是「播放代次」：连点下一首时，旧代次的 prepare 结果必须丢弃，
 *   否则第二个 MediaPlayer 会覆盖字段，第一个既不被 release 又会自己 start —— 双声部
 * - release() 之后到达的 prepare 一律丢弃，否则 Activity 已经销毁了还在放歌
 */
public class Player {

    public interface Listener {
        void onTrackChanged(Song s, int index);
        void onStateChanged(int state);
        void onProgress(int positionMs, int durationMs);
        void onError(String msg);
        void onQueueChanged();
        /**
         * 队列播到底了。返回 true = 上层会追加曲目并接着播（私人 FM 自动续批），
         * 这里就不要停；返回 false = 就地停住。
         */
        boolean onQueueEnded();
    }

    public static final int STATE_IDLE = 0;
    public static final int STATE_BUFFERING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_PAUSED = 3;
    public static final int STATE_COMPLETED = 4;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private Listener listener;

    /** 进程内唯一实例：Activity 被重建（换密度/多窗口）时不能把发声的句柄一起丢掉 */
    private static Player instance;

    private final AudioManager am;
    /** 被导航播报/来电打断过：GAIN 回来要自己续播，否则用户以为死机了 */
    private boolean pausedByFocus = false;
    private boolean ducked = false;
    /** 用户自己按的暂停：GAIN 回来绝不能自动播（否则导航播报结束时歌自己响了） */
    private boolean userPaused = false;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            MediaPlayer p = mp;
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    // 永久失去（别的播放器抢了）：停，不自动续。
                    // 注意：DUCK 之后可能直接来 LOSS（中间没有 GAIN），
                    // 这里必须顺手把音量还原，否则这首一直 20% 音量。
                    pausedByFocus = false;
                    ducked = false;
                    if (p != null) {
                        try { p.setVolume(1f, 1f); } catch (Exception e) { }
                        if (state == STATE_PLAYING) {
                            try { p.pause(); setState(STATE_PAUSED); } catch (Exception e) { }
                        }
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    android.util.Log.i("Player", "audiofocus LOSS_TRANSIENT pause, state=" + state);
                    // 导航播报、来电：暂停，结束后续播。
                    // BUFFERING 阶段 pause() 会抛异常，只记账，等 onPrepared 别自己开播
                    // 没有正在播（或用户自己暂停了）就别记账，否则 GAIN 回来会把它救活
                    if (!userPaused && (state == STATE_PLAYING || state == STATE_BUFFERING)) {
                        pausedByFocus = true;
                    }
                    if (p != null && state == STATE_PLAYING) {
                        try { p.pause(); setState(STATE_PAUSED); } catch (Exception e) { }
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // 允许压低：不打断节奏，音量降到 20%
                    if (p != null) {
                        try { p.setVolume(0.2f, 0.2f); ducked = true; } catch (Exception e) { }
                    }
                    android.util.Log.i("Player", "audiofocus DUCK -> volume 0.2, state=" + state);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    android.util.Log.i("Player", "audiofocus GAIN ducked=" + ducked
                            + " pausedByFocus=" + pausedByFocus + " state=" + state);
                    if (ducked) {
                        ducked = false;
                        try { if (p != null) p.setVolume(1f, 1f); } catch (Exception e) { }
                    }
                    if (pausedByFocus && !userPaused) {
                        pausedByFocus = false;
                        if (p != null && state == STATE_PAUSED) {
                            try { p.start(); setState(STATE_PLAYING); } catch (Exception e) { }
                        }
                    } else {
                        pausedByFocus = false;
                    }
                    break;
            }
        }
    };

    private List<Song> queue = new ArrayList<Song>();
    private int index = -1;
    private int state = STATE_IDLE;
    private String quality = "exhigh";

    /** 播放代次：每次 startCurrent / stop 递增，旧的异步结果靠它作废 */
    private int prepGen = 0;
    private boolean released = false;
    /** prepare 落地后要不要立刻开播（BUFFERING 期间用户按了暂停就置 false） */
    private boolean startWhenPrepared = true;
    /**
     * 断点播放：prepare 落地后要跳到的位置（毫秒）。
     * 由 MainActivity 恢复上次进度时设置，onPrepared 里消费一次就清零。
     */
    private int resumeMs = 0;
    /** 上次落盘时间：播放中每 5 秒存一次「听到哪」 */
    private long lastCursorSaveAt = 0;

    // 进度轮询
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            MediaPlayer p = mp;
            if (p != null && state == STATE_PLAYING) {
                try {
                    int pos = p.getCurrentPosition();
                    int dur = p.getDuration();
                    // 流式播放时 MediaPlayer 常常给 0（上游没带 Content-Length），
                    // 用接口里的歌曲时长兜底，否则进度条永远是空的、拖动也没意义
                    if (dur <= 0) {
                        Song cs = getCurrent();
                        if (cs != null) dur = cs.duration;
                    }
                    if (listener != null) listener.onProgress(pos, dur);
                    // 断点播放：每 5 秒把「听到哪」落盘（车机断电时没有生命周期回调可用）
                    long now = System.currentTimeMillis();
                    if (now - lastCursorSaveAt > 5000) {
                        lastCursorSaveAt = now;
                        ResumeState.saveCursor(ctx, index, pos, true);
                    }
                } catch (Exception e) { }
            }
            if (state == STATE_PLAYING && !released) main.postDelayed(this, 500);
        }
    };

    public Player(Context c) {
        this.ctx = c.getApplicationContext();
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        // ticker 不在这里启动：只在 PLAYING 时按需挂（见 setState）
    }

    /** 取进程内唯一实例。Activity 重建后复用，正在放的歌不断。 */
    public static synchronized Player get(Context c) {
        if (instance == null || instance.released) instance = new Player(c);
        return instance;
    }

    /** 界面彻底退出（按返回/home 后关闭）时才真正释放 */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.release();
            instance = null;
        }
    }

    /** 新的 Activity 接手：把当前曲目/状态/进度补给它，不然重建后界面是空的 */
    public void attach(Listener l) {
        setListener(l);
        final Song s = getCurrent();
        if (s == null || l == null) return;
        final int idx = index;
        final int st = state;
        main.post(new Runnable() {
            @Override public void run() {
                if (listener == null) return;
                listener.onTrackChanged(s, idx);
                listener.onQueueChanged();
                listener.onStateChanged(st);
                MediaPlayer p = mp;
                if (p != null) {
                    try { listener.onProgress(p.getCurrentPosition(), p.getDuration()); }
                    catch (Exception e) { }
                }
            }
        });
    }

    /** 申请音频焦点（导航/来电要能压我们一头） */
    private boolean requestFocus() {
        try {
            if (am == null) return true;
            return am.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) { return true; }
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setQuality(String q) { if (q != null && q.length() > 0) this.quality = q; }
    public String getQuality() { return quality; }

    /** 断点播放：设置 prepare 落地后要跳到的位置（0 表示从头放） */
    public void setResumeMs(int ms) { this.resumeMs = ms > 0 ? ms : 0; }

    /** 当前播放位置（毫秒）；还没 prepare 好时返回待跳转的断点位置 */
    public int getPosition() {
        MediaPlayer p = mp;
        if (p != null) {
            try { return Math.max(0, p.getCurrentPosition()); } catch (Exception e) { }
        }
        return Math.max(0, resumeMs);
    }

    public List<Song> getQueue() { return queue; }
    public int getIndex() { return index; }
    public Song getCurrent() {
        return (index >= 0 && index < queue.size()) ? queue.get(index) : null;
    }
    public int getState() { return state; }
    public boolean isReleased() { return released; }

    public void setQueue(List<Song> songs, int startIndex) {
        if (songs == null) return;
        queue = new ArrayList<Song>(songs);
        index = (startIndex >= 0 && startIndex < queue.size()) ? startIndex : -1;
        if (listener != null) listener.onQueueChanged();
    }

    public void addToQueue(List<Song> songs) {
        if (songs == null) return;
        queue.addAll(songs);
        if (listener != null) listener.onQueueChanged();
    }

    /* ---------------- 播放控制 ---------------- */

    public void playIndex(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        startCurrent();
    }

    public void playCurrent() {
        if (index < 0 || index >= queue.size()) return;
        startCurrent();
    }

    public void toggle() {
        if (released) return;
        // 注意顺序：startCurrent() 会先置 BUFFERING，但此时 mp 还是 null
        // （缓存探测 + 建播放器那段窗口）。先判 BUFFERING，否则按暂停会变成「从头重载并开播」。
        if (state == STATE_BUFFERING) {
            startWhenPrepared = !startWhenPrepared;
            userPaused = !startWhenPrepared;
            setState(STATE_BUFFERING);
            persist();
            return;
        }
        MediaPlayer p = mp;
        if (p == null) { userPaused = false; playCurrent(); return; }
        try {
            if (state == STATE_PLAYING) {
                userPaused = true;
                p.pause();
                setState(STATE_PAUSED);
                persist();
            } else if (state == STATE_PAUSED || state == STATE_COMPLETED) {
                if (state == STATE_COMPLETED) p.seekTo(0);
                // 焦点逻辑：拿不到就记下来等 GAIN，不要「因为已经 pausedByFocus 就照播」
                if (!requestFocus()) { pausedByFocus = true; persist(); return; }
                pausedByFocus = false;
                userPaused = false;
                p.start();
                setState(STATE_PLAYING);
                persist();
            } else if (state == STATE_IDLE) {
                playCurrent();
            } else if (state == STATE_BUFFERING) {
                // prepare 还没落地：pause()/start() 都会抛 IllegalStateException，
                // 但直接装死会让用户以为卡住 —— 记下意向，onPrepared 时执行
                startWhenPrepared = !startWhenPrepared;
                setState(STATE_BUFFERING);
            }
        } catch (Exception e) {
            notifyError(e.getMessage());
        }
    }

    public void next() {
        if (index + 1 < queue.size()) { playIndex(index + 1); return; }
        // 到底了：先问上层要不要续（私人 FM 会自动拉下一批），要续就保持 BUFFERING
        if (listener != null && listener.onQueueEnded()) {
            setState(STATE_BUFFERING);
            return;
        }
        nextRepeat();
    }

    public void prev() {
        // 播放超过 3 秒 → 回到本曲开头（符合用户直觉）
        MediaPlayer p = mp;
        if (p != null && state == STATE_PLAYING) {
            try {
                if (p.getCurrentPosition() > 3000) {
                    p.seekTo(0);
                    return;
                }
            } catch (Exception e) { }
        }
        if (index > 0) playIndex(index - 1);
    }

    private void nextRepeat() {
        MediaPlayer p = mp;
        try {
            if (p != null && p.isLooping()) {
                p.start();
                setState(STATE_PLAYING);
            } else {
                ++prepGen;                 // 作废任何还没落地的 prepare
                releaseMp();
                setState(STATE_IDLE);
            }
        } catch (Exception e) {
            ++prepGen;
            releaseMp();
            setState(STATE_IDLE);
        }
    }

    public void seekTo(int ms) {
        if (ms < 0) ms = 0;
        MediaPlayer p = mp;
        if (p == null) {
            // 还没 prepare 好：记下来，prepare 落地时直接跳过去
            resumeMs = ms;
            persist();
            return;
        }
        try { p.seekTo(ms); } catch (Exception e) { }
        persist();
    }

    /** 断点播放：把当前进度 + 播放状态落盘（cursor 级，写量很小） */
    private void persist() {
        if (queue.isEmpty() || index < 0) return;
        ResumeState.saveCursor(ctx, index, getPosition(), state == STATE_PLAYING);
    }

    public void stop() {
        ++prepGen;
        releaseMp();
        setState(STATE_IDLE);
    }

    public void release() {
        // 断点播放：退出前最后整份存一次（位置必须从还活着的句柄读，所以要放在 releaseMp 之前）
        if (!queue.isEmpty() && index >= 0) {
            ResumeState.save(ctx, queue, index, getPosition(),
                    state == STATE_PLAYING, quality);
        }
        released = true;
        pausedByFocus = false;
        ducked = false;
        try { if (am != null) am.abandonAudioFocus(focusListener); } catch (Exception e) { }
        ++prepGen;
        listener = null;
        main.removeCallbacks(ticker);
        releaseMp();
        state = STATE_IDLE;
    }

    /** 释放句柄，不改状态、不动 prepGen（调用方自己决定） */
    private void releaseMp() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try {
            if (p.isPlaying()) p.stop();
        } catch (Exception e) { }
        try { p.reset(); } catch (Exception e) { }
        try { p.release(); } catch (Exception e) { }
    }

    private void setState(final int s) {
        state = s;
        // 进度轮询只在播放时跑：以前每 500ms 自我续命，IDLE 了还在醒主线程
        main.removeCallbacks(ticker);
        if (s == STATE_PLAYING && !released) main.post(ticker);
        if (listener == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onStateChanged(s);
            }
        });
    }

    private void notifyError(final String msg) {
        if (listener == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onError(msg);
            }
        });
    }

    /* ---------------- 内部 ---------------- */

    private void startCurrent() {
        if (released) return;
        final Song s = getCurrent();
        if (s == null) return;

        // 先作废旧代次 + 收掉旧句柄，再开新代次
        releaseMp();
        final int gen = ++prepGen;
        startWhenPrepared = true;
        userPaused = false;
        setState(STATE_BUFFERING);

        PlayService.ensure(ctx);        // 前台服务：切到别的 App 也继续放
        AppConfig.setLastSongId(ctx, (int) s.id);
        // 断点播放：切歌即整份落盘。位置用 resumeMs（恢复断点时为上次进度，否则 0），
        // 免得「开机自动续播」刚起步就把断点覆盖成 0。
        ResumeState.save(ctx, queue, index, resumeMs, true, quality);
        lastCursorSaveAt = System.currentTimeMillis();
        final int idx = index;
        if (listener != null) main.post(new Runnable() {
            @Override public void run() {
                if (listener != null && gen == prepGen && !released) listener.onTrackChanged(s, idx);
            }
        });

        final String q = quality;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File local = null;
                try {
                    String ext = LocalCache.extFor(q);
                    if (LocalCache.isCached(s, q, ext)) {
                        local = LocalCache.fileFor(s, q, ext);
                        // 命中缓存顺手刷新 lastModified：LRU 淘汰按「最近使用」而不是「下载时间」
                        try { local.setLastModified(System.currentTimeMillis()); } catch (Exception e) { }
                    }
                } catch (Exception e) { }
                final File f = local;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        preparePlayer(s, f, gen);
                    }
                });
            }
        }).start();
    }

    private void preparePlayer(Song s, File localFile, int gen) {
        if (released || gen != prepGen) return;
        if (mp != null) releaseMp();      // 理论上走不到；真走到了也不能让两个句柄共存

        MediaPlayer p = null;
        try {
            p = new MediaPlayer();
            mp = p;
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            // 熄屏/系统休眠时别让 CPU 停了把流掐断（WAKE_LOCK 已在 manifest 声明）
            try { p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK); } catch (Exception e) { }

            if (localFile != null) {
                p.setDataSource(localFile.getAbsolutePath());
            } else {
                p.setDataSource(ApiClient.streamUrl(ctx, s.id, quality));
            }

            final MediaPlayer self = p;
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mpp) {
                    if (self != mp) return;          // 已被新一代取代
                    try {
                        // 断点播放：先把进度条拖到上次位置，再决定播不播。
                        // 只消费一次，之后切歌都从 0 开始。
                        int rs = resumeMs;
                        resumeMs = 0;
                        if (rs > 0) {
                            try {
                                int dur = self.getDuration();
                                // 上次听到结尾了就别硬跳，从头放更合理
                                if (dur > 0 && rs >= dur - 1000) rs = 0;
                            } catch (Exception e) { }
                            if (rs > 0) self.seekTo(rs);
                        }
                        // 拿不到焦点（通话/导航播报占着）就别开播，等 GAIN 再续
                        if (!requestFocus() || pausedByFocus || !startWhenPrepared) {
                            setState(STATE_PAUSED);
                            return;
                        }
                        self.start();
                        setState(STATE_PLAYING);
                    } catch (Exception e) {
                        notifyError(e.getMessage());
                    }
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mpp) {
                    if (self != mp) return;
                    setState(STATE_COMPLETED);
                    next();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mpp, int what, int extra) {
                    if (self != mp) return true;
                    releaseMp();
                    setState(STATE_IDLE);
                    notifyError("播放失败 what=" + what + " extra=" + extra);
                    return true;
                }
            });

            p.prepareAsync();
        } catch (Exception e) {
            if (p != null && p == mp) releaseMp();
            setState(STATE_IDLE);
            notifyError(e.getMessage());
        }
    }

    /**
     * 预取下一首：只在 WiFi 下做，下载到本地缓存。
     * 播放切换时下一首已在本地 → 零等待。
     */
    public void prefetchNext() {
        if (released) return;
        if (!AppConfig.isPrefetchEnabled(ctx)) return;
        if (!LocalCache.isWifi(ctx)) return;
        if (index + 1 >= queue.size()) return;

        final Song next = queue.get(index + 1);
        final String q = quality;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String ext = LocalCache.extFor(q);
                    if (LocalCache.isCached(next, q, ext)) return;
                    LocalCache.download(ctx, next, q, ext, null);
                    // 顺带做一次 LRU 清理
                    LocalCache.evictIfNeeded(AppConfig.getCacheMb(ctx));
                } catch (Exception e) {
                    // 预取失败无所谓，不打扰用户
                }
            }
        }).start();
    }
}
