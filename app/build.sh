#!/usr/bin/env bash
# 免 gradle 构建车机 APK（API 19）
# 流水线：aapt2 compile → aapt2 link → javac → d8 → zip → zipalign → apksigner
#
# 关键（每一条都实际静默失败过）：
#  - javac 只用 -classpath，不要 -bootclasspath
#  - aapt2 compile 失败必须让脚本失败，不能 || true
#  - res.zip 作为位置参数传给 link（-R 是 overlay 语义）
#  - javac 退出码必须检查，class 数量必须核对
#  - 版本号只有一个来源，从源码常量解析
set -euo pipefail

# 从脚本自身位置推导项目根，别写死作者的家目录（别人克隆到任何路径都能构建）
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$PROJ/build"
ROOT="$HOME/android-build"
export JAVA_HOME="$(ls -d "$ROOT"/jdk-11.* 2>/dev/null | head -1)"
[ -d "$JAVA_HOME" ] || { echo "!! 找不到 JDK11（$ROOT/jdk-11.*）" >&2; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$ROOT/sdk"
PLATFORM="$SDK/platforms/android-19/android.jar"
BT="$SDK/build-tools/34.0.0"

log() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "!! $*" >&2; exit 1; }

[ -f "$PLATFORM" ] || die "缺 android.jar: $PLATFORM"
[ -d "$BT" ] || die "缺 build-tools: $BT"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

# ---------- 版本号：唯一来源 ----------
VCODE=$(sed -n 's/.*VERSION_CODE = \([0-9]\+\)[^0-9].*/\1/p' "$PROJ/src/com/nakili/ncmcar/Version.java" | head -1)
VNAME=$(sed -n 's/.*VERSION_NAME = "\([^"]*\)".*/\1/p' "$PROJ/src/com/nakili/ncmcar/Version.java" | head -1)
[ -n "$VCODE" ] || die "解析不到 VERSION_CODE"
[ -n "$VNAME" ] || die "解析不到 VERSION_NAME"
log "versionCode=$VCODE versionName=$VNAME"

sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VCODE\"/" "$PROJ/AndroidManifest.xml"
sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VNAME\"/" "$PROJ/AndroidManifest.xml"
printf '{"versionCode":%s,"versionName":"%s"}\n' "$VCODE" "$VNAME" > "$OUT/version.json"

# ---------- 1) 资源 ----------
log "aapt2 compile ..."
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip" 2>"$OUT/aapt2.err" || {
  cat "$OUT/aapt2.err"; die "aapt2 compile 失败"
}
[ -s "$OUT/res.zip" ] || die "res.zip 为空（res/ 目录有东西吗？）"

log "aapt2 link ..."
mkdir -p "$OUT/gen"
"$BT/aapt2" link -I "$PLATFORM" --manifest "$PROJ/AndroidManifest.xml" \
    --min-sdk-version 17 --target-sdk-version 19 \
    --java "$OUT/gen" \
    "$OUT/res.zip" -o "$OUT/base.apk" 2>"$OUT/aapt2link.err" || {
  cat "$OUT/aapt2link.err"; die "aapt2 link 失败"
}
[ -s "$OUT/base.apk" ] || die "base.apk 为空"
[ -f "$OUT/gen/com/nakili/ncmcar/R.java" ] || die "R.java 没生成（--java 目录不对？）"
log "R.java 已生成"

# ---------- 1.5) 生成 Secrets.java（构建时注入，不落源码仓库） ----------
# 车机客户端要带服务端 token，但 token 是敏感值，不能硬编码进 git。
# 每次构建从 ~/.hermes/secrets/ncm.token 读，写成包内常量。
# 凭据根统一为 ~/.ncm/secrets（与 README、ncm_login.py、ncm-api.js 一致）；
# 老部署在 ~/.hermes/secrets 的仍然兼容。
SECRET_DIR="${NCM_SECRET_DIR:-$HOME/.ncm/secrets}"
[ -d "$SECRET_DIR" ] || SECRET_DIR="$HOME/.hermes/secrets"
TOKF="${NCM_TOKEN_FILE:-$SECRET_DIR/ncm.token}"
TOK=""
if [ -f "$TOKF" ]; then
  TOK=$(tr -d '\r\n' < "$TOKF")
