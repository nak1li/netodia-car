#!/usr/bin/env python3
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
