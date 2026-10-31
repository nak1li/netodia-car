package com.nakili.ncmcar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 主界面（v2 版式）：上部三栏 220 : 532 : 272，下部 104dp 常驻控制条。
 *
 *   左：每日推荐 / 红心歌曲 / 我的歌单 / 搜索（+ 设置）
 *   中：列表（头 56dp + 行 72dp，行内右侧 68dp 红心点击区）
 *   右：音质·缓存标签 / 封面 206dp / 歌名·歌手·专辑 / 歌词（16sp，当前行高亮）
 *
 * 车机约束（API 19 / Allwinner T3）：
 * - 只用 android.* 和 org.json，没有 AndroidX / AppCompat / 三方库
 * - 不做动画（elevation / ripple / letterSpacing 都是 API 21+ 的属性，这里一律不用）
 * - 按钮 ≥ 80dp，触摸区集中在驾驶员伸手带
 * - 物理按键：MediaPlayPause / MediaNext / MediaPrevious / SPACE；BACK 不退出
 */
public class MainActivity extends Activity implements Player.Listener {

    /* ---------------- 配色（与 preview_lhd.py 一一对应） ---------------- */
    private static final int C_ACCENT     = 0xFF7FA9FF;
    private static final int C_HEART      = 0xFFF5455C;
    private static final int C_HEART_OFF  = 0xFF3A4554;
    private static final int C_TITLE      = 0xFFE6EAF2;
    private static final int C_TEXT       = 0xFFDDE4EE;
    private static final int C_MUTED      = 0xFF7D8896;
    private static final int C_INK        = 0xFF0E1116;
    private static final int C_SUB_ON     = 0xFF1F3A63;

    /* ---------------- 左栏入口 ---------------- */
    private static final int NAV_DAILY = 0;
    private static final int NAV_HEART = 1;   // 心动模式（以当前歌为种子连播相似）
    private static final int NAV_PLAYLIST = 2;
    private static final int NAV_FM = 3;      // 私人 FM
    private static final int NAV_SEARCH = 4;  // 搜索（车主：放最后一个）
    private static final int NAV_COUNT = 5;
    // 「红心歌曲」入口已删除（车主：跟「我的歌单」里的「我喜欢的音乐」重叠）。
    // 红心功能本身都在：行内红心、底栏红心、红心状态同步。

    // 全部位图：TextView 的字符图标（☀ ♥ ≡）在 4.4 里基线跟位图对不齐，
    // 折叠成图标栏后一列图标高低不齐，看着就是「歪的」。
    private static final int[] NAV_DRAW = {R.drawable.ic_nav_daily, R.drawable.ic_nav_heartmode,
            R.drawable.ic_nav_list, R.drawable.ic_nav_fm, R.drawable.ic_nav_search};
    private static final String[] NAV_TITLES = {"每日推荐", "心动模式", "我的歌单",
            "私人 FM", "搜索"};

    /* ---------------- 中栏列表类型 ---------------- */
    private static final int LIST_SONGS = 0;
    private static final int LIST_PLAYLISTS = 1;
    private static final int LIST_DOWNLOADS = 2;

    private static final String MSG_LOGIN = "需要登录网易云（找小胖扫码）";
    private static final long LIKED_TTL = 5 * 60 * 1000L;      // 红心 id 内存缓存 5 分钟
    private static final long LIST_TTL = 5 * 60 * 1000L;

    private Player player;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 左栏
    private final View[] navItems = new View[NAV_COUNT];
    private final TextView[] navTitles = new TextView[NAV_COUNT];
    private final ImageView[] navIcons = new ImageView[NAV_COUNT];
    private int navSelected = -1;
    /** 左栏折叠：图标栏模式（56dp），中间列表和右侧播放器分走腾出来的宽度 */
    private boolean navCollapsed = false;
    /** 多久没操作就把左栏自动收起（车主定：默认 30 秒） */
    private static final long IDLE_COLLAPSE_MS = 30 * 1000L;
    private final Runnable idleCollapse = new Runnable() {
        @Override public void run() {
            if (!navCollapsed && !isFinishing()) applyNavCollapsed(true);
        }
    };
    private static final String K_NAV_COLLAPSED = "nav_collapsed";

    // 中栏
    private ListView songList;
    private TextView listTitle, listInfo, emptyHint;
    private RowAdapter adapter;
    private View listTopPad, listBottomPad;   // 列表上下留白，让当前播放行能停在正中
    private int padHeight = -1;

    // 右栏
    private TextView nowTitle, nowArtist, lyricView;
    private ImageView cover;
    private ScrollView lyricScroll;

    // 底栏
    private ImageView btnPrev, btnPlay, btnNext;
    private TextView btnLike;
    private SeekBar seek;
    private boolean userSeeking = false;
    /** 拖动松手后等 MediaPlayer 真正 seek 到位，期间别让进度回调把滑块抢回去 */
    private int seekPendingMs = -1;
    private long seekDeadline = 0;

    // 数据
    private final List<Song> currentSongs = new ArrayList<Song>();
    private final List<Playlist> currentPlaylists = new ArrayList<Playlist>();
    private int listMode = LIST_SONGS;
    private boolean fmMode = false;           // 当前列表是私人 FM（播完要自动续批）
    private boolean fmLoading = false;        // 续批进行中，别叠着发请求
    private boolean queueIsFm = false;        // 当前「播放队列」来自 FM（续批只认这个）
    private volatile int loadSeq = 0;         // 丢弃过期响应（连点左栏时）；后台线程也会读
    private List<Playlist> cachedPlaylists;   // 歌单列表快取，给左栏副标题用
    private long cachedPlaylistsAt = 0;
    /** 列表头代次：下载进度只允许写自己那一版列表头，切了列表就别覆盖 */
    private int headerSeq = 0;
    /** 正在发请求的红心 id（防连点） */
    private final Set<Long> likePending = new HashSet<Long>();

    // 歌词
    private final List<Integer> lrcTimes = new ArrayList<Integer>();
    private final List<String> lrcTexts = new ArrayList<String>();
    private int lrcIdx = -1;
    private volatile long lrcSongId = -1L;

    /* ==================================================================== */
    /* 红心 id 内存缓存（5 分钟）                                            */
    /* ==================================================================== */

    private static final class Liked {
        static final Set<Long> IDS = new HashSet<Long>();
        static long AT = 0L;

        static boolean fresh() {
            return AT > 0 && (System.currentTimeMillis() - AT) < LIKED_TTL;
        }

        static boolean has(long id) {
            return IDS.contains(Long.valueOf(id));
        }

        static void put(long id, boolean on) {
            if (on) IDS.add(Long.valueOf(id));
            else IDS.remove(Long.valueOf(id));
            if (AT == 0) AT = System.currentTimeMillis();
        }

        static void set(List<Long> ids) {
            IDS.clear();
            if (ids != null) IDS.addAll(ids);
            AT = System.currentTimeMillis();
        }

        static void markEmpty() {
            IDS.clear();
            AT = System.currentTimeMillis();
        }
    }

    /* ==================================================================== */
    /* 列表行模型 + 适配器                                                   */
    /* ==================================================================== */

    private static final class Row {
        String title = "";
        String sub = "";
        boolean cached;      // 「● 已缓存」
        boolean heart;       // 是否显示红心区（歌单行不显示）
        long songId;
        Song song;
        Playlist playlist;
    }

