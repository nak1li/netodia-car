package com.nakili.ncmcar;

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
