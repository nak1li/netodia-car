#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通知栏小图标：白色音符（系统会再做一次着色，所以要纯白剪影）。"""
import os, sys
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENS = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 4
W = (255, 255, 255, 255)

def note(size_dp=24, scale=1.0):
    px = int(size_dp * scale * SS)
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    r = int(px * 0.17)          # 符头半径
    stem_w = max(2, int(px * 0.09))
    # 两个符头（八分音符连桁）
    d.ellipse([px*0.10, px*0.62, px*0.10+2*r, px*0.62+2*r*0.8], fill=W)
    d.ellipse([px*0.52, px*0.56, px*0.52+2*r, px*0.56+2*r*0.8], fill=W)
    # 符干
    d.rectangle([px*0.10+2*r-stem_w, px*0.16, px*0.10+2*r, px*0.70], fill=W)
    d.rectangle([px*0.52+2*r-stem_w, px*0.10, px*0.52+2*r, px*0.64], fill=W)
    # 连桁
    d.polygon([(px*0.10+2*r-stem_w, px*0.16), (px*0.52+2*r, px*0.10),
               (px*0.52+2*r, px*0.26), (px*0.10+2*r-stem_w, px*0.32)], fill=W)
    return im

for name, s in DENS.items():
    out = os.path.join(RES, 'drawable-' + name)
    os.makedirs(out, exist_ok=True)
    note(24, s).resize((int(24*s), int(24*s)), Image.LANCZOS).save(os.path.join(out, 'ic_stat_note.png'))
print('ok')
