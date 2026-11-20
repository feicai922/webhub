#!/usr/bin/env bash
# DeepGravity 一键构建
#
#   ./build.sh            打 release APK
#   ./build.sh debug      打 debug APK
#   ./build.sh icons      从 logo 重新生成图标
#
set -euo pipefail
cd "$(dirname "$0")"

TC="$PWD/.toolchain"
export JAVA_HOME="$TC/jdk-17.0.21.1+2"
export ANDROID_HOME="$TC/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$TC/gradle-8.7/bin:$PATH"

TARGET="${1:-release}"

build_apk() {
  local variant="$1" out="$2"
  echo "==> 构建 Android APK ($variant)"

  # 签名密钥：不存在就生成一份（自签名，仅用于侧载）
  if [ ! -f android/keystore/webhub.jks ]; then
    mkdir -p android/keystore
    keytool -genkeypair -keystore android/keystore/webhub.jks \
      -alias webhub -keyalg RSA -keysize 2048 -validity 10950 \
      -storepass webhub123 -keypass webhub123 \
      -dname "CN=DeepGravity, O=Personal, C=CN" >/dev/null 2>&1
    echo "    已生成签名密钥 android/keystore/webhub.jks"
  fi

  ( cd android && gradle --no-daemon -q "assemble${variant}" )

  mkdir -p dist
  local src="android/app/build/outputs/apk/${variant,,}/app-${variant,,}.apk"
  rm -f "$out"
  mv -f "$src" "$out"           # 同一文件系统内改名，不搬数据
  echo "    -> $out  ($(du -h "$out" | cut -f1))"
}

case "$TARGET" in
  release) build_apk Release dist/DeepGravity.apk ;;
  debug)   build_apk Debug   dist/DeepGravity-debug.apk ;;
  icons)   ./tools/make-icons.sh ;;
  *) echo "用法: $0 [release|debug|icons]"; exit 1 ;;
esac

echo
echo "完成。产物在 dist/"
ls -lh dist/ 2>/dev/null || true
