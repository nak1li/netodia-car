package com.nakili.ncmcar;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 服务端地址解析（自愈）。
 *
 * 为什么需要这一层：
 * 车机连家里 WiFi 时走局域网地址（最快）；
 * 开车出门用 4G，只能走家里的公网 IP；而家里的 PPPoE 公网 IP 会变。
 *
 * 因此地址不能硬编码一个。策略（按 skill 的规矩）：
 *   1. 先用「上次成功过的地址」（记住就不必每次试错）
 *   2. 再试 LAN 地址（在家时命中，零公网流量）
 *   3. 再试出厂内置的公网 IP（大概率已过期，但比没有强）
 *   4. 全失败才去查 gist 拿当前公网 IP（只在失败后查，成功就不查，省一跳）
 *
 * gist 里的公网 IP 由家里 cron 每 5 分钟刷新（只在变化时写）。
 */
public class ServerResolver {

    // 走车机 agent 的公网入口 28473，路径前缀 /ncm。
    // 不再用 28475：那个端口没有公网映射，出门就废。
    // 28473 本来就是 agent 的通道（已有 DNAT），音乐 API 挂在它下面，
    // 路由器一行不用改，且家里和外面走的是同一条路径 —— 只验一次就够。
    // 这些地址都来自构建时注入的 Secrets（源码里不留任何私人 IP / 固定外链）。
    // 留空则跳过对应探测，只用设置里填的服务器地址。
    private static final String LAN = Secrets.LAN;
    private static final String BAKED_PUBLIC_IP = Secrets.PUBLIC_IP;
    private static final int PORT = 28473;
    private static final String PATH = "/ncm";
    private static final String GIST_RAW = Secrets.IP_SOURCE;

    private static final String K_LAST_GOOD = "last_good_server";

    /** 依次探测，返回第一个可用的 base url；全不可用返回 null */
    public static String resolve(Context c, int timeoutMs) {
        // 1) 上次成功的
        String last = AppConfig.p(c).getString(K_LAST_GOOD, "");
        if (!last.isEmpty() && ping(last, timeoutMs)) {
            return last;
        }

        // 2) LAN（构建时没注入就跳过）
        if (LAN != null && LAN.length() > 0 && ping(LAN, timeoutMs)) {
            remember(c, LAN);
            return LAN;
        }

        // 3) 出厂内置公网 IP（没配置就跳过）
        if (BAKED_PUBLIC_IP != null && BAKED_PUBLIC_IP.length() > 0) {
        String baked = "http://" + BAKED_PUBLIC_IP + ":" + PORT + PATH;
        if (ping(baked, timeoutMs)) {
            remember(c, baked);
            return baked;
        }

        }

        // 4) 查固定地址拿当前公网 IP（只在前面全失败时才查）
        if (GIST_RAW == null || GIST_RAW.length() == 0) return null;
        String ip = fetchCurrentPublicIp();
        if (ip != null) {
            String url = "http://" + ip + ":" + PORT + PATH;
            if (ping(url, timeoutMs)) {
                remember(c, url);
                return url;
            }
        }
        return null;
    }

    private static void remember(Context c, String base) {
        // 只记「上次可用」，绝不回写用户设置 —— 否则用户在设置里填的自定义地址
        // 会被下次开机探测结果顶掉，表现为「改了不生效」
        AppConfig.p(c).edit().putString(K_LAST_GOOD, base).apply();
    }

    /** 探测 /api/health */
    private static boolean ping(String base, int timeoutMs) {
        try {
            HttpURLConnection cn = (HttpURLConnection) new URL(base + "/api/health").openConnection();
            cn.setConnectTimeout(timeoutMs);
            cn.setReadTimeout(timeoutMs);
            cn.setRequestProperty("X-NCM-Token", "");
            int code = cn.getResponseCode();
            cn.disconnect();
            // 401 也算「服务端活着」（token 由调用方带）
            return code == 200 || code == 401;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从 gist 读公网 IP，做格式校验 */
    private static String fetchCurrentPublicIp() {
        try {
            HttpURLConnection cn = (HttpURLConnection) new URL(GIST_RAW).openConnection();
            // Android 4.4 的 HttpsURLConnection 默认只开 TLS 1.0/1.1，
            // 而 GitHub 早在 2022 就停了，不显式启用 TLS 1.2 这条自愈路径必然失败
            if (cn instanceof javax.net.ssl.HttpsURLConnection) {
                try {
                    javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                    sc.init(null, null, null);
                    ((javax.net.ssl.HttpsURLConnection) cn).setSSLSocketFactory(sc.getSocketFactory());
                } catch (Exception e) { }
            }
            cn.setConnectTimeout(8000);
            cn.setReadTimeout(8000);
            if (cn.getResponseCode() != 200) return null;
            InputStream is = cn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n;
            while ((n = is.read(b)) > 0) bos.write(b, 0, n);
            is.close();
            cn.disconnect();
            String s = bos.toString("UTF-8").trim();
            return isValidIpv4(s) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 脏数据会把「配置写坏」变成「永久连不上」，所以必须校验 */
    private static boolean isValidIpv4(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return false;
            }
            int v;
            try { v = Integer.parseInt(p); } catch (Exception e) { return false; }
            if (v < 0 || v > 255) return false;
        }
        return true;
    }
}
