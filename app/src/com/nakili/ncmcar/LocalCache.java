package com.nakili.ncmcar;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 车机本地缓存（L1）。
 *
 * 设计要点：
 * 1. 缓存「音频字节」而非 URL —— 网易云 URL 有时效，字节永久有效。
 * 2. 键含音质：songId.quality.ext，音质变了自然失效，不串味。
 * 3. .part 临时文件 + 原子 rename —— 断电/被杀不会留下半截的"完整文件"。
 * 4. 断点续传：Range 请求接着下，弱网下车机不被卡死。
 * 5. 完整性校验：Content-Length 与实际字节数一致才算数。
 * 6. LRU 淘汰 + 手动锁定（收藏永不被淘汰）。
 * 7. 预取只走 WiFi —— 4G 下不烧流量。
 *
 * 并发：同一个文件名用一把锁串起来。预取线程 + 用户点「下载」很容易同时命中同一首，
 * 两个 RandomAccessFile 往同一个 .part 里写 → 文件损坏 / rename 失败，而且原来的
 * 代码在续传分支还有 fos==null 的空指针。现在统一成单句柄 + 每文件互斥。
 */
public class LocalCache {

    private static File CACHE_DIR;
    private static File LOCK_DIR;

    /** 每个缓存文件名一把锁；只放永不回收的小对象，条目数 = 缓存过的歌数 */
    private static final Map<String, Object> LOCKS = new HashMap<String, Object>();

    public static void init(Context c) {
        if (CACHE_DIR != null) return;
        if (c == null) return;                 // 还没 init 就被调用：静默降级，别 NPE
        synchronized (LocalCache.class) {
            if (CACHE_DIR != null) return;
            CACHE_DIR = new File(c.getFilesDir(), "cache");
            LOCK_DIR = new File(c.getFilesDir(), "locked");
            CACHE_DIR.mkdirs();
            LOCK_DIR.mkdirs();
        }
    }

    public static boolean ready() { return CACHE_DIR != null; }

    public static File cacheDir() { return CACHE_DIR; }

    public static File fileFor(Song s, String quality, String ext) {
        if (CACHE_DIR == null) init(null);
        if (CACHE_DIR == null) return new File(s.cacheName(quality, ext));   // 未初始化：相对路径，必然不存在
        return new File(CACHE_DIR, s.cacheName(quality, ext));
    }

    /** 该音质下的扩展名（服务端按 type 决定） */
    /** 该档位是不是无损/母带类（返回 FLAC 文件） */
    public static boolean isLossless(String q) {
        if (q == null) return false;
        return "lossless".equals(q) || "hires".equals(q) || "jymaster".equals(q)
                || "jyeffect".equals(q) || "sky".equals(q) || "dolby".equals(q);
    }

    /** 缓存文件扩展名必须跟实际编码一致，否则「我的下载」里显示 mp3 其实是 flac */
    public static String extFor(String quality) {
        return isLossless(quality) ? "flac" : "mp3";
    }

    public static boolean isCached(Song s, String quality, String ext) {
        if (s == null) return false;
        File f = fileFor(s, quality, ext);
        return f.exists() && f.length() > 0;
    }

    public static long cachedSize(Song s, String quality, String ext) {
        File f = fileFor(s, quality, ext);
        return f.exists() ? f.length() : 0;
    }

    /* ---------------- 锁定（收藏） ---------------- */

    public static boolean isLocked(Song s, String quality) {
        if (s == null || LOCK_DIR == null) return false;
        return new File(LOCK_DIR, s.id + "." + quality).exists();
    }

    public static void setLocked(Song s, String quality, boolean lock) {
        if (s == null || LOCK_DIR == null) return;
        File f = new File(LOCK_DIR, s.id + "." + quality);
        if (lock) {
            try { f.createNewFile(); } catch (Exception e) { }
        } else {
            f.delete();
        }
    }

    /* ---------------- 下载 ---------------- */

    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    private static Object lockFor(String key) {
        synchronized (LOCKS) {
            Object o = LOCKS.get(key);
            if (o == null) {
                o = new Object();
                LOCKS.put(key, o);
            }
            return o;
        }
    }

    /**
     * 下载到本地缓存（边下边写 .part，完成后 rename）。
     * 支持断点续传：若 .part 已存在且服务端支持 Range，则接着下。
     *
     * @return 完整文件的 File；失败抛异常
     */
    public static File download(Context c, Song s, String quality, String ext, Progress prog)
            throws Exception {
        if (c == null) throw new Exception("缺少上下文");
        init(c);
        if (!ready()) throw new Exception("缓存目录不可用");
        File finalFile = fileFor(s, quality, ext);
        if (finalFile.exists() && finalFile.length() > 0) return finalFile;

        synchronized (lockFor(finalFile.getName())) {
            // 拿锁后重新判断：等锁的时候可能已经被另一个线程下完了
            if (finalFile.exists() && finalFile.length() > 0) return finalFile;
            return downloadLocked(c, s, quality, ext, finalFile, prog);
        }
    }

