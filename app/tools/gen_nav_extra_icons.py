#!/usr/bin/env python3
"""
Netodia Car — 网易云音乐车机播放器
Copyright (c) 2026 nak1li

Required Notice: Copyright (c) 2026 nak1li (Netodia Car). This project is licensed for
non-commercial use only. See LICENSE for details.

授权：PolyForm Noncommercial License 1.0.0 —— 仅限非商业用途。
商业使用（含预装售卖、SaaS 收费、集成进商业产品）须先取得著作权人书面授权。
"""
# -*- coding: utf-8 -*-
"""左栏两个新入口的图标：心动模式(心形+星点)、私人FM(收音机)。
   Android 4.4 字体缺这些字形，和搜索/齿轮一样用位图。"""
import os, sys, math
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENS = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 4
W = (0xDD, 0xE4, 0xEE, 255)

def heart(size_dp=26, scale=1.0):
    px = int(size_dp * scale * SS)
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = max(2, int(px * 0.085))
    # 心形参数方程描点
    pts = []
    for i in range(72):
        t = 2 * math.pi * i / 72
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((px / 2 + x * px / 40.0, px / 2 - y * px / 40.0 - px * 0.04))
    d.line(pts + [pts[0]], fill=W, width=w, joint='curve')
    # 右上角两颗小星：表示「心动」的随机性
    for cx, cy, r in ((px * 0.80, px * 0.18, px * 0.05), (px * 0.90, px * 0.34, px * 0.032)):
        d.line([cx - r, cy, cx + r, cy], fill=W, width=max(2, int(px * 0.06)))
        d.line([cx, cy - r, cx, cy + r], fill=W, width=max(2, int(px * 0.06)))
    return im

def radio(size_dp=26, scale=1.0):
    px = int(size_dp * scale * SS)
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = max(2, int(px * 0.085))
    left, top, right, bottom = px * 0.10, px * 0.34, px * 0.90, px * 0.86
    d.rounded_rectangle([left, top, right, bottom], radius=px * 0.08, outline=W, width=w)
    # 天线
    d.line([px * 0.28, top, px * 0.46, px * 0.10], fill=W, width=w)
    # 喇叭格栅
    d.ellipse([left + px * 0.10, top + px * 0.12, left + px * 0.34, top + px * 0.36],
              outline=W, width=w)
    # 两个旋钮
    d.ellipse([right - px * 0.42, top + px * 0.10, right - px * 0.26, top + px * 0.26],
              outline=W, width=w)
    d.ellipse([right - px * 0.24, top + px * 0.10, right - px * 0.08, top + px * 0.26],
              outline=W, width=w)
    # 刻度线
    d.line([left + px * 0.10, bottom - px * 0.14, right - px * 0.10, bottom - px * 0.14],
           fill=W, width=w)
    return im

for name, s in DENS.items():
    out = os.path.join(RES, 'drawable-' + name)
    os.makedirs(out, exist_ok=True)
    heart(26, s).resize((int(26 * s), int(26 * s)), Image.LANCZOS).save(os.path.join(out, 'ic_heart_mode.png'))
    radio(26, s).resize((int(26 * s), int(26 * s)), Image.LANCZOS).save(os.path.join(out, 'ic_fm.png'))
print('ok')
