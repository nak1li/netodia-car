#!/usr/bin/env python3
"""生成车机 App 图标（各密度 PNG）。
Android 4.4 (API 19) 不支持 VectorDrawable（API 21+），必须用 PNG。

设计：深色圆角底 + 蓝色音符。
车机屏可能只有 800x480，图标会被缩得很小 —— 用高对比度的大色块。
"""
import os
from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "res")
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG = (14, 17, 22, 255)
ACCENT = (91, 141, 239, 255)
ACCENT_LIGHT = (140, 180, 255, 255)


def draw_icon(size):
    # 4x 超采样后缩放，避免小尺寸边缘毛刺
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 圆角底
    radius = int(S * 0.18)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=radius, fill=BG)

    # 音符符干 + 符头（八分音符）—— 尺寸放大到画布 ~62%，48px 下才醒目
    stem_w = max(2, int(S * 0.105))
    stem_x = int(S * 0.56)
    stem_top = int(S * 0.13)
    stem_bottom = int(S * 0.74)
    d.rectangle([stem_x, stem_top, stem_x + stem_w, stem_bottom], fill=ACCENT)

    # 符头（椭圆）
    head_w = int(S * 0.42)
    head_h = int(S * 0.29)
    head_cx = stem_x + stem_w // 2 - head_w // 2
    head_cy = stem_bottom - head_h // 2
    d.ellipse([head_cx, head_cy, head_cx + head_w, head_cy + head_h], fill=ACCENT)

    # 符尾（连接符干与符头的斜杠）
    flag_w = int(S * 0.30)
    d.polygon([
        (stem_x + stem_w, stem_top),
        (stem_x + stem_w + flag_w, stem_top + int(S * 0.12)),
        (stem_x + stem_w + flag_w, stem_top + int(S * 0.30)),
        (stem_x + stem_w, stem_top + int(S * 0.19)),
    ], fill=ACCENT_LIGHT)

    return img.resize((size, size), Image.LANCZOS)


def draw_cover(size):
    """播放页封面占位（比 icon 大，1024x600 屏上会显示得很大）"""
    S = size * 2
    img = Image.new("RGBA", (S, S), (28, 36, 48, 255))
    d = ImageDraw.Draw(img)
    # 居中大音符
    icon = draw_icon(S).resize((int(S * 0.55), int(S * 0.55)), Image.LANCZOS)
    img.alpha_composite(icon, (int(S * 0.225), int(S * 0.225)))
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    # 应用图标（mipmap，多密度）
    for dens, size in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        draw_icon(size).save(os.path.join(d, "ic_launcher.png"))
        print(f"mipmap-{dens}/ic_launcher.png  {size}x{size}")

    # 封面占位（drawable，固定 256px 足够；Android 会缩放）
    dd = os.path.join(RES, "drawable")
    os.makedirs(dd, exist_ok=True)
    draw_cover(256).save(os.path.join(dd, "cover_placeholder.png"))
    print("drawable/cover_placeholder.png  256x256")
