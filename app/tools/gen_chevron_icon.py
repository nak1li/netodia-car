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
"""折叠/展开箭头，位图。

为什么不用字符 ‹ ›：Android 4.4 里 22sp/28sp 的箭头塞进 26dp 高的 TextView 会被
上下裁切，只剩顶部一小段弧，看着就像「图标上面多了一个点」。位图不受字体影响。

用法: python3 gen_chevron_icon.py <res_dir>
"""
import os
import sys
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENS = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 6
COLOR = (0xDD, 0xE4, 0xEE, 255)
DP = 18


def draw_chevron(size):
    """朝左的 ‹：两条等长斜线"""
    px = int(size * SS)
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = max(2, int(px * 0.14))
    ax, ay = px * 0.66, px * 0.16
    mx, my = px * 0.30, px * 0.50
    bx, by = px * 0.66, px * 0.84
    d.line([ax, ay, mx, my], fill=COLOR, width=w)
    d.line([mx, my, bx, by], fill=COLOR, width=w)
    # 补圆头，免得尖角看着发毛
    for cx, cy in ((ax, ay), (mx, my), (bx, by)):
        d.ellipse([cx - w / 2.0, cy - w / 2.0, cx + w / 2.0, cy + w / 2.0], fill=COLOR)
    return im


for name, s in DENS.items():
    out = os.path.join(RES, 'drawable-' + name)
    os.makedirs(out, exist_ok=True)
    im = draw_chevron(DP * s).resize((int(DP * s), int(DP * s)), Image.LANCZOS)
    im.save(os.path.join(out, 'ic_chevron.png'))
print('ok')
