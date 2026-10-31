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
"""通知栏「停止」图标：白色圆角方块+叉。"""
import os, sys
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENS = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 4
W = (0xDD, 0xE4, 0xEE, 255)

def stop(size_dp=24, scale=1.0):
    px = int(size_dp * scale * SS)
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pad = int(px * 0.22)
    d.rounded_rectangle([pad, pad, px - pad, px - pad], radius=int(px * 0.06),
                        outline=W, width=max(2, int(px * 0.09)))
    m = int(px * 0.34)
    d.line([m, m, px - m, px - m], fill=W, width=max(2, int(px * 0.09)))
    d.line([m, px - m, px - m, m], fill=W, width=max(2, int(px * 0.09)))
    return im

for name, s in DENS.items():
    out = os.path.join(RES, 'drawable-' + name)
    os.makedirs(out, exist_ok=True)
    stop(24, s).resize((int(24*s), int(24*s)), Image.LANCZOS).save(os.path.join(out, 'ic_stop.png'))
print('ok')
