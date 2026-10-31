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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 极小的封面加载器（不引第三方图片库）。
 *
 * 车机只有 1 个 ImageView 需要封面，Picasso/Glide 那种库没必要：
 * - 内存 LruCache 8MB，最多存十来张封面
 * - 后台线程下载 + 两段式采样解码（inSampleSize），避免 4.4 上 OOM
 * - 回主线程前核对 ImageView 上的 tag，防止快速切歌时把旧封面贴上
 */
public class CoverLoader {

    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(MAX_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b == null ? 0 : b.getByteCount();
                }
            };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 正在下载的 url（同一首切来切去时别并发重复拉同一张图） */
    private static final java.util.HashSet<String> INFLIGHT = new java.util.HashSet<String>();

    public static void clear() {
        CACHE.evictAll();
    }

    /**
     * @param reqPx 期望的目标像素（按长边采样，实际解码尺寸 ≤ 2*reqPx）
     */
    public static void load(final ImageView iv, final String url, final int reqPx) {
        if (iv == null) return;

        if (url == null || url.length() == 0) {
            iv.setTag("");
            iv.setImageResource(R.drawable.cover_placeholder);
            return;
        }

        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            iv.setTag(url);
            iv.setImageBitmap(cached);
            return;
        }

        iv.setTag(url);
        final int px = reqPx > 0 ? reqPx : 320;

        synchronized (INFLIGHT) {
            if (!INFLIGHT.add(url)) return;     // 已有线程在拉这张图，等它回主线程时自己贴
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap b;
                try {
                    Bitmap hit = CACHE.get(url);
                    b = (hit != null) ? hit : fetch(url, px);
                    if (b != null) CACHE.put(url, b);
                } finally {
                    synchronized (INFLIGHT) { INFLIGHT.remove(url); }
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!url.equals(iv.getTag())) return;   // 已经切歌了
                        if (b != null) iv.setImageBitmap(b);
                        else iv.setImageResource(R.drawable.cover_placeholder);
                    }
                });
            }
        }).start();
    }

    private static Bitmap fetch(String url, int reqPx) {
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(8000);
            cn.setReadTimeout(15000);
            cn.setInstanceFollowRedirects(true);
            cn.setRequestProperty("User-Agent", "ncm-car/2.0");
            if (cn.getResponseCode() != 200) return null;

            byte[] data = readAll(cn.getInputStream());
            if (data == null || data.length == 0) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int max = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (max / sample > reqPx * 2) sample *= 2;

            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // 封面用 565 省一半内存
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        } finally {
            if (cn != null) {
                try { cn.disconnect(); } catch (Exception e) { }
            }
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
