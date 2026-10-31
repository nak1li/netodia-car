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
"""生成播放控制图标 PNG（prev / play / pause / next）。

为什么不直接用 ⏮ ⏸ ⏭ 字符：这些码位是 Unicode 6.0/7.0 才加的，
Android 4.4 的系统字体很可能没有字形，真机上会显示成方框。
画成位图就跟字体无关了。

用法: python3 gen_player_icons.py <res_dir>
"""
import os
import sys
from PIL import Image, ImageDraw

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'res')
DENSITIES = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0}
SS = 4  # 超采样倍数，边缘抗锯齿

LIGHT = (0xDD, 0xE4, 0xEE, 255)
DARK = (0x0E, 0x11, 0x16, 255)


def canvas(w_dp, h_dp, scale):
    w, h = int(round(w_dp * scale * SS)), int(round(h_dp * scale * SS))
    return Image.new('RGBA', (w, h), (0, 0, 0, 0)), w, h


def save(img, name, density, w_dp, h_dp):
    out = img.resize((int(round(w_dp * DENSITIES[density])),
                      int(round(h_dp * DENSITIES[density]))), Image.LANCZOS)
    d = os.path.join(RES, 'drawable-' + density)
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, name + '.png')
    out.save(p)
    return p


def draw_play(color):
    """实心右向三角，26x30dp"""
    img, w, h = canvas(26, 30, 1)
    d = ImageDraw.Draw(img)
    d.polygon([(0, 0), (w, h // 2), (0, h)], fill=color)
    return img


def draw_pause(color):
    """两根竖条，24x28dp"""
    img, w, h = canvas(24, 28, 1)
    d = ImageDraw.Draw(img)
    bar = w // 3
    d.rectangle([0, 0, bar, h], fill=color)
    d.rectangle([w - bar, 0, w, h], fill=color)
    return img


def draw_skip(direction, color):
    """双三角 + 竖条，30x26dp；direction = -1 上一首 / +1 下一首"""
    img, w, h = canvas(30, 26, 1)
    d = ImageDraw.Draw(img)
    tri_w = int(w * 0.42)
    bar = max(2, int(w * 0.10))
    if direction < 0:
        # 竖条在左，两个三角指左
        d.rectangle([0, 0, bar, h], fill=color)
        x0 = bar + int(w * 0.06)
        for i in range(2):
            left = x0 + i * tri_w
            d.polygon([(left + tri_w, 0), (left, h // 2), (left + tri_w, h)], fill=color)
    else:
        d.rectangle([w - bar, 0, w, h], fill=color)
        x1 = w - bar - int(w * 0.06)
        for i in range(2):
            right = x1 - i * tri_w
            d.polygon([(right - tri_w, 0), (right, h // 2), (right - tri_w, h)], fill=color)
    return img


made = []
for den in DENSITIES:
    made.append(save(draw_skip(-1, LIGHT), 'ic_prev', den, 30, 26))
    made.append(save(draw_play(DARK), 'ic_play', den, 26, 30))
    made.append(save(draw_pause(DARK), 'ic_pause', den, 24, 28))
    made.append(save(draw_skip(+1, LIGHT), 'ic_next', den, 30, 26))

for p in made:
    print(p)
print('icons:', len(made))
