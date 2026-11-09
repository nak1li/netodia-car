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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 断点播放：把「听到哪」落盘，下次开机接着放。
 *
 * 为什么不用 onDestroy/onPause 保存：车机是直接拔钥匙断电，进程被 kill -9，
 * 生命周期回调根本不保证执行。所以必须「播放中定时落盘」。
 *
 * 为什么存整条队列而不是歌单 id：开机时服务端可能还没连上（甚至没网），
 * 存 id 就得重新拉歌单、还要处理歌单变了/歌被删了，容易恢复成空。
 * 存歌曲元数据（不含音频）只有几十 KB，开机无需网络就能把上次那一列原样摆出来。
 *
 * 两级写入，避免每 5 秒序列化 300 首歌：
 *   - full：队列 + 当前歌 + 音质。切歌/暂停/拖动/退出时写。
 *   - cursor：只写「第几首、第几毫秒、是否在播」。播放中每 5 秒写一次，很小。
 * 读取时用 cursor 覆盖 full 的进度（仅当指到同一首）。
 */
public class ResumeState {
    private static final String K_FULL = "resume_full";
    private static final String K_CURSOR = "resume_cursor";
    /** 队列最长存这么多首：私人 FM/红心几千首时不至于让 prefs 爆炸 */
    private static final int MAX_SONGS = 300;
    /** 超过一周的断点没意义了（歌单/心情都变了） */
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final int VER = 1;

    public List<Song> songs = new ArrayList<Song>();
    public int index = -1;
    public int posMs = 0;
    public boolean playing = false;
    public String quality = "";
    public long ts = 0;

    public boolean valid() {
        return index >= 0 && index < songs.size();
    }

    /** 整份落盘。切歌、暂停/继续、拖动进度、退出时调用。 */
    public static void save(Context c, List<Song> queue, int index, int posMs,
                            boolean playing, String quality) {
        if (c == null || queue == null || queue.isEmpty()) return;
        try {
            int start = 0;
            int count = queue.size();
            int idx = index;
            if (count > MAX_SONGS) {
                // 只存当前曲附近的一段，保证 index 落在窗口里
                start = Math.max(0, Math.min(index - MAX_SONGS / 2, count - MAX_SONGS));
                count = MAX_SONGS;
                idx = index - start;
            }
            JSONArray arr = new JSONArray();
            for (int i = start; i < start + count; i++) arr.put(toJson(queue.get(i)));
            JSONObject o = new JSONObject();
            o.put("v", VER);
            o.put("ts", System.currentTimeMillis());
            o.put("i", idx);
            o.put("pos", Math.max(0, posMs));
            o.put("play", playing);
            o.put("q", quality == null ? "" : quality);
            o.put("s", arr);
            AppConfig.p(c).edit().putString(K_FULL, o.toString()).apply();
            saveCursor(c, index, posMs, playing);
        } catch (Exception e) {
            // 落盘失败不影响播放
        }
    }

    /** 只更新进度（很小的一段）。播放中每 5 秒调一次。 */
    public static void saveCursor(Context c, int index, int posMs, boolean playing) {
        if (c == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("i", index);
            o.put("pos", Math.max(0, posMs));
            o.put("play", playing);
            o.put("ts", System.currentTimeMillis());
            AppConfig.p(c).edit().putString(K_CURSOR, o.toString()).apply();
        } catch (Exception e) { }
    }

    /** 读上次的断点；没有/过期/损坏都返回 null。 */
    public static ResumeState load(Context c) {
        if (c == null) return null;
        SharedPreferences sp = AppConfig.p(c);
        String raw = sp.getString(K_FULL, "");
        if (raw == null || raw.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(raw);
            long ts = o.optLong("ts", 0);
            if (ts <= 0 || System.currentTimeMillis() - ts > MAX_AGE_MS) return null;

            ResumeState r = new ResumeState();
            r.ts = ts;
            r.index = o.optInt("i", -1);
            r.posMs = Math.max(0, o.optInt("pos", 0));
            r.playing = o.optBoolean("play", false);
            r.quality = o.optString("q", "");
            JSONArray arr = o.optJSONArray("s");
            if (arr == null) return null;
            for (int i = 0; i < arr.length(); i++) {
                Song s = fromJson(arr.optJSONObject(i));
                if (s != null && s.id > 0) r.songs.add(s);
            }
            if (!r.valid()) return null;

            // cursor 比 full 新，且指同一首 → 用它的进度（full 只在切歌时才更新队列）
            String cur = sp.getString(K_CURSOR, "");
            if (cur != null && cur.length() > 0) {
                JSONObject co = new JSONObject(cur);
                if (co.optInt("i", -2) == r.index
                        && co.optLong("ts", 0) >= ts) {
                    r.posMs = Math.max(0, co.optInt("pos", r.posMs));
                    r.playing = co.optBoolean("play", r.playing);
                }
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** 用户主动清空（比如变了账号/想从头开始）时调。 */
    public static void clear(Context c) {
        if (c == null) return;
        AppConfig.p(c).edit().remove(K_FULL).remove(K_CURSOR).apply();
    }

    private static JSONObject toJson(Song s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", s.id);
        o.put("name", s.name == null ? "" : s.name);
        o.put("artist", s.artist == null ? "" : s.artist);
        o.put("albumId", s.albumId);
        o.put("albumName", s.albumName == null ? "" : s.albumName);
        o.put("coverUrl", s.coverUrl == null ? "" : s.coverUrl);
        o.put("duration", s.duration);
        o.put("fee", s.fee);
        return o;
    }

    private static Song fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            Song s = new Song();
            s.id = o.optLong("id", 0);
            s.name = o.optString("name", "");
            s.artist = o.optString("artist", "");
            s.albumId = o.optLong("albumId", 0);
            s.albumName = o.optString("albumName", "");
            s.coverUrl = o.optString("coverUrl", "");
            s.duration = o.optInt("duration", 0);
            s.fee = o.optInt("fee", 0);
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
