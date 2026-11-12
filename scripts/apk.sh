#!/usr/bin/env bash
# ローカルで APK を作るときの最短経路。
#
#   ./scripts/apk.sh            # デバッグ APK（R8 なし・最速。普段はこれ）
#   ./scripts/apk.sh release    # リリース変種を速く（R8 とリソース圧縮を切る）
#   ./scripts/apk.sh xr         # legacyXr のデバッグ APK
#   ./scripts/apk.sh modern     # modern のデバッグ APK
#   ./scripts/apk.sh measure    # どこに時間がかかったかを出す（--profile）
#   ./scripts/apk.sh deps       # 依存だけ先に落とす（初回の長時間化対策）
#   ./scripts/apk.sh stop       # Gradle / Kotlin デーモンを止める
#
# 環境変数:
#   LOW_RAM=1     メモリの少ない端末向け（ヒープとワーカーを絞る）
#   OFFLINE=1     --offline（依存が揃っているとき。ネットワーク待ちを無くす）
#   VARIANT=...   release で作る変種（legacy / modern / legacyXr）
set -euo pipefail

cd "$(dirname "$0")/.."

VARIANT="${VARIANT:-legacy}"
MODE="${1:-dev}"
cap() { printf '%s%s' "$(printf '%s' "$1" | cut -c1 | tr '[:lower:]' '[:upper:]')" "$(printf '%s' "$1" | cut -c2-)"; }

ARGS=()

case "$MODE" in
  dev|debug) TASK=":app:assembleLegacyDebug" ;;
  modern)    TASK=":app:assembleModernDebug" ;;
  xr)        TASK=":app:assembleLegacyXrDebug" ;;
  release)   TASK=":app:assemble$(cap "$VARIANT")Release"; ARGS+=("-PfastRelease") ;;
  deps)      TASK=":app:dependencies"; ARGS+=("--configuration" "legacyDebugRuntimeClasspath") ;;
  measure)   TASK=":app:assembleLegacyDebug"; ARGS+=("--profile") ;;
  stop)      exec ./gradlew --stop ;;
  *)         echo "usage: $0 [dev|debug|modern|xr|release|deps|measure|stop]" >&2; exit 2 ;;
esac

# 反復ビルドを速くする既定。clean はしない（差分ビルドを壊すだけ）。
ARGS+=(
  "--build-cache"
  "--console=plain"
  "--max-workers=${MAX_WORKERS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"
)

if [ "${OFFLINE:-0}" = "1" ]; then ARGS+=("--offline"); fi

if [ "${LOW_RAM:-0}" = "1" ]; then
  ARGS+=(
    "-Dorg.gradle.jvmargs=-Xmx2560m"
    "-Pkotlin.daemon.jvmargs=-Xmx1536m"
  )
fi

START=$(date +%s)
./gradlew "$TASK" "${ARGS[@]}"
END=$(date +%s)

echo
echo "== 所要時間: $((END - START)) 秒 =="
find app/build/outputs/apk -name "*.apk" -newermt "-5 minutes" -exec ls -lh {} \; 2>/dev/null || true

if [ "$MODE" = "measure" ]; then
  REPORT=$(ls -t app/build/reports/profile/*.html 2>/dev/null | head -1 || true)
  echo
  if [ -n "$REPORT" ]; then
    echo "== プロファイル: $REPORT =="
    echo "ブラウザで開くと、タスクごとの所要時間が一覧できます。"
    # 合計時間の列だけ拾って、かかった順に上位を出す（雰囲気を掴むため）。
    grep -oE '>[0-9]+m?s<|[0-9.]+s' "$REPORT" 2>/dev/null | tr -d '><' | sort -u | head -20 || true
  else
    echo "プロファイルがありません（--profile が効いていません）"
  fi
fi
