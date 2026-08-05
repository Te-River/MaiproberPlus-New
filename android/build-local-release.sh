#!/usr/bin/env bash
# 本地 release 构建脚本
# - 自动生成临时 debug keystore 签 release（仅本地自测/装机，不可分发）
# - 跑 ./gradlew assembleRelease
# - 产物拷到项目根 release/ 目录
# - 不污染仓库：keystore 与 release/ 都在 .gitignore 里
#
# 用法（在 android/ 下）：
#   ./build-local-release.sh
# 可选环境变量：
#   JAVA_HOME        —— 必须指向 JDK 21（脚本会校验）
#   ANDROID_HOME     —— 必须指向 Android SDK
#   LOCAL_KEYSTORE_PATH / LOCAL_KEYSTORE_ALIAS / LOCAL_STORE_PASSWORD
#     / LOCAL_KEY_PASSWORD —— 若你自带 release keystore，脚本跳过自动生成

set -euo pipefail

# --- 路径与常量 ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RELEASE_DIR="$PROJECT_ROOT/release"
KEYSTORE="$SCRIPT_DIR/local-release.keystore"
KEYSTORE_ALIAS="release"
KEYSTORE_STORE_PASS="maiupload_local_release"
KEYSTORE_KEY_PASS="maiupload_local_release"

# --- 环境校验 ---
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "❌ JAVA_HOME 未设置。请 export JAVA_HOME=\"D:/Java/jdk-21\"（或你的 JDK 21 路径）后再跑此脚本。"
  exit 1
fi
if ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qiE "version \"21"; then
  echo "❌ JAVA_HOME 的 JDK 不是 21："
  "$JAVA_HOME/bin/java" -version 2>&1
  echo "   项目要求 JDK 21。请装 Temurin 21：https://adoptium.net/"
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "❌ ANDROID_HOME 未设置。请 export ANDROID_HOME=\"\$LOCALAPPDATA/Android/Sdk\"（或你的 SDK 路径）后再跑此脚本。"
  exit 1
fi
if [[ ! -d "$ANDROID_HOME/platforms" ]]; then
  echo "❌ ANDROID_HOME 下找不到 platforms/，不像合法 SDK：$ANDROID_HOME"
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

# --- 签名材料准备 ---
if [[ -n "${LOCAL_KEYSTORE_PATH:-}" ]]; then
  echo "ℹ️ 使用你提供的 release keystore：$LOCAL_KEYSTORE_PATH"
  if [[ -z "${LOCAL_STORE_PASSWORD:-}" || -z "${LOCAL_KEY_PASSWORD:-}" ]]; then
    echo "❌ 自带 keystore 需同时设 LOCAL_STORE_PASSWORD 与 LOCAL_KEY_PASSWORD 环境变量"
    exit 1
  fi
else
  if [[ ! -f "$KEYSTORE" ]]; then
    echo "🔧 首次构建：生成临时 debug keystore（仅本地自测用，不可分发 release）"
    "$JAVA_HOME/bin/keytool" -genkeypair \
      -alias "$KEYSTORE_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -keystore "$KEYSTORE" \
      -storepass "$KEYSTORE_STORE_PASS" \
      -keypass "$KEYSTORE_KEY_PASS" \
      -dname "CN=Maiupload, OU=Local, O=Local, L=Local, ST=Local, C=CN" \
      > /dev/null 2>&1
    echo "   keystore 已生成：$KEYSTORE"
  else
    echo "ℹ️ 复用已有 keystore：$KEYSTORE"
  fi
  export LOCAL_KEYSTORE_PATH="$KEYSTORE"
  export LOCAL_KEYSTORE_ALIAS="$KEYSTORE_ALIAS"
  export LOCAL_STORE_PASSWORD="$KEYSTORE_STORE_PASS"
  export LOCAL_KEY_PASSWORD="$KEYSTORE_KEY_PASS"
fi

# --- 构建 ---
echo "🏗️  开始 assembleRelease ..."
cd "$SCRIPT_DIR"
./gradlew assembleRelease --console=plain

# --- 收集产物 ---
APK_DIR="$SCRIPT_DIR/app/build/outputs/apk/release"
if [[ ! -d "$APK_DIR" ]]; then
  echo "❌ 构建成功但找不到 APK 输出目录：$APK_DIR"
  exit 1
fi
mkdir -p "$RELEASE_DIR"
# 清掉旧 release 产物（只清 APK/json，不动用户其他文件）
rm -f "$RELEASE_DIR"/*.apk "$RELEASE_DIR"/output-metadata.json 2>/dev/null || true

shopt -s nullglob
APKS=("$APK_DIR"/*.apk)
shopt -u nullglob
if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "❌ APK_DIR 下没有 .apk 文件：$APK_DIR"
  exit 1
fi
for apk in "${APKS[@]}"; do
  cp -f "$apk" "$RELEASE_DIR/"
  echo "📦 已拷到 release/：$(basename "$apk")"
done
if [[ -f "$APK_DIR/output-metadata.json" ]]; then
  cp -f "$APK_DIR/output-metadata.json" "$RELEASE_DIR/"
fi

echo "✅ release 构建完成。产物在：$RELEASE_DIR"
ls -lh "$RELEASE_DIR"/*.apk 2>/dev/null
