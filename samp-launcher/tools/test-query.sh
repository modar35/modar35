#!/usr/bin/env bash
#
# Проверка ядра лаунчера на настоящем UDP: поднимает два mock-сервера SA-MP
# (UTF-8 и CP1251) и прогоняет по ним опрос из кода приложения (SampQuery.java).
#
#   samp-launcher/tools/test-query.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.cache/tc}"
JAVA="$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime/bin/java"
ECJ="$TOOLCHAIN_DIR/bin/ecj-3.45.0.jar"

for tool in "$JAVA" "$ECJ"; do
  [ -e "$tool" ] || { echo "Нет инструмента $tool — выполните tools/fetch-toolchain.sh" >&2; exit 1; }
done
command -v node >/dev/null || { echo "Нужен node для mock-сервера" >&2; exit 1; }

WORK="$(mktemp -d)"
PORT_UTF8=7831
PORT_CP1251=7832
PORT_DEAD=7899
CYRILLIC_NAME="Русский Мод | Сервер"  # ожидаемое имя задано в SampQueryTest.java

cleanup() {
  [ -n "${MOCK1:-}" ] && kill "$MOCK1" 2>/dev/null || true
  [ -n "${MOCK2:-}" ] && kill "$MOCK2" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "==> 1/3 Mock-серверы SA-MP (UDP)"
node "$ROOT/samp-launcher/tools/mock-samp-server.js" "$PORT_UTF8" \
  --name "Modar Test Server | Mobile" --players 3 > "$WORK/mock1.log" 2>&1 &
MOCK1=$!
node "$ROOT/samp-launcher/tools/mock-samp-server.js" "$PORT_CP1251" \
  --cp1251 --name "$CYRILLIC_NAME" --players 2 > "$WORK/mock2.log" 2>&1 &
MOCK2=$!

for i in $(seq 1 60); do
  if grep -q "слушаем" "$WORK/mock1.log" 2>/dev/null && grep -q "слушаем" "$WORK/mock2.log" 2>/dev/null; then
    break
  fi
  sleep 0.1
done
cat "$WORK/mock1.log" "$WORK/mock2.log" || true

echo "==> 2/3 Компиляция ядра лаунчера (ecj, без Android)"
mkdir -p "$WORK/classes"
"$JAVA" -Dfile.encoding=UTF-8 -jar "$ECJ" -source 8 -target 8 -encoding UTF-8 -proc:none -nowarn \
  -d "$WORK/classes" \
  "$ROOT/samp-launcher/app/src/main/java/com/modar/samp/SampQuery.java" \
  "$ROOT/samp-launcher/app/src/main/java/com/modar/samp/Hash.java" \
  "$ROOT/samp-launcher/tools/SampQueryTest.java" 2>&1 | grep -v "^$" || true

echo "==> 3/3 Прогон проверок"
"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$WORK/classes" \
  com.modar.samp.SampQueryTest "$PORT_UTF8" "$PORT_CP1251" "$PORT_DEAD"