    private class RowAdapter extends BaseAdapter {
        private final List<Row> rows = new ArrayList<Row>();
        private final LayoutInflater inf;

        RowAdapter() {
            inf = LayoutInflater.from(MainActivity.this);
        }

        void setRows(List<Row> data) {
            rows.clear();
            if (data != null) rows.addAll(data);
            notifyDataSetChanged();
        }

        /** 私人 FM 自动续批：把新一批追加到列表尾部 */
        void addRows(List<Row> more) {
            if (more == null || more.isEmpty()) return;
            rows.addAll(more);
            notifyDataSetChanged();
        }

        void clear() {
            rows.clear();
            notifyDataSetChanged();
        }

        /** 下载完成后把某一行的「● 已缓存」点亮 */
        void markCached(long songId, boolean v) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.songId == songId) {
                    r.cached = v;
                    notifyDataSetChanged();
                    return;
                }
            }
        }

        /**
         * 后台线程算好的「已缓存」标记，一次性贴回（避免在 getView / 主线程 stat 文件）。
         * ids 与 flags 一一对应；只影响当前还在列表里的行。
         */
        void applyCachedFlags(long[] ids, boolean[] flags) {
            if (ids == null || flags == null || ids.length != flags.length) return;
            boolean changed = false;
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (!r.heart || r.song == null) continue;
                for (int k = 0; k < ids.length; k++) {
                    if (ids[k] == r.songId) {
                        if (r.cached != flags[k]) {
                            r.cached = flags[k];
                            changed = true;
                        }
                        break;
                    }
                }
            }
            if (changed) notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int pos) { return rows.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert;
            ViewHolder h;
            if (v == null) {
                v = inf.inflate(R.layout.item_song, parent, false);
                h = new ViewHolder();
                h.title = (TextView) v.findViewById(R.id.row_title);
                h.sub = (TextView) v.findViewById(R.id.row_sub);
                h.cached = (TextView) v.findViewById(R.id.row_cached);
                h.heart = (TextView) v.findViewById(R.id.row_heart);
                h.playing = (ImageView) v.findViewById(R.id.row_playing);
                v.setTag(h);
                // 红心点击区：只切红心，不触发整行（播放）。
                // 位置放在 tag 里、点击时再查行，避免监听器捕获到过期的 position/歌曲对象。
                h.heart.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        Object tag = x.getTag();
                        if (!(tag instanceof Integer)) return;
                        int i = ((Integer) tag).intValue();
                        if (i < 0 || i >= rows.size()) return;
                        Row r = rows.get(i);
                        if (r.song != null) toggleLike(r.song);
                    }
                });
            } else {
                h = (ViewHolder) v.getTag();
            }

            Row r = rows.get(pos);

            // 正在播放的那一行：标题变强调色 + 行首均衡器图标
            Song cur = player == null ? null : player.getCurrent();
            boolean playing = r.song != null && cur != null && cur.id == r.songId;

            // 每帧都必须重置这三处状态：convertView 会把上一行的样子带过来
            h.title.setText(r.title);
            h.title.setTextColor(playing ? C_ACCENT : C_TEXT);
            // 在播标记挂在歌名后面：不占固定列，歌名不会被挤短。
            // 不在播用 GONE 也行（右边是空白，标题本来就从左边起），
            // 但留着 INVISIBLE 能让同一行「已缓存」标记位置稳定，不来回跳。
            if (h.playing != null) {
                h.playing.setVisibility(r.song == null ? View.GONE
                        : (playing ? View.VISIBLE : View.INVISIBLE));
            }
            h.sub.setText(r.sub);
            h.sub.setVisibility(r.sub.length() == 0 ? View.GONE : View.VISIBLE);
            h.cached.setVisibility(r.cached && r.heart ? View.VISIBLE : View.GONE);

            if (r.heart) {
                // 红心状态每帧从缓存读，乐观更新后 notifyDataSetChanged 即可变色
                h.heart.setVisibility(View.VISIBLE);
                h.heart.setTextColor(Liked.has(r.songId) ? C_HEART : C_HEART_OFF);
                h.heart.setTag(Integer.valueOf(pos));
            } else {
                h.heart.setVisibility(View.INVISIBLE);
                h.heart.setTag(null);
            }
            return v;
        }
    }

    private static final class ViewHolder {
        TextView title, sub, cached, heart;
        ImageView playing;
    }

    /* ==================================================================== */
    /* 生命周期                                                              */
    /* ==================================================================== */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LocalCache.init(this);
        setContentView(R.layout.activity_main);

        player = Player.get(this);
        player.attach(this);
        player.setQuality(AppConfig.getQuality(this));

        bindViews();
        buildNav();
        setupControls();
        setNavSelected(-1);

        if (getIntent() != null && getIntent().getBooleanExtra("offline", false)) {
            listMode = LIST_SONGS;
            setHeader("离线模式", "");
            adapter.clear();
            showHint("连不上服务端。\n可在「设置」里改服务器地址。\n已缓存的歌曲仍可播放。");
            lyricView.setText("");
            return;
        }

        PlayService.clearLegacyNotification(this);   // 清掉老版本留下的播放通知
        loadDaily();
        refreshLikedIds(false);
        prefetchPlaylistCount();
    }

    private void bindViews() {
        // 左栏
        // (nav_list 里的条目在 buildNav() 里生成)

        // 中栏
        songList = (ListView) findViewById(R.id.song_list);
        listTitle = (TextView) findViewById(R.id.list_title);
        listInfo = (TextView) findViewById(R.id.list_info);
        // 私人 FM 不再「点一下换一批」（车主否掉了）：列表播完自动续下一批，
        // 由 Player.onQueueEnded() 回调触发，这里不挂点击。
        emptyHint = (TextView) findViewById(R.id.empty_hint);
        adapter = new RowAdapter();
        // 上下各垫一段留白：ListView 默认只有到不了两头之外的滚动空间，
        // 没有留白的话最后几首永远顶不到正中（车主要求正在播的固定在中间）
        listTopPad = new View(this);
        listBottomPad = new View(this);
        songList.addHeaderView(listTopPad, null, false);
        songList.addFooterView(listBottomPad, null, false);
        songList.setAdapter(adapter);
        songList.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                try {
                    int h = (b - t) / 2 - dp(36);
                    if (h < 0) h = 0;
                    if (padHeight == h) return;
                    padHeight = h;
                    // 直接换 LayoutParams：ListView 的 header/footer 布局参数可能还是 null，
                    // 改字段会 NPE（4.4 上踩过）
                    listTopPad.setLayoutParams(
                            new AbsListView.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, h));
                    listBottomPad.setLayoutParams(
                            new AbsListView.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, h));
                } catch (Exception e) { }
            }
        });

        // 右栏
        cover = (ImageView) findViewById(R.id.cover);
        lyricScroll = (ScrollView) findViewById(R.id.lyric_scroll);
        lyricView = (TextView) findViewById(R.id.lyric_view);
        nowTitle = (TextView) findViewById(R.id.now_title);
        nowArtist = (TextView) findViewById(R.id.now_artist);

        // 底栏
        btnPrev = (ImageView) findViewById(R.id.btn_prev);
        btnPlay = (ImageView) findViewById(R.id.btn_play);
        btnNext = (ImageView) findViewById(R.id.btn_next);
        btnLike = (TextView) findViewById(R.id.btn_like);
        seek = (SeekBar) findViewById(R.id.seek);

        updateBottomHeart();
    }

    private void buildNav() {
        ViewGroup box = (ViewGroup) findViewById(R.id.nav_list);
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < NAV_COUNT; i++) {
            View item = inf.inflate(R.layout.item_nav, box, false);
            navIcons[i] = (ImageView) item.findViewById(R.id.nav_icon);
            navIcons[i].setImageResource(NAV_DRAW[i]);
            navTitles[i] = (TextView) item.findViewById(R.id.nav_title);
            navTitles[i].setText(NAV_TITLES[i]);
            final int idx = i;
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onNavClick(idx); }
            });
            navItems[i] = item;
            box.addView(item);
        }

        // 记住上次的折叠状态：车机重启后不用每次再点一遍
        if (AppConfig.p(this).getBoolean(K_NAV_COLLAPSED, false)) applyNavCollapsed(true);
        resetIdleTimer();

        findViewById(R.id.nav_collapse).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyNavCollapsed(!navCollapsed); }
        });

        // 设置齿轮在左栏头部（Tap=设置，长按=下载页）
        View gear = findViewById(R.id.nav_gear);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        gear.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showDownloads();
                return true;
            }
        });
    }

    /** 折叠/展开左栏：宽度按权重分，文字块整块隐藏，只留图标 */
    /** 任何触摸都重新计时；到点自动收起左栏 */
    private void resetIdleTimer() {
        main.removeCallbacks(idleCollapse);
        main.postDelayed(idleCollapse, IDLE_COLLAPSE_MS);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        resetIdleTimer();
        return super.dispatchTouchEvent(ev);
    }

    private void applyNavCollapsed(boolean collapsed) {
        navCollapsed = collapsed;
        View navCol = findViewById(R.id.nav_col);
        View listCol = findViewById(R.id.list_col);
        View playerCol = findViewById(R.id.player_col);
        setWeight(navCol, collapsed ? 56 : 220);
        // 腾出来的 164 按 108/56 分给列表和播放器
        setWeight(listCol, collapsed ? 640 : 532);
        setWeight(playerCol, collapsed ? 328 : 272);

        findViewById(R.id.nav_head_title).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        ImageView toggle = (ImageView) findViewById(R.id.nav_collapse);
        // 位图箭头：折叠态指向右（展 开），展开态指向左（收起）
        toggle.setRotation(collapsed ? 180f : 0f);
        toggle.setColorFilter(collapsed ? 0xFFDDE4EE : 0xFF7D8896, PorterDuff.Mode.SRC_IN);
        // 收窄到 56dp（减去左右 8dp 内边距只剩 40dp）后，齿轮+箭头放不下，
        // 箭头会被挤出屏外 → 折叠态只留「展开」箭头，齿轮等展开后再出现
        View gearBtn = findViewById(R.id.nav_gear);
        if (gearBtn != null) gearBtn.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        android.view.ViewGroup.LayoutParams tlp = toggle.getLayoutParams();
        tlp.width = dp(40);
        tlp.height = dp(collapsed ? 34 : 30);
        toggle.setLayoutParams(tlp);
        // 折叠后这是唯一的展开入口，稍放大一点好点中
        toggle.setPadding(dp(collapsed ? 7 : 6), dp(collapsed ? 7 : 6),
                dp(collapsed ? 7 : 6), dp(collapsed ? 7 : 6));

        int pad = dp(collapsed ? 0 : 13);
        for (int i = 0; i < NAV_COUNT; i++) {
            View item = navItems[i];
            if (item == null) continue;
            // 副标题已去掉，展开态只留一行标题；折叠态把标题也藏了，只剩图标
            if (navTitles[i] != null) {
                navTitles[i].setVisibility(collapsed ? View.GONE : View.VISIBLE);
            }
            item.setPadding(pad, 0, pad, 0);
            ImageView icon = navIcons[i];
            if (icon != null) {
                android.widget.LinearLayout.LayoutParams lp =
                        (android.widget.LinearLayout.LayoutParams) icon.getLayoutParams();
                // 折叠时图标占满整列并居中；展开时固定 30dp 靠左
                // （30dp 配 20sp 标题才够份量，26dp 时图标显得比字小，看着不协调）
                lp.width = collapsed ? android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                                     : dp(34);
                lp.height = dp(collapsed ? 30 : 34);
                lp.rightMargin = collapsed ? 0 : dp(10);
                lp.leftMargin = 0;
                icon.setLayoutParams(lp);
                icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            }
        }

        AppConfig.p(this).edit().putBoolean(K_NAV_COLLAPSED, collapsed).commit();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setWeight(View v, int w) {
        android.widget.LinearLayout.LayoutParams lp =
                (android.widget.LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = w;
        v.setLayoutParams(lp);
    }

    private void setNavSelected(int idx) {
        navSelected = idx;
        for (int i = 0; i < NAV_COUNT; i++) {
            boolean on = (i == idx);
            navItems[i].setBackgroundResource(
                    on ? R.drawable.nav_item_active : R.drawable.nav_item_normal);
            navTitles[i].setTextColor(on ? C_INK : C_TEXT);
            if (navIcons[i] != null) {
                android.graphics.drawable.Drawable ic = navIcons[i].getDrawable();
                if (ic != null) {
                    // mutate()：不加会把颜色改到共享的恒定状态上，影响别处
                    ic = ic.mutate();
                    ic.setColorFilter(on ? C_INK : C_TEXT, PorterDuff.Mode.SRC_IN);
                    navIcons[i].setImageDrawable(ic);
                }
            }
            navTitles[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void onNavClick(int idx) {
        // 再点一次当前入口就收起左栏（车机上腾出宽度给列表和封面）
        if (idx == navSelected && !navCollapsed && idx != NAV_SEARCH) {
            applyNavCollapsed(true);
            return;
        }
        // 折叠态点图标只切页面、不展开左栏 —— 图标栏的意义就是腾出宽度，
        // 点一下就弹开等于白收。要看名字用顶上那个 › 箭头。
        switch (idx) {
            case NAV_DAILY: loadDaily(); break;
            case NAV_PLAYLIST: loadMyPlaylists(); break;
            case NAV_SEARCH: showSearchDialog(); break;
            case NAV_HEART: loadHeartbeat(); break;
            case NAV_FM: loadFm(); break;
            default: break;
        }
    }

    private void setupControls() {
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { player.prev(); }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (player.getCurrent() == null) {
                    toast("先选一首歌");
                    return;
                }
                player.toggle();
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { player.next(); }
        });
        btnLike.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLike(player.getCurrent()); }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; seekPendingMs = -1; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                int target = sb.getProgress();
                seekPendingMs = target;
                seekDeadline = SystemClock.uptimeMillis() + 2500;
                player.seekTo(target);
            }
        });

        // 点封面 / 歌词区：收起或展开封面（歌词区自动占满剩下的高度）
        cover.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleCover(); }
        });
        lyricView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleCover(); }
        });

        songList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                pos -= songList.getHeaderViewsCount();   // 头部留白占了一位
                if (pos < 0 || pos >= adapter.getCount()) return;
                Row r = (Row) adapter.getItem(pos);
                if (listMode == LIST_PLAYLISTS) {
                    if (r.playlist != null) openPlaylist(r.playlist);
                    return;
                }
                if (listMode == LIST_SONGS && r.song != null) {
                    player.setQuality(AppConfig.getQuality(MainActivity.this));
                    queueIsFm = fmMode;
                    player.setQueue(currentSongs, pos);
                    player.playCurrent();
                    return;
                }
                if (listMode == LIST_DOWNLOADS) {
                    toast("缓存文件由播放时自动加载");
                }
            }
        });
        songList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= adapter.getCount()) return false;
                Row r = (Row) adapter.getItem(pos);
                if (r.song != null) {
                    showSongMenu(r.song);
                    return true;
                }
                return false;
            }
        });
    }

    private void toggleCover() {
        boolean show = (cover.getVisibility() != View.VISIBLE);
        cover.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /* ==================================================================== */
    /* 左栏数据源                                                            */
    /* ==================================================================== */

    private void loadDaily() {
        setNavSelected(NAV_DAILY);
        final int seq = ++loadSeq;
        listMode = LIST_SONGS;
        setHeader("每日推荐", "");
        adapter.clear();
        showHint("正在加载…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Song> songs = ApiClient.recommendSongs(MainActivity.this, 30);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            String info = new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date());
                            showSongs("每日推荐", info, songs);
                        }
                    });
                } catch (final ApiClient.NeedLoginException e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            setHeader("每日推荐", "未登录");
                            adapter.clear();
                            showHint(MSG_LOGIN);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            setHeader("每日推荐", "加载失败");
                            adapter.clear();
                            showHint("拉取失败：" + msgOf(e) + "\n可在「设置」里检查服务器地址");
                        }
                    });
                }
            }
        }).start();
    }

    /** 心动模式：以当前播放的歌为种子，拉一串相似歌曲（服务端滚雪球到 30 首） */
    private void loadHeartbeat() {
        setNavSelected(NAV_HEART);
        final int seq = ++loadSeq;
        listMode = LIST_SONGS;
        setHeader("心动模式", "");
        adapter.clear();
        showHint("正在按当前歌曲找相似…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long seed = 0;
                    Song cur = player.getCurrent();
                    if (cur != null) seed = cur.id;
                    if (seed == 0 && !currentSongs.isEmpty()) seed = currentSongs.get(0).id;
                    if (seed == 0) {
                        final List<Song> like = ApiClient.likelistSongs(MainActivity.this, 1);
                        if (!like.isEmpty()) seed = like.get(0).id;
                    }
                    if (seed == 0) throw new Exception("没有可用的种子歌曲，先随便放一首");
                    final List<Song> songs = ApiClient.similarSongs(MainActivity.this, seed, 30, "");
                    final String seedName = player.getCurrent() == null ? "" : player.getCurrent().name;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            String info = seedName.length() > 0 ? "种子：" + seedName : "";
                            showSongs("心动模式", info, songs);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            setHeader("心动模式", "加载失败");
                            adapter.clear();
                            showHint("拉取失败：" + msgOf(e));
                        }
                    });
                }
            }
        }).start();
    }

    /** 私人 FM：每次 3 首左右，再点一次换一批 */
    private void loadFm() {
        setNavSelected(NAV_FM);
        final int seq = ++loadSeq;
        listMode = LIST_SONGS;
        setHeader("私人 FM", "");
        adapter.clear();
        showHint("正在为你挑选…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Song> songs = ApiClient.personalFm(MainActivity.this);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            showSongs("私人 FM", "", songs);
                        }
                    });
                } catch (final ApiClient.NeedLoginException e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            setHeader("私人 FM", "未登录");
                            adapter.clear();
                            showHint(MSG_LOGIN);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            setHeader("私人 FM", "加载失败");
                            adapter.clear();
                            showHint("拉取失败：" + msgOf(e));
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 后台预取一次歌单存进缓存：点进「我的歌单」时直接出列表，不用等网络。
     * （原来是为了填左栏副标题的数字，副标题去掉后改成纯预热缓存。）
     */
    private void prefetchPlaylistCount() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Playlist> pls = ApiClient.userPlaylists(MainActivity.this, 100);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (cachedPlaylists != null) return;   // 已经有更新的缓存了就不覆盖
                            rememberPlaylists(pls);
                        }
                    });
                } catch (Exception e) {
                    // 未登录/网络失败就维持原样，用户点进去时会看到具体提示
                }
            }
        }).start();
    }

    private void loadMyPlaylists() {
        setNavSelected(NAV_PLAYLIST);
        final int seq = ++loadSeq;
        setHeader("我的歌单", "");
        if (cachedPlaylists != null
                && System.currentTimeMillis() - cachedPlaylistsAt < LIST_TTL) {
            showPlaylists(cachedPlaylists);
            return;
        }
        adapter.clear();
        showHint("正在加载歌单…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Playlist> pls = ApiClient.userPlaylists(MainActivity.this, 100);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            rememberPlaylists(pls);
                            showPlaylists(pls);
                        }
                    });
                } catch (final ApiClient.NeedLoginException e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            setHeader("我的歌单", "未登录");
                            adapter.clear();
                            showHint(MSG_LOGIN);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            setHeader("我的歌单", "加载失败");
                            adapter.clear();
                            showHint("拉取失败：" + msgOf(e));
                        }
                    });
                }
            }
        }).start();
    }

    /** 打开歌单曲目：复用现有 /api/playlist/detail */
    private void openPlaylist(final Playlist pl) {
        setNavSelected(NAV_PLAYLIST);
        final int seq = ++loadSeq;
        listMode = LIST_SONGS;
        setHeader(pl.name, "");
        adapter.clear();
        showHint("正在加载《" + pl.name + "》…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Song> songs = ApiClient.playlistSongs(MainActivity.this, pl.id);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            showSongs(pl.name, "", songs);
                        }
                    });
                } catch (final ApiClient.NeedLoginException e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            adapter.clear();
                            showHint(MSG_LOGIN);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            adapter.clear();
                            showHint("拉取失败：" + msgOf(e));
                        }
                    });
                }
            }
        }).start();
    }

    private void rememberPlaylists(List<Playlist> pls) {
        cachedPlaylists = new ArrayList<Playlist>(pls);
        cachedPlaylistsAt = System.currentTimeMillis();
    }

    private void showSongs(String title, String info, List<Song> songs) {
        listMode = LIST_SONGS;
        fmMode = "私人 FM".equals(title);
        currentSongs.clear();
        currentSongs.addAll(songs);
        currentPlaylists.clear();
        setHeader(title, info);
        if (songs.isEmpty()) {
            adapter.clear();
            showHint("没有内容");
            return;
        }
        String q = AppConfig.getQuality(this);
        List<Row> rows = new ArrayList<Row>(songs.size());
        final long[] ids = new long[songs.size()];
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            Row r = new Row();
            r.title = s.name;
            r.sub = s.artist;
            r.heart = true;
            r.songId = s.id;
            r.song = s;
            r.cached = false;              // 由后台线程补，别在主线程逐首 stat 文件
            rows.add(r);
            ids[i] = s.id;
        }
        hideHint();
        adapter.setRows(rows);
        // 打开列表就滚到正在播的那首（没有就回顶部）
        int at = -1;
        Song cur = player == null ? null : player.getCurrent();
        if (cur != null) {
            for (int i = 0; i < songs.size(); i++) {
                if (songs.get(i).id == cur.id) { at = i; break; }
            }
        }
        // 有正在播的就把那首居中；没有就把头部留白顶出去、从第一行开始看
        if (at >= 0) scrollListToCurrent(songs.get(at).id);
        else scrollListToTop();
        markCachedAsync(ids, q);
    }

    /**
     * 500 首歌的列表在主线程做 500 次 File.exists() 会卡滚动/卡点击，
     * 这里丢到后台一次性算完再贴标记。
     */
    private void markCachedAsync(final long[] ids, final String quality) {
        final int seq = loadSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean[] flags = new boolean[ids.length];
                Song tmp = new Song();
                for (int i = 0; i < ids.length; i++) {
                    if (seq != loadSeq) return;          // 列表已经换了，别白算
                    tmp.id = ids[i];
                    try { flags[i] = LocalCache.isCached(tmp, quality, LocalCache.extFor(quality)); }
                    catch (Exception e) { flags[i] = false; }
                }
                main.post(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) return;
                        adapter.applyCachedFlags(ids, flags);
                    }
                });
            }
        }).start();
    }

    private void showPlaylists(List<Playlist> pls) {
        listMode = LIST_PLAYLISTS;
        currentSongs.clear();
        currentPlaylists.clear();
        currentPlaylists.addAll(pls);
        setHeader("我的歌单", "");
        if (pls.isEmpty()) {
            adapter.clear();
            showHint("这个账号下还没有歌单");
            return;
        }
        List<Row> rows = new ArrayList<Row>(pls.size());
        for (int i = 0; i < pls.size(); i++) {
            Playlist p = pls.get(i);
            Row r = new Row();
            r.title = p.name;
            r.sub = (p.creator == null) ? "" : p.creator;
            r.heart = false;
            r.playlist = p;
            rows.add(r);
        }
        hideHint();
        adapter.setRows(rows);
        scrollListToTop();
    }

    /* ==================================================================== */
    /* 搜索 / 下载页 / 设置                                                  */
    /* ==================================================================== */

    private void showSearchDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_search, null);
        final EditText input = (EditText) v.findViewById(R.id.search_input);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("搜索歌曲")
                .setView(v)
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        doSearch(input.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        // 软键盘上按「搜索」也要能直接搜（车机上比点按钮快）
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView tv, int actionId, KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    dlg.dismiss();
                    doSearch(tv.getText().toString());
                    return true;
                }
                return false;
            }
        });
        dlg.show();
        input.requestFocus();
    }

    private void doSearch(final String raw) {
        final String q = (raw == null) ? "" : raw.trim();
        if (q.length() == 0) return;
        final int seq = ++loadSeq;
        listMode = LIST_SONGS;
        setHeader("搜索：" + q, "");
        adapter.clear();
        showHint("正在搜索「" + q + "」…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Song> songs = ApiClient.search(MainActivity.this, q, 1);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            showSongs("搜索：" + q, "", songs);
                            if (songs.isEmpty()) {
                                showHint("没有搜到「" + q + "」\n换个歌名或歌手试试");
                            }
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            adapter.clear();
                            showHint("搜索失败：" + msgOf(e));
                        }
                    });
                }
            }
        }).start();
    }

    private void showDownloads() {
        final int seq = ++loadSeq;
        listMode = LIST_DOWNLOADS;
        setNavSelected(-1);
        currentSongs.clear();
        currentPlaylists.clear();
        setHeader("我的下载", "");
        adapter.clear();
        showHint("正在统计缓存…");

        // 目录扫描（几百个文件 + 两次 listFiles）不能放主线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<LocalCache.CacheFile> files = LocalCache.listAll();
                long total = 0;
                for (int i = 0; i < files.size(); i++) total += files.get(i).size;
                final long totalSize = total;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) return;
                        showDownloadRows(files, totalSize);
                    }
                });
            }
        }).start();
    }

    private void showDownloadRows(List<LocalCache.CacheFile> files, long totalSize) {
        listMode = LIST_DOWNLOADS;
        setHeader("我的下载", (totalSize / 1024 / 1024) + " MB");
        if (files.isEmpty()) {
            adapter.clear();
            showHint("还没有缓存文件\n播放过的歌会自动缓存");
            return;
        }
        List<Row> rows = new ArrayList<Row>(files.size());
        for (int i = 0; i < files.size(); i++) {
            LocalCache.CacheFile f = files.get(i);
            String name = f.f.getName();
            String id = name;
            String quality = "";
            int dot = name.indexOf('.');
            if (dot > 0) {
                id = name.substring(0, dot);
                int dot2 = name.indexOf('.', dot + 1);
                quality = (dot2 > 0) ? name.substring(dot + 1, dot2) : name.substring(dot + 1);
            }
            Row r = new Row();
            r.title = "缓存 " + id;
            r.sub = quality + " · " + (f.size / 1024 / 1024 > 0
                    ? (f.size / 1024 / 1024) + " MB" : (f.size / 1024) + " KB")
                    + (f.locked ? " · 已锁定" : "");
            r.heart = false;
            rows.add(r);
        }
        hideHint();
        adapter.setRows(rows);
        scrollListToTop();
    }

    private void showSettings() {
        View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        final EditText serverIn = (EditText) v.findViewById(R.id.set_server);
        final EditText tokenIn = (EditText) v.findViewById(R.id.set_token);
        final EditText cacheIn = (EditText) v.findViewById(R.id.set_cache_mb);
        final EditText qualityIn = (EditText) v.findViewById(R.id.set_quality);

        serverIn.setText(AppConfig.getServer(this));
        tokenIn.setText(AppConfig.getToken(this));
        cacheIn.setText(String.valueOf(AppConfig.getCacheMb(this)));
        qualityIn.setFocusable(false);
        qualityIn.setClickable(true);
        qualityIn.setText(qualityLabel(AppConfig.getQuality(this)) + "  (" + AppConfig.getQuality(this) + ")");
        qualityIn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                final String[] labels = {
                        "标准 128K", "高 192K", "极高 320K（默认）",
                        "无损 FLAC（会员）", "Hi-Res FLAC（会员）", "超清母带 / 环绕（会员）"};
                final String[] values = {
                        "standard", "high", "exhigh", "lossless", "hires", "jymaster"};
                String cur = AppConfig.getQuality(MainActivity.this);
                int checked = 2;
                for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) checked = i;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("音质")
                        .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d2, int which) {
                                qualityIn.setText(qualityLabel(values[which])
                                        + "  (" + values[which] + ")");
                                qualityIn.setTag(values[which]);
                                d2.dismiss();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        qualityIn.setTag(AppConfig.getQuality(this));

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(v)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String oldServer = AppConfig.getServer(MainActivity.this);
                        String oldToken = AppConfig.getToken(MainActivity.this);
                        AppConfig.setServer(MainActivity.this, serverIn.getText().toString());
                        AppConfig.setToken(MainActivity.this, tokenIn.getText().toString());
                        try {
                            AppConfig.setCacheMb(MainActivity.this,
                                    Integer.parseInt(cacheIn.getText().toString().trim()));
                        } catch (Exception e) {
                            toast("缓存上限只能填整数（MB），这一项没保存");
                        }
                        String q = qualityIn.getTag() instanceof String
                                ? (String) qualityIn.getTag()
                                : qualityIn.getText().toString().trim();
                        if (q.length() > 0) {
                            AppConfig.setQuality(MainActivity.this, q);
                            player.setQuality(q);
                        }
                        // 换了服务器/令牌还沿用旧的红心缓存 → 界面显示上一个账号的状态
                        boolean credChanged = !oldToken.equals(AppConfig.getToken(MainActivity.this))
                                || !oldServer.equals(AppConfig.getServer(MainActivity.this));
                        if (credChanged) {
                            Liked.AT = 0L;
                            Liked.IDS.clear();
                            refreshLikedIds(true);
                        }
                        toast("设置已保存");
                    }
                })
                .setNeutralButton("清理缓存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        // 主线程删几百 MB 会冻界面；先落盘当前设置（对话框一点就关）
                        AppConfig.setServer(MainActivity.this, serverIn.getText().toString());
                        AppConfig.setToken(MainActivity.this, tokenIn.getText().toString());
                        new Thread(new Runnable() {
                            @Override public void run() {
                                LocalCache.clearAll();
                                main.post(new Runnable() {
                                    @Override public void run() {
                                        toast("已清理本地缓存");
                                        if (!currentSongs.isEmpty()) {
                                            long[] ids = new long[currentSongs.size()];
                                            for (int k = 0; k < ids.length; k++) ids[k] = currentSongs.get(k).id;
                                            markCachedAsync(ids, AppConfig.getQuality(MainActivity.this));
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .create();

        TextView dl = (TextView) v.findViewById(R.id.set_downloads);
        final TextView dlRef = dl;
        dl.setText("我的下载（统计中…）");
        dl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                dlg.dismiss();
                showDownloads();
            }
        });

        dlg.show();

        // 统计走后台，别为了在设置页显示一行字把主线程卡在几百次 stat 上
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = LocalCache.listAll().size();
                final long total = LocalCache.totalSize();
                main.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        dlRef.setText("我的下载（" + (total / 1024 / 1024) + " MB）");
                    }
                });
            }
        }).start();
    }

    /* ==================================================================== */
    /* 红心                                                                  */
    /* ==================================================================== */

    /** 启动时 / 5 分钟过期后拉红心 id；失败不打扰用户，保持旧缓存 */
    private void refreshLikedIds(boolean force) {
        if (!force && Liked.fresh()) {
            applyLikedUi();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Long> ids = ApiClient.likelistIds(MainActivity.this);
                    main.post(new Runnable() {
                        @Override public void run() {
                            Liked.set(ids);
                            applyLikedUi();
                        }
                    });
                } catch (final ApiClient.NeedLoginException e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            Liked.markEmpty();
                            applyLikedUi();
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            // 没有「红心歌曲」入口了，状态异常就不打扰用户
                        }
                    });
                }
            }
        }).start();
    }

    /** 乐观更新：先改本地再发请求，失败回滚 */
    private void toggleLike(final Song s) {
        if (s == null) {
            toast("先选一首歌");
            return;
        }
        // 同一首的请求还没回来时再点，会让两次乐观更新 + 回滚互相打架，直接忽略
        final Long key = Long.valueOf(s.id);
        synchronized (likePending) {
            if (likePending.contains(key)) return;
            likePending.add(key);
        }
        final boolean target = !Liked.has(s.id);
        applyLike(s.id, target);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ApiClient.setLike(MainActivity.this, s.id, target);
                    ok = true;
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            applyLike(s.id, !target);      // 回滚
                            toast("红心操作失败：" + msgOf(e));
                        }
                    });
                } finally {
                    if (ok) {
                        synchronized (likePending) { likePending.remove(key); }
                    } else {
                        main.post(new Runnable() {
                            @Override public void run() {
                                synchronized (likePending) { likePending.remove(key); }
                            }
                        });
                    }
                }
            }
        }).start();
    }

    private void applyLike(long id, boolean liked) {
        Liked.put(id, liked);
        applyLikedUi();
    }

    private void applyLikedUi() {
        if (adapter != null) adapter.notifyDataSetChanged();
        updateBottomHeart();
    }

    private void updateBottomHeart() {
        Song s = (player == null) ? null : player.getCurrent();
        boolean on = (s != null) && Liked.has(s.id);
        btnLike.setBackgroundResource(on ? R.drawable.heart_on : R.drawable.heart_off);
        btnLike.setTextColor(on ? C_HEART : C_HEART_OFF);
        btnLike.setEnabled(s != null);
        btnLike.setAlpha(s == null ? 0.55f : 1.0f);
    }

    /* ==================================================================== */
    /* 曲目菜单（下载 / 锁定 / 详情）                                         */
    /* ==================================================================== */

    private void showSongMenu(final Song s) {
        String[] items = {"下载到本地", "锁定（不自动清理）", "查看详情"};
        new AlertDialog.Builder(this)
                .setTitle(s.name + " - " + s.artist)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) downloadSong(s);
                        else if (which == 1) toggleLock(s);
                        else showSongDetail(s);
                    }
                }).show();
    }

    private void downloadSong(final Song s) {
        if (s == null) return;
        final String q = AppConfig.getQuality(this);
        if (LocalCache.isCached(s, q, LocalCache.extFor(q))) {
            toast("已经在本地了");
            adapter.markCached(s.id, true);
            return;
        }
        toast("开始下载…");
        final String prevInfo = listInfo.getText().toString();
        final int hseq = headerSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final long[] lastPost = new long[]{0L};
                    LocalCache.download(MainActivity.this, s, q, LocalCache.extFor(q),
                            new LocalCache.Progress() {
                                @Override
                                public void onProgress(final long done, final long total) {
                                    // 每个 16KB 都回主线程刷文字 → 主线程被 post 淹没，
                                    // 这里按 400ms 节流，进度条照样在动
                                    long now = SystemClock.uptimeMillis();
                                    if (now - lastPost[0] < 400) return;
                                    lastPost[0] = now;
                                    main.post(new Runnable() {
                                        @Override public void run() {
                                            if (hseq != headerSeq) return;   // 已经切到别的列表
                                            if (total > 0) {
                                                listInfo.setText((done * 100 / total) + "% · "
                                                        + (done / 1024) + "/" + (total / 1024) + " KB");
                                            } else {
                                                listInfo.setText((done / 1024) + " KB");
                                            }
                                        }
                                    });
                                }
                            });
                    LocalCache.evictIfNeeded(AppConfig.getCacheMb(MainActivity.this));
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (hseq == headerSeq && prevInfo != null) {
                                listInfo.setText(prevInfo);      // 把进度文字还回去
                            }
                            toast("下载完成");
                            adapter.markCached(s.id, true);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (hseq == headerSeq && prevInfo != null) listInfo.setText(prevInfo);
                            toast("下载失败：" + msgOf(e));
                        }
                    });
                }
            }
        }).start();
    }

    private void toggleLock(Song s) {
        if (s == null) return;
        String q = AppConfig.getQuality(this);
        boolean now = LocalCache.isLocked(s, q);
        LocalCache.setLocked(s, q, !now);
        toast(now ? "已取消锁定" : "已锁定，不会被自动清理");
    }

    private void showSongDetail(Song s) {
        StringBuilder sb = new StringBuilder();
        sb.append("歌曲：").append(s.name).append("\n");
        sb.append("歌手：").append(s.artist).append("\n");
        if (s.albumName.length() > 0) sb.append("专辑：").append(s.albumName).append("\n");
        sb.append("时长：").append(formatTime(s.duration)).append("\n");
        sb.append("音质：").append(AppConfig.getQuality(this)).append("\n");
        sb.append("红心：").append(Liked.has(s.id) ? "是" : "否").append("\n");
        String qq = AppConfig.getQuality(this);
        boolean cached = LocalCache.isCached(s, qq, LocalCache.extFor(qq));
        sb.append("本地缓存：").append(cached ? "已缓存" : "未缓存");
        new AlertDialog.Builder(this)
                .setTitle("歌曲信息")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    /* ==================================================================== */
    /* 歌词                                                                  */
    /* ==================================================================== */

    private void loadLyrics(final Song s) {
        final long sid = s.id;
        lrcSongId = sid;
        lrcTimes.clear();
        lrcTexts.clear();
        lrcIdx = -1;
        lyricView.setText("歌词加载中…");
        lyricScroll.scrollTo(0, 0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String lrc = null;
                try {
                    if (lrcSongId != sid) return;      // 已经切歌，别再白跑一趟网络
                    lrc = ApiClient.lyric(MainActivity.this, sid);
                } catch (Exception e) {
                    lrc = null;
                }
                final String text = lrc;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (lrcSongId != sid) return;      // 已经切歌
                        setLyrics(text);
                    }
                });
            }
        }).start();
    }

    private void setLyrics(String lrc) {
        lrcTimes.clear();
        lrcTexts.clear();
        lrcIdx = -1;
        if (lrc != null && lrc.length() > 0) {
            String[] lines = lrc.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i].trim();
                if (ln.length() == 0) continue;
                List<Integer> stamps = new ArrayList<Integer>(2);
                String body = ln;
                while (body.startsWith("[")) {
                    int end = body.indexOf(']');
                    if (end <= 0) break;
                    int ms = parseStamp(body.substring(1, end));
                    if (ms < 0) break;
                    stamps.add(Integer.valueOf(ms));
                    body = body.substring(end + 1).trim();
                }
                if (stamps.isEmpty()) {
                    // [ti:]/[ar:]/[al:]/[by:]/[offset:] 这类元信息不是歌词，别渲染进歌词区
                    if (ln.charAt(0) == '[') continue;
                    lrcTimes.add(Integer.valueOf(-1));   // 纯文本歌词（整篇没有时间轴）
                    lrcTexts.add(ln);
                } else {
                    for (int k = 0; k < stamps.size(); k++) {
                        lrcTimes.add(stamps.get(k));
                        lrcTexts.add(body);
                    }
                }
            }
        }
        if (lrcTexts.isEmpty()) {
            lyricView.setText("暂无歌词");
            return;
        }
        if (hasTimeline()) sortLyrics();
        renderLyrics(-1);
    }

    /** 歌词是否带时间轴（纯文本歌词不跟随进度） */
    private boolean hasTimeline() {
        for (int i = 0; i < lrcTimes.size(); i++) {
            if (lrcTimes.get(i).intValue() >= 0) return true;
        }
        return false;
    }

    /** 一行挂多个时间标签时顺序会乱，这里按时间重排（行数很少，插入排序够） */
    private void sortLyrics() {
        for (int i = 1; i < lrcTimes.size(); i++) {
            int t = lrcTimes.get(i).intValue();
            String s = lrcTexts.get(i);
            int j = i - 1;
            while (j >= 0 && lrcTimes.get(j).intValue() > t) {
                lrcTimes.set(j + 1, lrcTimes.get(j));
                lrcTexts.set(j + 1, lrcTexts.get(j));
                j--;
            }
            lrcTimes.set(j + 1, Integer.valueOf(t));
            lrcTexts.set(j + 1, s);
        }
    }

    private void renderLyrics(int cur) {
        lrcIdx = cur;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < lrcTexts.size(); i++) {
            String line = lrcTexts.get(i);
            int start = sb.length();
            sb.append(line);
            int end = sb.length();
            if (end > start) {
                if (i == cur) {
                    sb.setSpan(new ForegroundColorSpan(C_ACCENT), start, end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    sb.setSpan(new ForegroundColorSpan(C_MUTED), start, end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (i < lrcTexts.size() - 1) sb.append("\n");
        }
        lyricView.setText(sb);
        scrollToLyric(cur);
    }

    private void scrollToLyric(final int idx) {
        if (idx < 0) return;
        lyricView.post(new Runnable() {
            @Override
            public void run() {
                Layout l = lyricView.getLayout();
                if (l == null) return;
                int offset = 0;
                for (int i = 0; i < idx && i < lrcTexts.size(); i++) {
                    offset += lrcTexts.get(i).length() + 1;
                }
                if (offset > lyricView.getText().length()) return;
                int line = l.getLineForOffset(offset);
                int y = l.getLineTop(line);
                lyricScroll.smoothScrollTo(0, Math.max(0, y - lyricScroll.getHeight() / 3));
            }
        });
    }

    private void updateLyricCursor(int positionMs) {
        if (!hasTimeline()) return;                 // 没有时间轴就不跟随
        int idx = -1;
        for (int i = 0; i < lrcTimes.size(); i++) {
            int t = lrcTimes.get(i).intValue();
            if (t < 0) continue;                    // [by:] 这类非时间行跳过
            if (t <= positionMs) idx = i;
            else break;
        }
        if (idx != lrcIdx) renderLyrics(idx);
    }

    private static int parseStamp(String s) {
        // 支持 mm:ss / mm:ss.S / mm:ss.SS / mm:ss.SSS
        int c = s.indexOf(':');
        if (c <= 0) return -1;
        try {
            int m = Integer.parseInt(s.substring(0, c).trim());
            String rest = s.substring(c + 1).trim();
            int dot = rest.indexOf('.');
            String sec = (dot >= 0) ? rest.substring(0, dot) : rest;
            String frac = (dot >= 0) ? rest.substring(dot + 1) : "";
            int secs = Integer.parseInt(sec.trim());
            int ms = 0;
            if (frac.length() > 0) {
                int f = Integer.parseInt(frac.trim());
                if (frac.length() == 1) ms = f * 100;
                else if (frac.length() == 2) ms = f * 10;
                else ms = f % 1000;
            }
            return (m * 60 + secs) * 1000 + ms;
        } catch (Exception e) {
            return -1;
        }
    }

    /* ==================================================================== */
    /* 右栏标签                                                              */
    /* ==================================================================== */

    private static String qualityLabel(String q) {
        if ("standard".equals(q)) return "128K";
        if ("high".equals(q)) return "192K";
        if ("lossless".equals(q)) return "无损";
        if ("hires".equals(q)) return "Hi-Res";
        if ("jymaster".equals(q)) return "超清母带";
        if ("jyeffect".equals(q)) return "高清环绕";
        if ("sky".equals(q)) return "沉浸环绕";
        if ("dolby".equals(q)) return "杜比全景";
        return "320K";
    }

    /* ==================================================================== */
    /* Player.Listener                                                       */
    /* ==================================================================== */

    @Override
    public void onTrackChanged(Song s, int index) {
        if (s == null) return;
        nowTitle.setText(s.name);
        // 歌手 + 专辑挤一行，右栏省下的高度全给歌词
        String art = s.artist == null ? "" : s.artist.trim();
        String alb = s.albumName == null ? "" : s.albumName.trim();
        nowArtist.setText(art.length() == 0 ? alb
                : (alb.length() == 0 ? art : art + " · " + alb));

        // 切歌必须把上一首的进度/时长/拖动状态清干净，否则旧进度条会挂在新歌下面
        userSeeking = false;
        seekPendingMs = -1;
        if (s.duration > 0) seek.setMax(s.duration);
        seek.setProgress(0);
        lyricScroll.scrollTo(0, 0);

        CoverLoader.load(cover, s.coverUrl, 320);
        updateBottomHeart();
        loadLyrics(s);
        player.prefetchNext();
        // 列表里「正在播放」的行要跟着换
        if (adapter != null) adapter.notifyDataSetChanged();
        // 列表跟着当前播放的歌滚动（车主：切歌后要能看见正在播哪首）
        scrollListToCurrent(s.id);
    }

    /** 回到列表最上面：把头部留白整段顶出去，让第一行贴着列表顶端 */
    private void scrollListToTop() {
        if (songList == null || adapter == null) return;
        songList.post(new Runnable() {
            @Override public void run() {
                int pad = listTopPad == null ? 0 : listTopPad.getHeight();
                songList.setSelectionFromTop(songList.getHeaderViewsCount(), -pad);
            }
        });
    }

    /**
     * 把中栏列表滚到正在播的那首，并且**固定放在列表正中**（车主定的）：
     * 上下各留一半，往下切歌时列表跟着走，正在播的永远在中间那行。
     * 用 post 等这一帧的布局跑完再滚，否则 notifyDataSetChanged 的重排会把滚动位置吃掉。
     */
    private void scrollListToCurrent(long songId) {
        if (listMode != LIST_SONGS || songList == null || adapter == null) return;
        int at = -1;
        for (int i = 0; i < currentSongs.size(); i++) {
            if (currentSongs.get(i).id == songId) { at = i; break; }
        }
        if (at < 0 || at >= adapter.getCount()) return;
        final int target = at;
        final int rowH = dp(72);
        songList.post(new Runnable() {
            @Override public void run() {
                int h = songList.getHeight();
                if (h <= 0) return;
                // 中间那行：上面留 (高 - 行高)/2；头部留白就是这段距离，
                // 所以直接把留白高度当 offset，行首正好落在正中
                int offset = Math.max(0, (h - rowH) / 2);
                songList.setSelectionFromTop(target + songList.getHeaderViewsCount(), offset);
            }
        });
    }

    @Override
    public void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_PLAYING:
                btnPlay.setImageResource(R.drawable.ic_pause);
                btnPlay.setEnabled(true);
                break;
            case Player.STATE_BUFFERING:
                btnPlay.setImageResource(R.drawable.ic_play);
                break;
            case Player.STATE_PAUSED:
                btnPlay.setImageResource(R.drawable.ic_play);
                break;
            case Player.STATE_COMPLETED:
                btnPlay.setImageResource(R.drawable.ic_play);
                break;
            default:
                btnPlay.setImageResource(R.drawable.ic_play);
                break;
        }
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        if (durationMs > 0) {
            if (seek.getMax() != durationMs) seek.setMax(durationMs);
            // 松手后的一两个回调还是旧位置：等真正靠近目标值再放开，否则滑块会抖回去
            if (seekPendingMs >= 0) {
                // 2.5 秒还没到位（seek 被丢弃/落在文件尾）就放弃顶住，别让滑块一直骗人
                if (SystemClock.uptimeMillis() > seekDeadline
                        || Math.abs(positionMs - seekPendingMs) <= 1500) {
                    seekPendingMs = -1;
                } else {
                    positionMs = seekPendingMs;
                }
            }
            if (!userSeeking) {
                seek.setProgress(positionMs);
            }
        }
        updateLyricCursor(positionMs);
    }

    @Override
    public void onError(String msg) {
        toast("播放出错：" + msg);
        btnPlay.setImageResource(R.drawable.ic_play);
    }

    @Override
    public void onQueueChanged() {
        updateBottomHeart();
    }

    /**
     * 私人 FM 播到列表末尾：自动拉下一批接上，不需要用户点。
     * 返回 true 让 Player 别停（保持在 BUFFERING，等新曲目到了直接播）。
     */
    @Override
    public boolean onQueueEnded() {
        if (!queueIsFm) return false;
        if (fmLoading) return true;          // 上一批还在路上，先顶住别停
        fmLoading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Song> more = null;
                String err = null;
                try {
                    more = ApiClient.personalFm(MainActivity.this);
                } catch (Exception e) {
                    err = msgOf(e);
                }
                final List<Song> got = more;
                final String e2 = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        fmLoading = false;
                        if (!queueIsFm) return;
                        if (got == null || got.isEmpty()) {
                            toast("FM 续播失败" + (e2 == null ? "" : "：" + e2));
                            player.stop();
                            return;
                        }
                        appendFmBatch(got);
                    }
                });
            }
        }).start();
        return true;
    }

    /** 把新一批 FM 曲目接到队列尾部，然后从这一批的第一首开始播 */
    private void appendFmBatch(List<Song> more) {
        // 去重：FM 接口偶尔会把刚播过的又发回来
        List<Song> fresh = new ArrayList<Song>();
        for (int i = 0; i < more.size(); i++) {
            Song s = more.get(i);
            boolean dup = false;
            for (int k = 0; k < currentSongs.size(); k++) {
                if (currentSongs.get(k).id == s.id) { dup = true; break; }
            }
            if (!dup) fresh.add(s);
        }
        if (fresh.isEmpty()) {
            toast("FM 没有新歌了");
            player.stop();
            return;
        }
        int at = player.getQueue().size();
        player.addToQueue(fresh);
        if (fmMode) {                        // 列表还停在 FM 页才更新中栏
            currentSongs.addAll(fresh);
            adapter.addRows(buildRows(fresh));
        }
        player.playIndex(at);
    }

    /** Song → 列表行（showSongs 与 FM 续批共用） */
    private List<Row> buildRows(List<Song> songs) {
        List<Row> rows = new ArrayList<Row>(songs.size());
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            Row r = new Row();
            r.title = s.name;
            r.sub = s.artist;
            r.heart = true;
            r.songId = s.id;
            r.song = s;
            r.cached = false;
            rows.add(r);
        }
        return rows;
    }

    /* ==================================================================== */
    /* 工具                                                                  */
    /* ==================================================================== */

    private void setHeader(String title, String info) {
        headerSeq++;
        listTitle.setText(title);
        listInfo.setText(info);
    }

    private void showHint(String msg) {
        emptyHint.setText(msg);
        emptyHint.setVisibility(View.VISIBLE);
        songList.setVisibility(View.GONE);
    }

    private void hideHint() {
        emptyHint.setVisibility(View.GONE);
        songList.setVisibility(View.VISIBLE);
    }

    private static String msgOf(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        if (m.length() > 90) m = m.substring(0, 90) + "…";
        return m;
    }

    static String formatTime(int ms) {
        if (ms <= 0) return "00:00";
        int s = ms / 1000;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        if (h > 0) return String.format(Locale.CHINA, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.CHINA, "%02d:%02d", m, sec);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ==================================================================== */
    /* 物理按键 / 生命周期                                                   */
    /* ==================================================================== */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                player.toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                player.next();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                player.prev();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true;   // 车机上不退出，误触返回会打断播放
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 车机场景：切后台不停止播放
    }

    @Override
    protected void onDestroy() {
        // 先摘监听再释放：Player 里还排着队的回调（prepare/进度）不该再摸已经销毁的界面
        if (player != null) {
            // 只摘监听，不释放：退出界面后音乐继续放（前台服务+通知栏控制），
            // 这正是「切到导航 App 音乐不断」的前提。要停就从通知栏按暂停/停止。
            player.setListener(null);   // 字段保留：残留的后台线程回调还会读 getCurrent()，置 null 会 NPE
        }
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
