#!/usr/bin/env bash
# Netodia Car — 网易云音乐车机播放器
# Copyright (c) 2026 nak1li
#
# Required Notice: Copyright (c) 2026 nak1li (Netodia Car). This project is licensed for
# non-commercial use only. See LICENSE for details.
#
# 授权：PolyForm Noncommercial License 1.0.0 —— 仅限非商业用途。
# 商业使用（含预装售卖、SaaS 收费、集成进商业产品）须先取得著作权人书面授权。
# ncm-server 端到端验收
set -uo pipefail
TOK=$(cat ~/.hermes/secrets/ncm.token)
B="http://127.0.0.1:28475"
H="X-NCM-Token: $TOK"
Q="token=$TOK"

pass=0; fail=0
chk() { # chk <名字> <期望> <实际>
  if [ "$2" = "$3" ]; then echo "  ✓ $1"; pass=$((pass+1));
  else echo "  ✗ $1  期望=$2 实际=$3"; fail=$((fail+1)); fi
}
has() { # has <名字> <子串> <文本>
  if echo "$3" | grep -q "$2"; then echo "  ✓ $1"; pass=$((pass+1));
  else echo "  ✗ $1  未找到 '$2'"; echo "     内容: $(echo "$3" | head -c 300)"; fail=$((fail+1)); fi
}

echo "== 1. health =="
R=$(curl -s --max-time 10 -H "$H" $B/api/health)
has "health ok=true" '"ok":true' "$R"

echo "== 2. 鉴权 =="
C=$(curl -s --max-time 10 -o /dev/null -w "%{http_code}" $B/api/health)
chk "无 token 401" "401" "$C"
C=$(curl -s --max-time 10 -o /dev/null -w "%{http_code}" -H "X-NCM-Token: wrong" $B/api/health)
chk "错 token 401" "401" "$C"

echo "== 3. 搜索 =="
R=$(curl -s --max-time 20 -H "$H" "$B/api/search?q=%E5%91%A8%E6%9D%B0%E4%BC%A6%20%E6%99%B4%E5%A4%A9&type=1&limit=5")
has "搜索 code=200" '"code":200' "$R"
has "搜索返回 songs" '"songs":' "$R"
SID=$(echo "$R" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['songs'][0]['id'])" 2>/dev/null)
echo "     首个曲目 id=$SID"
has "曲目含 artists" '"artists":' "$R"
has "曲目含 album" '"album":' "$R"

echo "== 4. 歌单推荐 =="
R=$(curl -s --max-time 20 -H "$H" "$B/api/playlist/recommend?limit=5")
has "推荐 code=200" '"code":200' "$R"
has "推荐有 playlists" '"playlists":' "$R"
PID=$(echo "$R" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['playlists'][0]['id'])" 2>/dev/null)

echo "== 5. 歌单详情 =="
R=$(curl -s --max-time 20 -H "$H" "$B/api/playlist/detail?id=$PID&limit=10")
has "歌单 code=200" '"code":200' "$R"
has "歌单有 songs" '"songs":' "$R"

echo "== 6. 榜单 =="
R=$(curl -s --max-time 20 -H "$H" "$B/api/toplist")
has "榜单 code=200" '"code":200' "$R"

echo "== 7. 歌词 =="
R=$(curl -s --max-time 20 -H "$H" "$B/api/lyric?id=$SID")
has "歌词 code=200" '"code":200' "$R"

echo "== 8. 播放地址（不暴露网易云 URL）=="
R=$(curl -s --max-time 20 -H "$H" "$B/api/song/url?id=$SID&quality=exhigh")
has "url br=320000" '"br":320000' "$R"
has "url 不含 music.126.net" '' "$R"
if echo "$R" | grep -q "music.126.net"; then echo "  ✗ 泄漏了网易云域名"; fail=$((fail+1)); else echo "  ✓ 未泄漏网易云域名"; pass=$((pass+1)); fi

echo "== 9. 音频流（首次=MISS）=="
# 先清缓存，保证这是真正的冷启动路径
curl -s --max-time 10 -H "$H" -X POST "$B/api/cache/clear" > /dev/null
T0=$(date +%s%N)
curl -s --max-time 60 -H "$H" -o /tmp/stream1.mp3 -D /tmp/h1.txt "$B/api/stream?id=$SID&quality=exhigh"
T1=$(date +%s%N)
echo "     耗时 $(( (T1-T0)/1000000 )) ms, 大小 $(stat -c%s /tmp/stream1.mp3) 字节"
has "响应 X-Cache: MISS" "X-Cache: MISS" "$(cat /tmp/h1.txt)"
chk "音频魔数 ID3" "494433" "$(head -c3 /tmp/stream1.mp3 | xxd -p)"
chk "Content-Length 一致" "$(grep -i '^content-length:' /tmp/h1.txt | tr -d '\r' | awk '{print $2}')" "$(stat -c%s /tmp/stream1.mp3)"

echo "== 10. 音频流（二次=HIT，走缓存）=="
T0=$(date +%s%N)
curl -s --max-time 60 -H "$H" -o /tmp/stream2.mp3 -D /tmp/h2.txt "$B/api/stream?id=$SID&quality=exhigh"
T1=$(date +%s%N)
echo "     耗时 $(( (T1-T0)/1000000 )) ms"
has "响应 X-Cache: HIT" "X-Cache: HIT" "$(cat /tmp/h2.txt)"
if cmp -s /tmp/stream1.mp3 /tmp/stream2.mp3; then echo "  ✓ 两次字节完全一致"; pass=$((pass+1)); else echo "  ✗ 字节不一致"; fail=$((fail+1)); fi

echo "== 11. Range 请求（断点续传/拖动进度）=="
curl -s --max-time 20 -H "$H" -H "Range: bytes=0-1023" -o /tmp/part.bin -D /tmp/h3.txt "$B/api/stream?id=$SID&quality=exhigh"
chk "Range 返回 206" "206" "$(grep -i '^HTTP/' /tmp/h3.txt | awk '{print $2}' | tr -d '\r')"
chk "Range 长度 1024" "1024" "$(stat -c%s /tmp/part.bin)"
has "Content-Range 正确" "bytes 0-1023/" "$(cat /tmp/h3.txt)"

echo "== 12. 缓存状态 =="
R=$(curl -s --max-time 10 -H "$H" "$B/api/cache/status")
has "缓存统计有 audio.fileCount" '"fileCount":' "$R"
echo "     $R"

echo "== 13. 缓存文件确实落盘 =="
ls -la ~/.ncm-cache/audio/ | head -5

echo
echo "================ 结果: 通过 $pass / 失败 $fail ================"
[ "$fail" -eq 0 ]
