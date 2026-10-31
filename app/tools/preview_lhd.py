#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LHD 布局 v2：字体阶梯收敛 + 比例微调后的预览（1024x600，mdpi 1dp=1px）。
字重只用 400/700 —— Android 4.4 的中文字体只有 Regular/Bold 两档。
"""
import base64, sys

W, H = 1024, 600
COVER = sys.argv[2] if len(sys.argv) > 2 else '/tmp/cover.jpg'
OUT = sys.argv[1]
with open(COVER, 'rb') as f:
    cover_b64 = base64.b64encode(f.read()).decode()

# 颜色（对比度已核：对 #0E1116 底最低 4.9）
BG, PANEL, BAR, DIV = '#0E1116', '#141922', '#0B0E13', '#1E2530'
ACCENT, PRIMARY, HEART = '#7FA9FF', '#5B8DEF', '#F5455C'
BTN_DARK, BTN_STROKE = '#1C2430', '#2A3442'
T_TITLE, T_TEXT, T_SEC, T_MUTED = '#E6EAF2', '#DDE4EE', '#A9B4C4', '#7D8896'
INK = '#0E1116'

BAR_H = 104
COLS_H = H - BAR_H          # 496
NAV_W, RIGHT_W = 220, 272
MID_W = W - NAV_W - RIGHT_W  # 532
ROW_H, HEAD_H = 72, 56

MAG = ('<svg width="23" height="23" viewBox="0 0 24 24" fill="none" '
       'stroke="currentColor" stroke-width="2.2" stroke-linecap="round">'
       '<circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5 L21 21"/></svg>')

NAV = [("☀", "每日推荐", "每天 6:00 更新", True),
       ("♥", "红心歌曲", "128 首", False),
       ("≡", "我的歌单", "12 个歌单", False),
       (MAG, "搜索", "", False)]

SONGS = [("想你就写信 (Live)", "周杰伦 / 李硕 / 张鑫", True, True),
         ("屋顶", "周杰伦 / 温岚 / 吴宗宪", True, True),
         ("布拉格广场", "蔡依林 / 周杰伦", False, False),
         ("默 (Live)", "李荣浩 / 周杰伦", True, False),
         ("刀马旦", "李玟 / 周杰伦", False, False),
         ("因为爱情 (Live)", "周杰伦 / 那英", False, False)]

# ---------- 左列入口 ----------
nav_html = ''
for ic, name, sub, active in NAV:
    bg = ACCENT if active else 'transparent'
    fg = INK if active else T_TEXT
    subcol = '#1F3A63' if active else T_MUTED
    sub_html = (f'<div style="font-size:15px;line-height:1.3;color:{subcol};margin-top:4px;">{sub}</div>'
                if sub else '')
    nav_html += f'''
    <div style="height:78px;display:flex;align-items:center;padding:0 13px;margin-bottom:6px;
                border-radius:10px;background:{bg};border:1px solid {DIV if not active else ACCENT};">
      <div style="width:32px;font-size:23px;color:{fg};text-align:center;line-height:1;">{ic}</div>
      <div style="margin-left:9px;min-width:0;">
        <div style="font-size:20px;line-height:1.25;color:{fg};font-weight:{700 if active else 400};
                    letter-spacing:0.3px;">{name}</div>
        {sub_html}
      </div>
    </div>'''

# ---------- 中列列表 ----------
rows_html = ''
for i, (n, a, cached, liked) in enumerate(SONGS):
    dot = (f'<span style="font-size:13px;color:{ACCENT};margin-left:8px;">● 已缓存</span>'
           if cached else '')
    hcol = HEART if liked else '#3A4554'
    rows_html += f'''
    <div style="height:{ROW_H}px;display:flex;align-items:center;padding:0 10px 0 18px;
                border-bottom:1px solid {DIV};">
      <div style="flex:1;min-width:0;">
        <div style="font-size:20px;line-height:1.3;color:{T_TEXT};white-space:nowrap;
                    overflow:hidden;text-overflow:ellipsis;">{n}{dot}</div>
        <div style="font-size:15px;line-height:1.3;color:{T_MUTED};margin-top:4px;
                    white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">{a}</div>
      </div>
      <div style="width:68px;text-align:center;font-size:25px;line-height:1;color:{hcol};">♥</div>
    </div>'''

html = f'''<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
 * {{margin:0;padding:0;box-sizing:border-box;}}
 body {{width:{W}px;height:{H}px;overflow:hidden;background:{BG};
        font-family:"Noto Sans CJK SC","Noto Sans SC","WenQuanYi Zen Hei",sans-serif;
        font-weight:400;color:{T_TEXT};}}
 .tnum {{font-variant-numeric:tabular-nums;font-feature-settings:"tnum";}}
</style></head><body>
<div style="display:flex;width:{W}px;height:{COLS_H}px;">

  <!-- 左列：功能入口（贴近驾驶员一侧） -->
  <div style="width:{NAV_W}px;height:100%;background:{PANEL};padding:10px;display:flex;flex-direction:column;">
    <div style="font-size:13px;letter-spacing:1.5px;color:{T_MUTED};padding:0 8px 10px 8px;">音 乐</div>
    {nav_html}
    <div style="flex:1;"></div>
    <div style="height:54px;display:flex;align-items:center;padding:0 13px;border-radius:10px;
                border:1px solid {DIV};font-size:17px;color:{T_MUTED};">⚙ 设置</div>
  </div>

  <!-- 中列：列表（标题 24 / 行 72） -->
  <div style="width:{MID_W}px;height:100%;display:flex;flex-direction:column;">
    <div style="height:{HEAD_H}px;display:flex;align-items:center;padding:0 18px;
                border-bottom:1px solid {DIV};">
      <div style="font-size:24px;font-weight:700;letter-spacing:0.3px;color:{T_TITLE};line-height:1.2;">每日推荐</div>
      <div style="font-size:15px;color:{T_MUTED};margin-left:12px;">10月31日 · 30 首</div>
    </div>
    <div style="flex:1;overflow:hidden;">{rows_html}</div>
  </div>

  <!-- 右栏：纯展示 -->
  <div style="width:{RIGHT_W}px;height:100%;background:{PANEL};padding:14px 16px;
              display:flex;flex-direction:column;">
    <div style="font-size:13px;color:{ACCENT};height:17px;letter-spacing:0.5px;">320K · 已缓存</div>
    <div style="height:206px;width:100%;display:flex;align-items:center;justify-content:center;margin:6px 0 12px 0;">
      <img src="data:image/jpeg;base64,{cover_b64}" style="max-height:206px;max-width:100%;border-radius:8px;">
    </div>
    <div style="font-size:24px;font-weight:700;line-height:1.2;color:#FFFFFF;white-space:nowrap;
                overflow:hidden;text-overflow:ellipsis;">屋顶</div>
    <div style="font-size:15px;line-height:1.3;color:{T_SEC};margin-top:6px;white-space:nowrap;
                overflow:hidden;text-overflow:ellipsis;">周杰伦 / 温岚 / 吴宗宪</div>
    <div style="border-top:1px solid {DIV};margin-top:14px;padding-top:12px;">
      <div style="font-size:16px;line-height:1.55;color:{T_MUTED};">把钥匙交给你的时候</div>
      <div style="font-size:16px;line-height:1.55;color:{T_TITLE};font-weight:700;">你应该会明白我的爱</div>
      <div style="font-size:16px;line-height:1.55;color:{T_MUTED};">虽然我从未向你坦白</div>
    </div>
  </div>
</div>

<!-- 底部常驻控制条：控制集中在左侧伸手带 -->
<div style="width:{W}px;height:{BAR_H}px;background:{BAR};border-top:1px solid {DIV};
            display:flex;align-items:center;padding:0 16px;">

  <div style="width:96px;height:80px;margin-right:12px;background:{BTN_DARK};border:1px solid {BTN_STROKE};
              border-radius:12px;display:flex;align-items:center;justify-content:center;
              font-size:26px;color:{T_TEXT};">⏮</div>
  <div style="width:124px;height:80px;margin-right:12px;background:{PRIMARY};border-radius:12px;
              display:flex;align-items:center;justify-content:center;font-size:36px;color:{INK};">▶</div>
  <div style="width:96px;height:80px;margin-right:12px;background:{BTN_DARK};border:1px solid {BTN_STROKE};
              border-radius:12px;display:flex;align-items:center;justify-content:center;
              font-size:26px;color:{T_TEXT};">⏭</div>
  <div style="width:80px;height:80px;background:{BTN_DARK};border:1px solid {HEART};border-radius:12px;
              display:flex;align-items:center;justify-content:center;font-size:30px;color:{HEART};">♥</div>

  <div style="flex:1;display:flex;align-items:center;padding:0 20px;">
    <div class="tnum" style="font-size:15px;color:{T_MUTED};width:48px;">02:14</div>
    <div style="flex:1;height:8px;background:#2A3442;border-radius:4px;position:relative;margin:0 12px;">
      <div style="width:42%;height:8px;background:{PRIMARY};border-radius:4px;"></div>
      <div style="position:absolute;left:42%;top:50%;transform:translate(-50%,-50%);
                  width:20px;height:20px;border-radius:50%;background:{PRIMARY};"></div>
    </div>
    <div class="tnum" style="font-size:15px;color:{T_MUTED};width:48px;text-align:right;">05:19</div>
  </div>
</div>
</body></html>'''

with open(OUT, 'w', encoding='utf-8') as f:
    f.write(html)
print(f'wrote {OUT} {W}x{H}')