else
  if [ "${ALLOW_NO_TOKEN:-0}" = "1" ]; then
    log "⚠️ 没有 $TOKF，按 ALLOW_NO_TOKEN=1 继续出包（请求会 401）"
  else
    die "缺少 token 文件 $TOKF：装上会满屏 401。真的不要 token 就 ALLOW_NO_TOKEN=1 bash build.sh"
  fi
fi
# 服务器地址同理：默认空，构建时从 ~/.hermes/secrets/ncm.server 或 $NCM_SERVER 注入
SRVF="${NCM_SERVER_FILE:-$SECRET_DIR/ncm.server}"
SRV="${NCM_SERVER:-}"
if [ -z "$SRV" ] && [ -f "$SRVF" ]; then SRV=$(tr -d '\r\n' < "$SRVF"); fi
[ -n "$SRV" ] || log "⚠️ 没有服务器地址（$SRVF 或 \$NCM_SERVER），装到车机上需要在设置里手填"

# 可选的自动发现参数（局域网入口 / 内置公网 IP / 取 IP 的固定地址），没有就留空
RESOLVER_CFG="${NCM_RESOLVER_FILE:-$SECRET_DIR/ncm.resolver}"
LANURL=""; PUBIP=""; IPSRC=""
if [ -f "$RESOLVER_CFG" ]; then
  LANURL=$(sed -n 's/^lan=//p' "$RESOLVER_CFG" | head -1)
  PUBIP=$(sed -n 's/^public_ip=//p' "$RESOLVER_CFG" | head -1)
  IPSRC=$(sed -n 's/^ip_source=//p' "$RESOLVER_CFG" | head -1)
fi

SECRETS_JAVA="$PROJ/src/com/nakili/ncmcar/Secrets.java"
cat > "$SECRETS_JAVA" <<EOF
package com.nakili.ncmcar;

/** 构建时由 build.sh 生成，不要手改。 */
final class Secrets {
    static final String TOKEN = "$TOK";
    static final String SERVER = "$SRV";
    static final String LAN = "$LANURL";
    static final String PUBLIC_IP = "$PUBIP";
    static final String IP_SOURCE = "$IPSRC";
    private Secrets() {}
}
EOF
log "Secrets.java 已生成（token 长度 ${#TOK}）"

# ---------- 2) 编译 ----------
log "javac ..."
{ find "$PROJ/src" -name '*.java'; find "$OUT/gen" -name '*.java'; } > "$OUT/sources.txt"
N_SRC=$(wc -l < "$OUT/sources.txt")
log "源文件 $N_SRC 个（含生成的 R.java）"

set +e
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
      -classpath "$PLATFORM" \
      -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
RC=$?
set -e
if [ $RC -ne 0 ]; then
  cat "$OUT/javac.log"; die "javac 失败（退出码 $RC）"
fi
COUNT=$(find "$OUT/classes" -name '*.class' | wc -l)
log "编出 $COUNT 个 class"
[ "$COUNT" -ge "$N_SRC" ] || die "class 数($COUNT) < 源文件数($N_SRC)，有源文件没编出来"

# ---------- 3) dex ----------
log "d8 ..."
mapfile -t CLASSFILES < <(find "$OUT/classes" -name '*.class')
"$BT/d8" --min-api 17 --lib "$PLATFORM" --output "$OUT/dex" "${CLASSFILES[@]}" \
    2>"$OUT/d8.err" || { cat "$OUT/d8.err"; die "d8 失败"; }
[ -s "$OUT/dex/classes.dex" ] || die "classes.dex 没生成"

