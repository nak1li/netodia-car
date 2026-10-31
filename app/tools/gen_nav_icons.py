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
"""生成左栏「搜索」用的放大镜图标（白色描边，24dp）。

为什么不用字符：⌕ / 🔍 在 Android 4.4 的系统字体里没有字形，
真机上就是一片空白（模拟器实测确认过）。画成位图跟字体无关。

用法: python3 gen_nav_icons.py <res_dir>
"""
import os
import sys
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENS = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 4
WHITE = (0xDD, 0xE4, 0xEE, 255)


def magnifier(size_dp=24, scale=1.0, color=WHITE):
    px = int(size_dp * scale * SS)
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    w = max(2, int(px * 0.10))          # 线宽
    pad = int(px * 0.10)
    r = int(px * 0.30)
    cx, cy = pad + r, pad + r
    # 圆环
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=w)
    # 手柄（45°，从圆环右下引到角）
    sx, sy = cx + int(r * 0.72), cy + int(r * 0.72)
    ex, ey = px - pad, px - pad
    d.line([sx, sy, ex, ey], fill=color, width=w)
    # 手柄末端圆头
    d.ellipse([ex - w // 2, ey - w // 2, ex + w // 2, ey + w // 2], fill=color)
    return img


made = []
for name, s in DENS.items():
    out_dir = os.path.join(RES, 'drawable-' + name)
    os.makedirs(out_dir, exist_ok=True)
    im = magnifier(24, s).resize((int(24 * s), int(24 * s)), Image.LANCZOS)
    p = os.path.join(out_dir, 'ic_search.png')
    im.save(p)
    made.append(p)
print('\n'.join(made))
