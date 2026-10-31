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

import org.json.JSONArray;
import org.json.JSONObject;

/** 曲目模型 */
public class Song {
    public long id;
    public String name = "";
    public String artist = "";
    public long albumId;
    public String albumName = "";
    public String coverUrl = "";
    public int duration;   // ms
    public int fee;

    public static Song fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            Song s = new Song();
            s.id = o.getLong("id");
            s.name = o.optString("name", "");
            s.coverUrl = o.optString("cover", "");

            JSONArray ar = o.optJSONArray("artists");
            if (ar != null && ar.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ar.length(); i++) {
                    if (i > 0) sb.append(" / ");
                    sb.append(ar.getJSONObject(i).optString("name", ""));
                }
                s.artist = sb.toString();
            }

            JSONObject al = o.optJSONObject("album");
            if (al != null) {
                s.albumId = al.optLong("id", 0);
                s.albumName = al.optString("name", "");
                s.coverUrl = s.coverUrl.isEmpty() ? al.optString("pic", "") : s.coverUrl;
            }
            s.duration = o.optInt("duration", 0);
            s.fee = o.optInt("fee", 0);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** 缓存文件名：songId.quality.ext —— 与服务端键一致 */
    public String cacheName(String quality, String ext) {
        return id + "." + quality + "." + ext;
    }

    @Override
    public String toString() {
        return name + " - " + artist;
    }
}
