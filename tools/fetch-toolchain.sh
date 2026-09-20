#!/usr/bin/env bash
#
# Скачивает минимальный тулчейн для сборки APK без Android Studio.
# Все компоненты берутся из публичных пакетов (PyPI, npm) и кладутся в
# $TOOLCHAIN_DIR (по умолчанию ~/.cache/tc):
#
#   jdkpy/jdk4py/java-runtime   — JRE 25 (java, keytool) из PyPI-пакета jdk4py
#   bin/aapt2                   — linux-сборка AAPT2 из npm-пакета aaptjs3
#   bin/android.jar             — android.jar API 34 (с resources.arsc)
#   bin/d8.jar                  — D8 (dex-компилятор)
#   bin/apksigner.jar           — apksigner
#   bin/ecj-3.45.0.jar          — компилятор Java (Eclipse Compiler for Java)
#   bin/debug.keystore          — отладочный ключ (alias androiddebugkey, пароль android)
#
# Замечание: javac в jdk4py отсутствует (это JRE), поэтому Java компилируется ECJ —
# результат тот же: обычные .class, которые затем переводит в dex D8.
#
set -euo pipefail

TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.cache/tc}"
WORK="$(mktemp -d)"
mkdir -p "$TOOLCHAIN_DIR/bin"

echo "==> Каталог инструментов: $TOOLCHAIN_DIR"

echo "==> 1/4 JRE (PyPI: jdk4py)"
python3 -m pip download jdk4py --no-deps -d "$WORK" -q
WHL="$(ls "$WORK"/jdk4py-*.whl | head -1)"
mkdir -p "$TOOLCHAIN_DIR/jdkpy"
python3 -m zipfile -e "$WHL" "$TOOLCHAIN_DIR/jdkpy/"
chmod -R +x "$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime/bin" || true
"$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime/bin/java" -version

echo "==> 2/4 AAPT2 для linux (npm: aaptjs3)"
curl -sSL -o "$WORK/aaptjs3.tgz" "$(python3 - <<'PY'
import json, urllib.request
d = json.load(urllib.request.urlopen("https://registry.npmjs.org/aaptjs3"))
print(d["versions"][d["dist-tags"]["latest"]]["dist"]["tarball"])
PY
)"
tar xzf "$WORK/aaptjs3.tgz" -C "$WORK"
cp "$WORK/package/bin/x64/linux/aapt2" "$TOOLCHAIN_DIR/bin/aapt2"
chmod +x "$TOOLCHAIN_DIR/bin/aapt2"

echo "==> 3/4 android.jar, d8, apksigner, ecj, debug.keystore (npm: @drxiaozhi/minapk)"
curl -sSL -o "$WORK/minapk.tgz" "$(python3 - <<'PY'
import json, urllib.request
d = json.load(urllib.request.urlopen("https://registry.npmjs.org/@drxiaozhi%2Fminapk"))
print(d["versions"][d["dist-tags"]["latest"]]["dist"]["tarball"])
PY
)"
tar xzf "$WORK/minapk.tgz" -C "$WORK"
for f in android.jar d8.jar apksigner.jar ecj-3.45.0.jar debug.keystore; do
  cp "$WORK/package/tools/$f" "$TOOLCHAIN_DIR/bin/$f"
done

echo "==> 4/4 Проверка"
"$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime/bin/java" -version
"$TOOLCHAIN_DIR/bin/aapt2" version
"$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime/bin/java" -cp "$TOOLCHAIN_DIR/bin/d8.jar" com.android.tools.r8.D8 --version
"$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime/bin/java" -jar "$TOOLCHAIN_DIR/bin/apksigner.jar" --version
"$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime/bin/java" -jar "$TOOLCHAIN_DIR/bin/ecj-3.45.0.jar" -version

rm -rf "$WORK"
echo "Готово. Теперь: tools/build-apk.sh"
