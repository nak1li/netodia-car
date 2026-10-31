#!/usr/bin/env python3
"""网易云扫码登录：生成二维码 → 用户用手机网易云 App 扫 → 服务端保存 cookie。

凭据安全：二维码只是「授权请求」，不是密码；扫描动作由用户在手机上完成，
cookie 直接落在服务端 secrets 文件（600），车机 APK 永远拿不到账号凭据。

用法：
  python3 ncm_login.py qr        # 生成二维码 PNG，打印路径
  python3 ncm_login.py poll KEY  # 轮询登录结果（最长 150 秒）
  python3 ncm_login.py status    # 查当前登录态
"""
import json
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:28475"
import os

TOKEN_FILE = os.environ.get("NCM_TOKEN_FILE",
                           os.path.expanduser("~/.ncm/secrets/ncm.token"))
OUT_PNG = os.environ.get("NCM_QR_PNG", "ncm_login_qr.png")


def token():
    try:
        return open(TOKEN_FILE).read().strip()
    except Exception:
        return ""


def api(path, timeout=20):
    req = urllib.request.Request(BASE + path, headers={"X-NCM-Token": token()})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def cmd_qr():
    d = api("/api/login/qr")
    if "qr" not in d:
        print(json.dumps(d, ensure_ascii=False))
        return 1
    # 把 data:image/png;base64,... 落盘
    import base64
    b64 = d["qr"].split(",", 1)[1]
    with open(OUT_PNG, "wb") as f:
        f.write(base64.b64decode(b64))
    print(json.dumps({"unikey": d["unikey"], "qrurl": d["qrurl"], "png": OUT_PNG},
                     ensure_ascii=False))
    return 0


def cmd_poll(key, max_wait=150):
    """800 过期 / 801 等待扫码 / 802 已扫待确认 / 803 成功"""
    deadline = time.time() + max_wait
    last = None
    while time.time() < deadline:
        try:
            d = api("/api/login/check?key=" + urllib.parse.quote(key), timeout=15)
        except Exception as e:
            print(f"  [poll] {e}")
            time.sleep(2)
            continue
        code = d.get("code")
        if code != last:
            print(f"  [{int(time.time() % 1000):03d}] code={code} msg={d.get('message','')}")
            last = code
        if code == 803:
            print(json.dumps({"ok": True, "saved": d.get("saved"), "nickname": d.get("nickname", "")},
                             ensure_ascii=False))
            return 0
        if code == 800:
            print(json.dumps({"ok": False, "reason": "二维码已过期"}, ensure_ascii=False))
            return 2
        time.sleep(2)
    print(json.dumps({"ok": False, "reason": "等待超时"}, ensure_ascii=False))
    return 3


def cmd_status():
    print(json.dumps(api("/api/login/status"), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    import urllib.parse  # noqa
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "qr":
        sys.exit(cmd_qr())
    elif cmd == "poll":
        sys.exit(cmd_poll(sys.argv[2]))
    else:
        sys.exit(cmd_status())
