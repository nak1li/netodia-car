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
import android.content.SharedPreferences;

/**
 * 配置：服务端地址、token、缓存上限、音质。
 *
 * 重要：token 和地址不硬编码在 APK 里 —— APK 装上车机后可能永远不再重装，
 * 配置必须能在应用内改（设置页）或从服务端下发。硬编码只作为 fallback。
 */
public class AppConfig {
    private static final String PREFS = "ncm_config";
    private static final String K_SERVER = "server";
    private static final String K_TOKEN = "token";
    private static final String K_QUALITY = "quality";
    private static final String K_CACHE_MB = "cache_mb";
    private static final String K_PREFETCH = "prefetch";
    private static final String K_LAST_SONG = "last_song";
    private static final String K_LAST_LIST = "last_list";

    // fallback：出厂默认值，可在设置页改
    private static final String DEF_SERVER = Secrets.SERVER;
    // 构建时由 build.sh 从 ~/.hermes/secrets/ncm.token 注入（见 Secrets.java）
    private static final String DEF_TOKEN = Secrets.TOKEN;

    public static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getServer(Context c) {
        // 优先级：用户手填 > 自动探测上次可用 > 构建时注入的默认值
        String user = p(c).getString(K_SERVER, "");
        if (user != null && user.length() > 0) return user;
        String last = p(c).getString("last_good_server", "");
        if (last != null && last.length() > 0) return last;
        return DEF_SERVER;
    }

    public static void setServer(Context c, String v) {
        p(c).edit().putString(K_SERVER, v.trim()).apply();
    }

    public static String getToken(Context c) {
        return p(c).getString(K_TOKEN, DEF_TOKEN);
    }

    public static void setToken(Context c, String v) {
        p(c).edit().putString(K_TOKEN, v.trim()).apply();
    }

    /** 音质：standard / exhigh / lossless */
    public static String getQuality(Context c) {
        return p(c).getString(K_QUALITY, "exhigh");
    }

    public static void setQuality(Context c, String v) {
        p(c).edit().putString(K_QUALITY, v).apply();
    }

    /** 车机本地缓存上限（MB） */
    public static int getCacheMb(Context c) {
        return p(c).getInt(K_CACHE_MB, 512);
    }

    public static void setCacheMb(Context c, int v) {
        p(c).edit().putInt(K_CACHE_MB, v).apply();
    }

    /** 是否开启预取（仅 WiFi 下生效） */
    public static boolean isPrefetchEnabled(Context c) {
        return p(c).getBoolean(K_PREFETCH, true);
    }

    public static void setPrefetchEnabled(Context c, boolean v) {
        p(c).edit().putBoolean(K_PREFETCH, v).apply();
    }

    public static int getLastSongId(Context c) {
        return p(c).getInt(K_LAST_SONG, 0);
    }

    public static void setLastSongId(Context c, int id) {
        p(c).edit().putInt(K_LAST_SONG, id).apply();
    }

    public static String getLastList(Context c) {
        return p(c).getString(K_LAST_LIST, "");
    }

    public static void setLastList(Context c, String v) {
        p(c).edit().putString(K_LAST_LIST, v).apply();
    }
}
