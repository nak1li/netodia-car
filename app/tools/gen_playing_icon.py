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
"""列表里「正在播放」的小标记：三条高低不等的竖条（均衡器样式）。

为什么画成位图：Android 4.4 的字体里 ♪ ▶ 这些字形不一定有，
真机上可能是空白框（和搜索/齿轮一个坑）。

用法: python3 gen_playing_icon.py <res_dir>
"""
import os
import sys
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENS = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 6
ACCENT = (0x7F, 0xA9, 0xFF, 255)


def bars(size_dp=14, scale=1.0):
    px = int(size_dp * scale * SS)
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = max(2, int(px * 0.16))
    gap = int(px * 0.22)
    heights = (0.45, 0.85, 0.62)
    x = (px - (w * 3 + gap * 2)) // 2
    for h in heights:
        bh = int(px * h)
        y = px - bh
        d.rounded_rectangle([x, y, x + w, px], radius=w // 2, fill=ACCENT)
        x += w + gap
    return im


for name, s in DENS.items():
    out = os.path.join(RES, 'drawable-' + name)
    os.makedirs(out, exist_ok=True)
    bars(14, s).resize((int(14 * s), int(14 * s)), Image.LANCZOS).save(
        os.path.join(out, 'ic_playing.png'))
print('ok')
