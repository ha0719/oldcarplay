#!/bin/bash
# 下载 QuickJS 源码（与洛雪移动版同一个库 HarlonWang/quickjs-wrapper，目标版本 2.4.0），
# 由本工程以 minSdk 19 自己编译，解决车机 Android 4.4 上 "cannot locate symbol fesetround"。
# 用法：在 haplayer-lx 目录执行  bash tools/setup_quickjs.sh
#
# 注意：Maven 版本号和 git tag 不一定是同一套，所以按以下顺序定位 2.4.0：
#   1. tag 2.4.0 / v2.4.0
#   2. 在 CHANGELOG.md 历史里找"添加 2.4.1 条目"的那次提交，取它的上一个提交（= 2.4.0 时的代码）
#   3. 都找不到就用 main 最新代码（Java API 基本兼容），并给出提示
set -e
cd "$(dirname "$0")/.."
mkdir -p third_party
DIR=third_party/quickjs-wrapper
URL=https://github.com/HarlonWang/quickjs-wrapper.git

if [ ! -d "$DIR/.git" ]; then
  echo ">> 克隆 quickjs-wrapper ..."
  git clone "$URL" "$DIR"
fi
cd "$DIR"
git fetch --tags --quiet || true

REF=""
for t in 2.4.0 v2.4.0; do
  if git rev-parse -q --verify "refs/tags/$t" >/dev/null; then REF="$t"; break; fi
done

if [ -z "$REF" ]; then
  echo ">> 没有 2.4.0 的 tag，从 CHANGELOG 历史定位 ..."
  C241=$(git log --format=%H -S "2.4.1 (" -- CHANGELOG.md | tail -1 || true)
  if [ -n "$C241" ]; then REF="${C241}^"; fi
fi

if [ -n "$REF" ]; then
  echo ">> 切换到 $REF"
  git checkout --quiet --detach "$REF"
else
  echo "!! 没能定位到 2.4.0，改用 main 最新代码（如编译报 Java API 不兼容，把报错发给我）"
  git checkout --quiet main || git checkout --quiet master
fi

git submodule update --init --recursive

if [ -f native/quickjs/quickjs.c ] && [ -f wrapper-android/src/main/CMakeLists.txt ]; then
  echo ">> OK：源码就绪  $(git log -1 --format='%h %ad %s' --date=short)"
  grep -m1 -E '^[0-9]+\.[0-9]+\.[0-9]+' CHANGELOG.md | sed 's/^/   CHANGELOG 最新条目：/' || true
else
  echo "!! 源码不完整：缺 native/quickjs（子模块）或 wrapper-android，请检查网络后重试"
  exit 1
fi
