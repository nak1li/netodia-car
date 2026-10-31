package com.nakili.ncmcar;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 API 客户端。
 *
 * 只用 HttpURLConnection + org.json（Android 自带），不引任何三方库，
 * APK 体积最小。
 */
public class ApiClient {
    private static final int TIMEOUT = 12000;
    /** 单次响应体上限，防止异常服务端把车机 OOM 掉 */
    private static final int MAX_BODY = 4 * 1024 * 1024;

    public static String server(Context c) {
        String s = AppConfig.getServer(c);
        if (s == null) s = "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.length() > 0 && s.indexOf("://") < 0) s = "http://" + s;   // 用户少打 scheme 也认
        return s;
    }

    /** 服务端地址是否可用（设置页里存了空串/半截地址时早失败，别等超时） */
    public static boolean serverUsable(Context c) {
        String s = server(c);
        return s.length() > 0 && s.indexOf("://") > 0;
    }

    private static HttpURLConnection conn(Context c, String path, boolean post) throws Exception {
        if (c == null) throw new Exception("缺少上下文");
        URL u = new URL(server(c) + path);
        HttpURLConnection cn = (HttpURLConnection) u.openConnection();
        cn.setConnectTimeout(TIMEOUT);
        cn.setReadTimeout(30000);
        cn.setRequestProperty("X-NCM-Token", AppConfig.getToken(c));
        if (post) {
            cn.setRequestMethod("POST");
        } else {
            cn.setRequestMethod("GET");
        }
        return cn;
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = is.read(buf)) > 0) {
            total += n;
            if (total > MAX_BODY) throw new Exception("服务端响应过大");
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    public static String get(Context c, String path) throws Exception {
        HttpURLConnection cn = conn(c, path, false);
        try {
            int code = cn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? cn.getInputStream() : cn.getErrorStream();
            String body = is == null ? "" : readAll(is);
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " " + body);
            }
            return body;
        } finally {
            cn.disconnect();
        }
    }

    public static String getJson(Context c, String path, String key) throws Exception {
        return get(c, path + (path.indexOf('?') >= 0 ? "&" : "?") + "key=" + key);
    }

    public static boolean healthCheck(Context c) {
        try {
            if (c == null) return false;
            HttpURLConnection cn = (HttpURLConnection) new URL(server(c) + "/api/health").openConnection();
            cn.setConnectTimeout(5000);
            cn.setReadTimeout(5000);
            cn.setRequestProperty("X-NCM-Token", "");
            int code = cn.getResponseCode();
            cn.disconnect();
            return code > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------- 业务方法 ---------- */

    public static List<Song> search(Context c, String q, int type) throws Exception {
        String path = "/api/search?q=" + URLEncoder.encode(q) + "&type=" + type + "&limit=30";
        JSONObject o = new JSONObject(get(c, path));
        JSONArray arr = o.optJSONArray("songs");
        List<Song> out = new ArrayList<Song>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Song s = Song.fromJson(arr.getJSONObject(i));
                if (s != null) out.add(s);
            }
        }
        return out;
    }

    public static List<Playlist> recommendedPlaylists(Context c) throws Exception {
        JSONObject o = new JSONObject(get(c, "/api/playlist/recommend?limit=30"));
        JSONArray arr = o.optJSONArray("playlists");
        List<Playlist> out = new ArrayList<Playlist>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                out.add(Playlist.fromJson(arr.getJSONObject(i)));
            }
        }
        return out;
    }

    public static List<Song> playlistSongs(Context c, long id) throws Exception {
        JSONObject o = new JSONObject(get(c, "/api/playlist/detail?id=" + id + "&limit=500"));
        JSONArray arr = o.optJSONArray("songs");
        List<Song> out = new ArrayList<Song>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Song s = Song.fromJson(arr.getJSONObject(i));
                if (s != null) out.add(s);
            }
        }
        return out;
    }

    public static String lyric(Context c, long id) throws Exception {
        JSONObject o = new JSONObject(get(c, "/api/lyric?id=" + id));
        return o.optString("lrc", "");
    }

    /** 音频流地址：服务端代理 URL，带 token（MediaPlayer 不能带自定义头，只能走 query） */
    public static String streamUrl(Context c, long id, String quality) {
        String q = (quality == null || quality.length() == 0) ? "exhigh" : quality;
        return server(c) + "/api/stream?id=" + id + "&quality=" + URLEncoder.encode(q)
                + "&ext=" + LocalCache.extFor(q)
                + "&token=" + URLEncoder.encode(AppConfig.getToken(c));
    }

    static class URLEncoder {
        static String encode(String s) {
            try {
                return java.net.URLEncoder.encode(s, "UTF-8");
            } catch (Exception e) {
                return s;
            }
        }
    }

    /* ---------------- 我的音乐：每日推荐 / 红心 / 歌单 ----------------
     *
     * 这几个接口要求服务端已登录网易云。未登录时服务端回 403 + {error:'login_required'}，
     * 所以不能直接用 get()（它对非 2xx 一律抛「HTTP 403 ...」），
     * 否则界面上只能显示一串原始 JSON。统一走 exec()：状态码和 body 都拿到，
     * 由 obj() 把「需要登录」翻译成 NeedLoginException。
     */

    /** 需要登录网易云账号（服务端未扫码登录） */
    public static class NeedLoginException extends Exception {
        public NeedLoginException() { super("需要登录网易云（找小胖扫码）"); }
    }

    private static final class Resp {
        int status;
        String body = "";
    }

    private static Resp exec(Context c, String path) throws Exception {
        HttpURLConnection cn = conn(c, path, false);
        try {
            Resp r = new Resp();
            r.status = cn.getResponseCode();
            InputStream is = (r.status >= 200 && r.status < 300)
                    ? cn.getInputStream() : cn.getErrorStream();
            r.body = (is == null) ? "" : readAll(is);
            return r;
        } finally {
            cn.disconnect();
        }
    }

    private static JSONObject obj(Resp r) throws Exception {
        String t = (r.body == null) ? "" : r.body.trim();
        if (t.isEmpty()) throw new Exception("服务端无响应（HTTP " + r.status + "）");
        JSONObject o;
        try {
            o = new JSONObject(t);
        } catch (Exception e) {
            throw new Exception("服务端返回不是 JSON（HTTP " + r.status + "）");
        }
        if ("login_required".equals(o.optString("error", ""))) throw new NeedLoginException();
        int biz = o.optInt("code", 200);
        if (biz == 301 || biz == 302) throw new NeedLoginException();
        if (biz != 200 && biz != 0) throw new Exception("接口返回 code=" + biz);
        if (r.status >= 400) throw new Exception("HTTP " + r.status);
        return o;
    }

    private static List<Song> songsOf(JSONArray arr) {
        List<Song> out = new ArrayList<Song>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                try {
                    Song s = Song.fromJson(arr.getJSONObject(i));
                    if (s != null) out.add(s);
                } catch (Exception e) { }
            }
        }
        return out;
    }

    /** 每日推荐（按天个性化，未登录 → NeedLoginException） */
    public static List<Song> recommendSongs(Context c, int limit) throws Exception {
        JSONObject o = obj(exec(c, "/api/recommend/songs?limit=" + limit));
        return songsOf(o.optJSONArray("songs"));
    }

    /** 红心歌曲 id 列表（用于渲染每行与底栏的红心状态） */
    public static List<Long> likelistIds(Context c) throws Exception {
        JSONObject o = obj(exec(c, "/api/likelist"));
        List<Long> out = new ArrayList<Long>();
        JSONArray arr = o.optJSONArray("ids");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                long id = arr.optLong(i, 0L);
                if (id > 0) out.add(Long.valueOf(id));
            }
        }
        return out;
    }

    /** 红心歌曲（带曲目详情，最近红心在前） */
    public static List<Song> likelistSongs(Context c, int limit) throws Exception {
        JSONObject o = obj(exec(c, "/api/likelist/songs?limit=" + limit));
        return songsOf(o.optJSONArray("songs"));
    }

    /** 私人 FM（每次一小批，多为 3 首） */
    public static List<Song> personalFm(Context c) throws Exception {
        JSONObject o = obj(exec(c, "/api/fm"));
        return songsOf(o.optJSONArray("songs"));
    }

    /** 心动模式：以某首歌为种子拿相似歌曲（服务端已滚雪球到 limit 首） */
    public static List<Song> similarSongs(Context c, long seedId, int limit, String exclude) throws Exception {
        String p = "/api/similar?id=" + seedId + "&limit=" + limit;
        if (exclude != null && exclude.length() > 0) p += "&exclude=" + exclude;
        JSONObject o = obj(exec(c, p));
        return songsOf(o.optJSONArray("songs"));
    }

    /** 我的歌单 */
    public static List<Playlist> userPlaylists(Context c, int limit) throws Exception {
        JSONObject o = obj(exec(c, "/api/user/playlists?limit=" + limit));
        List<Playlist> out = new ArrayList<Playlist>();
        JSONArray arr = o.optJSONArray("playlists");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                try {
                    Playlist p = Playlist.fromJson(arr.getJSONObject(i));
                    if (p != null) out.add(p);
                } catch (Exception e) { }
            }
        }
        return out;
    }

    /** 红心 / 取消红心；失败抛异常（调用方回滚本地状态） */
    public static void setLike(Context c, long id, boolean like) throws Exception {
        Resp r = exec(c, "/api/like?id=" + id + "&like=" + (like ? "1" : "0"));
        // 成功时服务端可能回 204 / 空 body：不能当成失败，否则界面会回滚一次成功操作
        String t = (r.body == null) ? "" : r.body.trim();
        if (t.length() == 0) {
            if (r.status >= 200 && r.status < 300) return;
            throw new Exception("HTTP " + r.status);
        }
        obj(r);
    }
}
