#!/bin/bash
# 装机前检查：APK 里的 libquickjs-android-wrapper.so 用到的系统函数，车机上是否都有。
# 结果为 0 个缺失才算通过（编译成功 ≠ 能在 4.4 上加载）。
#
# 用法（车机 USB 连着电脑）：
#   bash tools/check_so_symbols.sh [apk路径]
#   默认 apk：app/build/outputs/apk/debug/app-debug.apk
# 不连车机时：先在有车机时执行一次，会把系统库缓存到 tools/device_libs/，之后离线也能查。
set -e
cd "$(dirname "$0")/.."
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
SO_IN_APK=lib/armeabi-v7a/libquickjs-android-wrapper.so
LIBDIR=tools/device_libs
WORK=$(mktemp -d)

# ---- 找 NDK 里的 llvm-nm / llvm-readelf ----
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
NDK="$SDK/ndk/25.2.9519653"
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1)
NM="$BIN/llvm-nm"; READELF="$BIN/llvm-readelf"
[ -x "$NM" ] || { echo "!! 找不到 $NM（请先安装 NDK 25.2.9519653）"; exit 1; }

# ---- 从 APK 抽出 so ----
[ -f "$APK" ] || { echo "!! 找不到 APK：$APK"; exit 1; }
unzip -o -q "$APK" "$SO_IN_APK" -d "$WORK" || { echo "!! APK 里没有 $SO_IN_APK"; exit 1; }
SO="$WORK/$SO_IN_APK"

NEEDED=$("$READELF" -d "$SO" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
echo ">> so 依赖的系统库：" $NEEDED

# ---- 取车机系统库（有 adb 就拉最新的，否则用缓存）----
mkdir -p "$LIBDIR"
if adb get-state >/dev/null 2>&1; then
  echo ">> 从车机拉取系统库（Android $(adb shell getprop ro.build.version.release | tr -d '\r')）"
  for l in $NEEDED; do adb pull "/system/lib/$l" "$LIBDIR/$l" >/dev/null 2>&1 || echo "   车机上没有 /system/lib/$l"; done
else
  echo ">> 车机未连接，使用缓存：$LIBDIR"
fi

# ---- 车机库导出的函数 ----
: > "$WORK/exported.txt"
for l in $NEEDED; do
  if [ -f "$LIBDIR/$l" ]; then
    "$NM" -D --defined-only "$LIBDIR/$l" | awk '{print $NF}' | sed 's/@.*//' >> "$WORK/exported.txt"
  else
    echo "!! 缺少车机库 $l，无法检查（连上车机重试）"; exit 1
  fi
done
sort -u "$WORK/exported.txt" -o "$WORK/exported.txt"

# ---- so 需要的强引用函数（弱引用 w 缺了也能加载，不算）----
"$NM" -D --undefined-only "$SO" | awk '$1=="U"{print $2}' | sed 's/@.*//' | sort -u > "$WORK/needed.txt"

MISSING=$(comm -23 "$WORK/needed.txt" "$WORK/exported.txt")
TOTAL=$(wc -l < "$WORK/needed.txt" | tr -d ' ')
if [ -z "$MISSING" ]; then
  echo "✅ 通过：so 需要的 $TOTAL 个系统函数，车机上全部存在，可以装机"
  rm -rf "$WORK"; exit 0
else
  echo "❌ 车机缺少以下函数（共 $(echo "$MISSING" | wc -l | tr -d ' ') 个），装上去会报 UnsatisfiedLinkError："
  echo "$MISSING" | sed 's/^/   /'
  echo "把这段输出发给我。"
  rm -rf "$WORK"; exit 2
fi