    private static File downloadLocked(Context c, Song s, String quality, String ext,
                                       File finalFile, Progress prog) throws Exception {
        if (!CACHE_DIR.exists()) CACHE_DIR.mkdirs();
        File partFile = new File(CACHE_DIR, finalFile.getName() + ".part");

        for (int attempt = 0; attempt < 2; attempt++) {
            long existing = partFile.exists() ? partFile.length() : 0;
            String url = ApiClient.streamUrl(c, s.id, quality);

            HttpURLConnection cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(15000);
            cn.setReadTimeout(60000);
            cn.setRequestProperty("X-NCM-Token", AppConfig.getToken(c));
            boolean append = false;
            long total = -1;
            try {
                if (existing > 0) cn.setRequestProperty("Range", "bytes=" + existing + "-");

                int code = cn.getResponseCode();

                if (code == 416 && existing > 0) {
                    // Range 越界：本地 .part 和服务端对不上，删掉重下
                    partFile.delete();
                    continue;
                }
                if (code != 200 && code != 206) throw new Exception("HTTP " + code);

                append = (code == 206 && existing > 0);

                String cr = cn.getHeaderField("Content-Range");
                if (cr != null && cr.indexOf('/') >= 0) {
                    try { total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); }
                    catch (Exception e) { total = -1; }
                } else {
                    // 用 getContentLength()：gzip 透明解压时它返回 -1，这时不做长度校验，比拿原始头安全
                    total = cn.getContentLength();
                }
                InputStream is = cn.getInputStream();
                RandomAccessFile raf = null;
                try {
                    raf = new RandomAccessFile(partFile, "rw");
                    if (append) {
                        raf.seek(existing);
                    } else {
                        raf.setLength(0);
                        raf.seek(0);
                    }
                    byte[] buf = new byte[16384];
                    int n;
                    long downloaded = append ? existing : 0;
                    while ((n = is.read(buf)) > 0) {
                        raf.write(buf, 0, n);
                        downloaded += n;
                        if (prog != null) prog.onProgress(downloaded, total);
                    }
                    try { raf.getFD().sync(); } catch (Exception e) { }
                } finally {
                    if (raf != null) { try { raf.close(); } catch (Exception e) { } }
                    try { is.close(); } catch (Exception e) { }
                }
            } finally {
                cn.disconnect();
            }

            long finalSize = partFile.length();
            if (finalSize == 0) {
                partFile.delete();
                throw new Exception("下载内容为空");
            }
            if (total > 0 && finalSize != total) {
                if (finalSize < total) {
                    // 传输被掐断：.part 是完整前缀，留着下次续传（别删！删了就每断一次重头下）
                    throw new Exception("网络中断（" + finalSize + "/" + total + "）");
                }
                // 比服务端还大 = 内容错位，续传会一直失败，必须删
                partFile.delete();
                throw new Exception("缓存内容异常（" + finalSize + "/" + total + "）");
            }

            // 原子提交
            if (finalFile.exists()) finalFile.delete();
            if (!partFile.renameTo(finalFile)) {
                throw new Exception("写入缓存失败（rename）");
            }
            finalFile.setLastModified(System.currentTimeMillis());
            return finalFile;
        }
        throw new Exception("服务端缓存已失效（HTTP 416）");
    }

    /* ---------------- LRU 淘汰 ---------------- */

    public static class CacheFile {
        public File f;
        public long size;
        public long lastUsed;
        public boolean locked;
    }

    public static List<CacheFile> listAll() {
        List<CacheFile> out = new ArrayList<CacheFile>();
        if (CACHE_DIR == null || LOCK_DIR == null) return out;
        File[] fs = CACHE_DIR.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.isFile() || f.getName().endsWith(".part")) continue;
            CacheFile cf = new CacheFile();
            cf.f = f;
            cf.size = f.length();
            cf.lastUsed = f.lastModified();
            cf.locked = new File(LOCK_DIR, lockNameFor(f.getName())).exists();
            out.add(cf);
        }
        return out;
    }

    private static String lockNameFor(String cacheName) {
        int i = cacheName.lastIndexOf('.');
        return i > 0 ? cacheName.substring(0, i) : cacheName;
    }

    public static long totalSize() {
        long t = 0;
        for (CacheFile c : listAll()) t += c.size;
        return t;
    }

    /**
     * LRU 淘汰到上限以下。锁定的文件永不淘汰。
     */
    public static long evictIfNeeded(int maxMb) {
        if (maxMb <= 0) return 0;
        long max = (long) maxMb * 1024 * 1024;
        List<CacheFile> all = listAll();
        long total = 0;
        for (CacheFile c : all) total += c.size;
        if (total <= max) return 0;

        Collections.sort(all, new Comparator<CacheFile>() {
            @Override
            public int compare(CacheFile a, CacheFile b) {
                // 锁定文件排到最后（优先保留）
                if (a.locked != b.locked) return a.locked ? 1 : -1;
                return a.lastUsed < b.lastUsed ? -1 : (a.lastUsed > b.lastUsed ? 1 : 0);
            }
        });

        long freed = 0;
        for (CacheFile c : all) {
            if (total - freed <= max) break;
            if (c.locked) continue;
            if (c.f.delete()) freed += c.size;
        }
        return freed;
    }

    public static void clearAll() {
        if (CACHE_DIR == null) return;
        File[] fs = CACHE_DIR.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /* ---------------- 网络判断 ---------------- */

    /** 是否在 WiFi 上（预取只在 WiFi 下做） */
    public static boolean isWifi(Context c) {
        try {
            if (c == null) return false;
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected()
                    && (ni.getType() == ConnectivityManager.TYPE_WIFI
                        || ni.getType() == ConnectivityManager.TYPE_ETHERNET);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isOnline(Context c) {
        try {
            if (c == null) return true;
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }
}
