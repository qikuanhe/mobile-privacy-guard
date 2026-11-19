#!/bin/bash
# 手动构建 APK（不依赖 Gradle / Android Studio）
#
# 需要：JDK 17、Kotlin 编译器、Android SDK Build-Tools 35 与 Platform 35
# 用法：
#   export ANDROID_TOOLS_DIR=/path/to/tools     # 目录内含 jdk17/ kotlinc/ sdk/
#   bash build.sh
#
# 可选环境变量：
#   JDK_HOME / KOTLIN_HOME / ANDROID_SDK_ROOT  分别覆盖单个工具位置
#   PYTHON_BIN                                 指定 Python（pack.py 需要）
#   KEYSTORE_DIR                               签名文件存放目录（默认 .keystore/）
#   KEYSTORE_PASS / KEY_ALIAS                  签名密码与别名
#
# 说明：本脚本面向 Windows + Git Bash（aapt2.exe / d8.bat / apksigner.bat 均为 Windows 版）。
set -e
cd "$(dirname "$0")"

# ---------- 1. 定位工具链 ----------
if [ -n "$ANDROID_TOOLS_DIR" ]; then
    AT="$(cd "$ANDROID_TOOLS_DIR" && pwd)"
elif [ -d ./tools ]; then
    AT="$(cd ./tools && pwd)"
elif [ -d ../android_tools ]; then
    AT="$(cd ../android_tools && pwd)"
else
    echo "错误：找不到工具链目录。" >&2
    echo "请设置 ANDROID_TOOLS_DIR 指向包含 jdk17/ kotlinc/ sdk/ 的目录，" >&2
    echo "或把工具链放在 ./tools 或 ../android_tools。" >&2
    exit 1
fi

# Git Bash 下把 POSIX 路径转成 Windows 路径（kotlinc 的 -classpath 需要）
to_win() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}
ATW="$(to_win "$AT")"
HERE_W="$(to_win "$(pwd)")"

JDK="${JDK_HOME:-$AT/jdk17}"
KOTLIN="${KOTLIN_HOME:-$AT/kotlinc}"
SDK="${ANDROID_SDK_ROOT:-$AT/sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"

PY="${PYTHON_BIN:-}"
if [ -z "$PY" ]; then
    PY="$(command -v python3 2>/dev/null || command -v python 2>/dev/null || true)"
fi
if [ -z "$PY" ]; then
    echo "错误：找不到 Python（pack.py 需要）。请设置 PYTHON_BIN。" >&2
    exit 1
fi

for p in "$JDK/bin/java" "$KOTLIN/bin/kotlinc" "$BT/aapt2.exe" "$PLATFORM"; do
    if [ ! -e "$p" ]; then
        echo "错误：缺少 $p" >&2
        exit 1
    fi
done

# ---------- 2. 包名 / 版本 ----------
PKG_PATH="com/daweiba/privacyguard"
VERSION_CODE=12
VERSION_NAME=0.7.1
APK="DaweibaPrivacy-v${VERSION_NAME}.apk"

# Shizuku 可选增强：4 个 jar 来自 Maven Central dev.rikka.shizuku 13.1.5
# classpath 含分号时 Git Bash 会错误转换 POSIX 路径，因此这里全部用 Windows 原生绝对路径
CP="$ATW/sdk/platforms/android-35/android.jar"
for j in shizuku-api shizuku-aidl shizuku-provider shizuku-shared; do
    CP="$CP;$HERE_W/libs/$j.jar"
done

export PATH="$JDK/bin:$PATH"

# ---------- 3. 构建 ----------
rm -rf build
mkdir -p build/gen build/classes

echo "[1/6] aapt2 编译资源..."
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[2/6] aapt2 链接生成基础 APK..."
"$BT/aapt2.exe" link \
    -o build/app.base.apk \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code $VERSION_CODE \
    --version-name $VERSION_NAME \
    build/res.zip

echo "[3/6] Kotlin 编译..."
mkdir -p build/kout
"$KOTLIN/bin/kotlinc" \
    -jvm-target 1.8 \
    -classpath "$CP" \
    -d build/kout \
    src/$PKG_PATH/*.kt

echo "[4/6] d8 转 dex (含 kotlin-stdlib)..."
mkdir -p build/dexout
"$BT/d8.bat" --release --min-api 26 \
    --lib "$PLATFORM" \
    --output build/dexout \
    build/kout/$PKG_PATH/*.class \
    "$KOTLIN/lib/kotlin-stdlib.jar" \
    libs/shizuku-api.jar \
    libs/shizuku-aidl.jar \
    libs/shizuku-provider.jar \
    libs/shizuku-shared.jar

echo "[5/6] 注入 classes.dex..."
"$PY" pack.py build/app.base.apk build/app.unaligned.apk build/dexout/classes.dex

echo "[6/6] 对齐 + 签名..."
# 签名文件放在 build/ 之外，避免每次构建重新生成导致签名变化、无法覆盖安装
KS_DIR="${KEYSTORE_DIR:-.keystore}"
KS="$KS_DIR/debug.keystore"
KS_PASS="${KEYSTORE_PASS:-privacycheck}"
ALIAS="${KEY_ALIAS:-daweiba}"
mkdir -p "$KS_DIR"
if [ ! -f "$KS" ]; then
    echo "  生成签名文件 $KS（首次构建）..."
    "$JDK/bin/keytool" -genkeypair \
        -keystore "$KS" -alias "$ALIAS" \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=Daweiba Privacy Guard,O=Daweiba,C=CN" \
        -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/zipalign.exe" -f 4 build/app.unaligned.apk build/app.aligned.apk
"$BT/apksigner.bat" sign \
    --ks "$KS" --ks-pass "pass:$KS_PASS" \
    --out "$APK" \
    build/app.aligned.apk

echo ""
echo "构建完成: $(pwd)/$APK"
ls -la "$APK"
echo ""
echo "提示：签名文件在 $KS，请妥善备份；换签名后无法覆盖安装旧版本。"