# ---------- 4) 打包 ----------
log "打包 ..."
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# 系统没有 zip 命令（连 unzip 都没有），用 Python 的 zipfile 追加
python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile, os
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
# 验证
with zipfile.ZipFile(apk) as z:
    assert 'classes.dex' in z.namelist(), "classes.dex 没进包！"
print("  classes.dex 已入包")
PY

# ---------- 5) 对齐 ----------
log "zipalign ..."
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk" || die "zipalign 失败"

# ---------- 6) 签名 ----------
log "apksigner ..."
# keystore 必须放在 $OUT 之外：脚本开头会 rm -rf "$OUT"，
# 放在里面等于每次构建都换一把新钥匙，车机上 adb install -r 会
# INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES（只能先卸载再装）。
KS="$PROJ/keys/release.keystore"
mkdir -p "$(dirname "$KS")"
if [ ! -f "$KS" ]; then
  # 已经有 keys/ 目录却找不到 keystore → 说明钥匙丢了。
  # 这时绝不能静默生成新钥匙：签名变了，车机上覆盖安装会 FAILED_UPDATE_INCOMPATIBLE，
  # 只能卸载重装（设置里的服务器地址/令牌/缓存全丢）。
  [ -d "$(dirname "$KS")" ] && die "keystore 丢失：$(dirname "$KS") 存在但没有 release.keystore。请从备份恢复，或删掉该目录重新生成（会导致签名变更）"

  log "首次构建：生成签名密钥 $KS（请备份，丢了就没法覆盖安装）"
  keytool -genkeypair -v -keystore "$KS" -storepass ncmcar \
    -alias ncmcar -keypass ncmcar -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=nakili car music, OU=personal, O=nakili, L=-, ST=-, C=CN" \
    > "$OUT/keytool.log" 2>&1 || { cat "$OUT/keytool.log"; die "生成 keystore 失败"; }
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:ncmcar --key-pass pass:ncmcar \
    --out "$OUT/Netodia-Car.apk" "$OUT/aligned.apk" || die "签名失败"

# ---------- 7) 验收（不信构建日志，从产物读回） ----------
log "=== 验收 ==="
"$BT/apksigner" verify --verbose "$OUT/Netodia-Car.apk" | head -8

BADGING=$("$BT/aapt2" dump badging "$OUT/Netodia-Car.apk")
echo "$BADGING" | grep -E "^package:|^application-label:|^sdkVersion:|^targetSdkVersion:" || true
echo "$BADGING" | grep -E "uses-permission" | sed 's/^/  /'

SIZE=$(stat -c%s "$OUT/Netodia-Car.apk")
log "APK 大小: $SIZE 字节 ($(( SIZE / 1024 )) KB)"

python3 - "$OUT/Netodia-Car.apk" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = z.namelist()
print("  entries:", len(names))
for key in ('classes.dex', 'AndroidManifest.xml', 'res/layout/activity_main.xml',
            'res/drawable/btn_primary.xml', 'res/drawable/cover_placeholder.png'):
    print(f"  {'OK ' if key in names else 'MISS'} {key}")
# aapt2 会给资源加 -v4 之类的后缀，用模糊匹配核对图标真的进包了
icons = [n for n in names if 'ic_launcher' in n]
print(f"  {'OK ' if len(icons) >= 5 else 'MISS'} ic_launcher 密度齐全 ({len(icons)} 个): {icons[:2]}")
dex = z.read('classes.dex')
for k in (b'Lcom/nakili/ncmcar/MainActivity;', b'Lcom/nakili/ncmcar/Player;',
          b'Lcom/nakili/ncmcar/LocalCache;', b'Lcom/nakili/ncmcar/BootReceiver;'):
    print(f"  {'OK ' if k in dex else 'MISS'} {k.decode()}")
PY

log "BUILD OK -> $OUT/Netodia-Car.apk"
