#!/usr/bin/env sh
set -eu
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VER=8.9
BASE="$DIR/.gradle-dist"
HOME_DIR="$BASE/gradle-$VER"
ZIP="$BASE/gradle-$VER-bin.zip"
if [ ! -x "$HOME_DIR/bin/gradle" ]; then
  mkdir -p "$BASE"
  echo "Gradle $VER 다운로드 중..."
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail -o "$ZIP" "https://services.gradle.org/distributions/gradle-$VER-bin.zip"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VER-bin.zip"
  else
    echo "curl 또는 wget이 필요합니다." >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$HOME_DIR/bin/gradle" "$@"
