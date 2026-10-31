#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""左栏折叠时的「设置」齿轮图标：Android 4.4 字体没有 ⚙ 字形，只能用位图。"""
import os, sys
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENS = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 4
W = (0x7D, 0x88, 0x96, 255)   # C_MUTED，和未选中文字一致

def gear(size_dp=26, scale=1.0):
    px = int(size_dp * scale * SS)
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = px / 2.0
    r_out = px * 0.42
    r_in = px * 0.30
    tooth = px * 0.115
    # 8 个齿
    import math
    for k in range(8):
        a = k * math.pi / 4
        x1 = c + math.cos(a) * (r_in - tooth * 0.4)
        y1 = c + math.sin(a) * (r_in - tooth * 0.4)
        x2 = c + math.cos(a) * (r_out + tooth * 0.35)
        y2 = c + math.sin(a) * (r_out + tooth * 0.35)
        d.line([x1, y1, x2, y2], fill=W, width=max(2, int(px * 0.14)))
    d.ellipse([c - r_out * 0.78, c - r_out * 0.78, c + r_out * 0.78, c + r_out * 0.78],
              outline=W, width=max(2, int(px * 0.11)))
    d.ellipse([c - px * 0.10, c - px * 0.10, c + px * 0.10, c + px * 0.10], fill=W)
    return im

for name, s in DENS.items():
    out = os.path.join(RES, 'drawable-' + name)
    os.makedirs(out, exist_ok=True)
    gear(26, s).resize((int(26 * s), int(26 * s)), Image.LANCZOS).save(
        os.path.join(out, 'ic_gear.png'))
print('ok')
