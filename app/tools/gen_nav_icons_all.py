#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""左栏 6 个入口的图标，全部位图。

为什么不用字符：Android 4.4 的系统字体里 ☀ ♥ ≡ 之类的字形基线跟位图对不齐
（字符画在文字基线上，位图画在顶部），折叠成图标栏时同一列里两套图标高低不齐，
看着就是「歪的」。统一成位图 + ImageView(scaleType=center) 就一劳永逸。

用法: python3 gen_nav_icons_all.py <res_dir>
"""
import math
import os
import sys
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENS = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 4
WHITE = (0xDD, 0xE4, 0xEE, 255)
DP = 26


def blank(px):
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


def heart_points(px, filled):
    """心形参数方程；filled=True 用填充色，False 用描边"""
    pts = []
    for i in range(96):
        t = 2 * math.pi * i / 96
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((px / 2 + x * px / 38.0, px / 2 - y * px / 38.0 - px * 0.03))
    return pts


def draw_sun(size_dp, scale):
    px = int(size_dp * scale * SS)
    im, d = blank(px)
    w = max(2, int(px * 0.075))
    c = px * 0.5
    r = px * 0.17
    d.ellipse([c - r, c - r, c + r, c + r], outline=WHITE, width=w)
    for i in range(8):
        a = math.pi * i / 4
        r1, r2 = px * 0.26, px * 0.40
        d.line([c + math.cos(a) * r1, c + math.sin(a) * r1,
                c + math.cos(a) * r2, c + math.sin(a) * r2], fill=WHITE, width=w)
    return im


def draw_heart(size_dp, scale, filled=False, sparkles=False):
    px = int(size_dp * scale * SS)
    im, d = blank(px)
    w = max(2, int(px * 0.075))
    pts = heart_points(px, filled)
    if filled:
        d.polygon(pts, fill=WHITE)
    else:
        d.line(pts + [pts[0]], fill=WHITE, width=w, joint='curve')
    if sparkles:
        for cx, cy, r in ((px * 0.80, px * 0.16, px * 0.045),
                          (px * 0.92, px * 0.33, px * 0.028)):
            d.line([cx - r, cy, cx + r, cy], fill=WHITE, width=max(2, int(px * 0.055)))
            d.line([cx, cy - r, cx, cy + r], fill=WHITE, width=max(2, int(px * 0.055)))
    return im


def draw_list(size_dp, scale):
    """歌单：三条横线（≡），长度递减"""
    px = int(size_dp * scale * SS)
    im, d = blank(px)
    w = max(2, int(px * 0.085))
    left = px * 0.16
    for i, frac in enumerate((0.68, 0.68, 0.44)):
        y = px * (0.30 + i * 0.20)
        d.line([left, y, left + px * frac, y], fill=WHITE, width=w)
        d.ellipse([left - px * 0.12 - w, y - w, left - px * 0.12 + w, y + w], fill=WHITE)
    return im


def draw_search(size_dp, scale):
    px = int(size_dp * scale * SS)
    im, d = blank(px)
    w = max(2, int(px * 0.085))
    pad = px * 0.14
    r = px * 0.26
    cx, cy = pad + r, pad + r
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=WHITE, width=w)
    sx, sy = cx + int(r * 0.72), cy + int(r * 0.72)
    d.line([sx, sy, px - pad, px - pad], fill=WHITE, width=w)
    d.ellipse([px - pad - w // 2, px - pad - w // 2,
               px - pad + w // 2, px - pad + w // 2], fill=WHITE)
    return im


def draw_radio(size_dp, scale):
    """私人 FM：收音机（天线 + 喇叭 + 两个旋钮）"""
    px = int(size_dp * scale * SS)
    im, d = blank(px)
    w = max(2, int(px * 0.085))
    left, top, right, bottom = px * 0.12, px * 0.36, px * 0.88, px * 0.84
    d.rounded_rectangle([left, top, right, bottom], radius=px * 0.07, outline=WHITE, width=w)
    d.line([px * 0.30, top, px * 0.47, px * 0.12], fill=WHITE, width=w)
    d.ellipse([left + px * 0.11, top + px * 0.12, left + px * 0.33, top + px * 0.34],
              outline=WHITE, width=w)
    d.ellipse([right - px * 0.40, top + px * 0.10, right - px * 0.25, top + px * 0.25],
              outline=WHITE, width=w)
    d.ellipse([right - px * 0.22, top + px * 0.10, right - px * 0.07, top + px * 0.25],
              outline=WHITE, width=w)
    d.line([left + px * 0.10, bottom - px * 0.13, right - px * 0.10, bottom - px * 0.13],
           fill=WHITE, width=w)
    return im


BUILDERS = {
    'ic_nav_daily': lambda s: draw_sun(DP, s),
    'ic_nav_like': lambda s: draw_heart(DP, s, filled=True),
    'ic_nav_heartmode': lambda s: draw_heart(DP, s, filled=False, sparkles=True),
    'ic_nav_list': lambda s: draw_list(DP, s),
    'ic_nav_search': lambda s: draw_search(DP, s),
    'ic_nav_fm': lambda s: draw_radio(DP, s),
}

for name, s in DENS.items():
    out = os.path.join(RES, 'drawable-' + name)
    os.makedirs(out, exist_ok=True)
    for fn, builder in BUILDERS.items():
        im = builder(s).resize((int(DP * s), int(DP * s)), Image.LANCZOS)
        im.save(os.path.join(out, fn + '.png'))
print('ok')
