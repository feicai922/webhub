#!/usr/bin/env bash
# 从 logo/logo-抠图.png 生成 Android 图标与界面用图
#
#   ./tools/make-icons.sh [源图]
#
# 产物：
#   android/app/src/main/res/mipmap-*/ic_launcher_foreground.png   自适应图标前景
#   android/app/src/main/res/drawable-nodpi/logo.png              首页头部 logo
#   android/app/src/main/assets/www/logo.png                      首页页面引用的 logo
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${1:-logo/logo-抠图.png}"
[ -f "$SRC" ] || { echo "找不到 $SRC"; exit 1; }
command -v ffmpeg >/dev/null || { echo "需要 ffmpeg"; exit 1; }

# 自适应图标前景：108dp 画布，logo 占 58%，四周透明（留出系统裁切安全区）
gen_fg() {  # $1=画布边长px  $2=输出
  local logo off
  logo=$(python3 -c "print(int($1*0.58))")
  off=$(python3 -c "print(($1-$logo)//2)")
  ffmpeg -v error -y -i "$SRC" \
    -vf "scale=$logo:$logo:flags=lanczos,pad=$1:$1:$off:$off:color=black@0.0" "$2"
}

declare -A DP=( [mdpi]=108 [hdpi]=162 [xhdpi]=216 [xxhdpi]=324 [xxxhdpi]=432 )
for d in "${!DP[@]}"; do
  mkdir -p "android/app/src/main/res/mipmap-$d"
  gen_fg "${DP[$d]}" "android/app/src/main/res/mipmap-$d/ic_launcher_foreground.png"
done

# 界面用的 logo（256px 足够）
mkdir -p android/app/src/main/res/drawable-nodpi android/app/src/main/assets/www
ffmpeg -v error -y -i "$SRC" -vf "scale=256:256:flags=lanczos" android/app/src/main/res/drawable-nodpi/logo.png
cp -f android/app/src/main/res/drawable-nodpi/logo.png android/app/src/main/assets/www/logo.png

echo "图标生成完毕："
find android/app/src/main/res -name "ic_launcher_foreground.png" -o -name "logo.png" | sort
