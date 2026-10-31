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

import org.json.JSONObject;

/** 歌单模型 */
public class Playlist {
    public long id;
    public String name = "";
    public String coverUrl = "";
    public String creator = "";
    public int trackCount;

    public static Playlist fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            Playlist p = new Playlist();
            p.id = o.getLong("id");
            p.name = o.optString("name", "");
            p.coverUrl = o.optString("cover", "");
            p.creator = o.optString("creator", "");
            p.trackCount = o.optInt("trackCount", 0);
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
