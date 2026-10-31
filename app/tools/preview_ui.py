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
"""
旧入口保留：主界面已经换成 v2 版式（左 220 / 中 532 / 右 272 + 104dp 底栏），
预览脚本由同目录的 preview_lhd.py 负责。本文件只做转发，
免得两份渲染脚本各自漂移、看预览时对着已经不存在的老版式做判断。

    python3 app/tools/preview_ui.py out.html cover.jpg
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "preview_lhd.py")

if not os.path.isfile(TARGET):
    sys.exit("找不到 %s" % TARGET)

sys.exit(subprocess.call([sys.executable, TARGET] + sys.argv[1:]))
