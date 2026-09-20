#!/usr/bin/env bash
#
# Сборка APK «Modar Family» без Android Studio и без Gradle:
#   aapt2 (ресурсы) → ecj (javac) → d8 (dex) → zipalign.py → apksigner
#
# Требуется каталог инструментов из основного проекта:
#   ../tools/fetch-toolchain.sh   (один раз; кладёт всё в ~/.cache/tc)
#
# Использование:
#   parental/tools/build-apk.sh                     debug-подпись
#   RELEASE=1 KEYSTORE=... KS_PASS=... KEY_ALIAS=... parental/tools/build-apk.sh
#
# Необязательное предзаполнение (подставится при первом запуске приложения):
#   MODAR_SERVER=http://1.2.3.4:8080   адрес сервера семьи
#   MODAR_CODE=123456                  код сопряжения для режима «Ребёнок»
#   MODAR_EMAIL / MODAR_PASSWORD       аккаунт родителя
#
# Модуль голосовых вызовов (WebRTC) подключается, если он уже скачан:
#   parental/tools/fetch-webrtc.sh
# Без него приложение собирается и работает, только кнопка звонка предложит
# обычный телефонный звонок.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_DIR="$ROOT/parental/android"
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.cache/tc}"
BIN="$TOOLCHAIN_DIR/bin"

APP_NAME="ModarFamily"
PKG="com.modar.family"
VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-2.1.0}"
MIN_SDK="${MIN_SDK:-24}"
TARGET_SDK="${TARGET_SDK:-34}"
WEBRTC_DIR="${WEBRTC_DIR:-$ROOT/parental/tools/cache/webrtc}"

JAVA_HOME_DIR="${JAVA_HOME_DIR:-$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime}"
JAVA="$JAVA_HOME_DIR/bin/java"
AAPT2="$BIN/aapt2"
ANDROID_JAR="$BIN/android.jar"
D8_JAR="$BIN/d8.jar"
ECJ_JAR="$BIN/ecj-3.45.0.jar"
APKSIGNER_JAR="$BIN/apksigner.jar"

for f in "$JAVA" "$AAPT2" "$ANDROID_JAR" "$D8_JAR" "$ECJ_JAR" "$APKSIGNER_JAR"; do
  [ -e "$f" ] || { echo "Не найден инструмент: $f" >&2; echo "Сначала: $ROOT/tools/fetch-toolchain.sh" >&2; exit 1; }
done

BUILD="$APP_DIR/build"
OUT="$APP_DIR/dist"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/assets" "$OUT"

MANIFEST="$APP_DIR/AndroidManifest.xml"
RES_DIR="$APP_DIR/res"
SRC_DIR="$APP_DIR/java"

echo "==> 1/6 Манифест (package=$PKG)"
python3 - "$MANIFEST" "$BUILD/AndroidManifest.xml" "$PKG" <<'PY'
import re, sys
src, dst, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(src, encoding="utf-8").read()
if 'package="' not in text.split(">", 1)[0]:
    text = re.sub(r"<manifest\b", '<manifest package="%s"' % pkg, text, count=1)
open(dst, "w", encoding="utf-8").write(text)
PY
grep -q "package=\"$PKG\"" "$BUILD/AndroidManifest.xml" || { echo "Не удалось подставить package" >&2; exit 1; }

echo "==> 1b/6 Предзаполнение настроек"
if [ -n "${MODAR_SERVER:-}" ]; then
  printf '%s' "$MODAR_SERVER" > "$BUILD/assets/modar_server.txt"
  echo "    сервер: $MODAR_SERVER"
fi
if [ -n "${MODAR_CODE:-}" ]; then
  printf '%s' "$MODAR_CODE" > "$BUILD/assets/modar_code.txt"
  echo "    код сопряжения: вшит (действует, пока родитель не сменил его)"
fi
if [ -n "${MODAR_EMAIL:-}" ]; then
  printf '%s' "$MODAR_EMAIL" > "$BUILD/assets/modar_email.txt"
fi
if [ -n "${MODAR_PASSWORD:-}" ]; then
  printf '%s' "$MODAR_PASSWORD" > "$BUILD/assets/modar_password.txt"
  echo "    ! Пароль вшит в APK — такой файл нельзя передавать посторонним."
fi

echo "==> 2/6 aapt2 compile (ресурсы)"
"$AAPT2" compile --dir "$RES_DIR" -o "$BUILD/res.zip"

echo "==> 3/6 aapt2 link (ресурсы + R.java)"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  -A "$BUILD/assets" \
  "$BUILD/res.zip"

echo "==> 4/6 ecj (компиляция Java)"
find "$SRC_DIR" "$BUILD/gen" -name '*.java' | sort > "$BUILD/sources.txt"
wc -l < "$BUILD/sources.txt" | xargs echo "    файлов Java:"
"$JAVA" -jar "$ECJ_JAR" \
  -source 8 -target 8 \
  -encoding UTF-8 \
  -proc:none -nowarn \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "==> 5/6 d8 (dex)"
find "$BUILD/classes" -name '*.class' | sort > "$BUILD/classes.txt"
DEX_INPUTS="$(cat "$BUILD/classes.txt")"
WEBRTC_JAR="$WEBRTC_DIR/classes.jar"
HAS_WEBRTC=0
if [ -f "$WEBRTC_JAR" ]; then
  HAS_WEBRTC=1
  DEX_INPUTS="$DEX_INPUTS $WEBRTC_JAR"
  echo "    + модуль голосовых вызовов WebRTC"
fi
# shellcheck disable=SC2086
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  $DEX_INPUTS

echo "==> 6/6 упаковка, выравнивание и подпись"
ADDITIONS=("classes.dex=$BUILD/dex/classes.dex")
if [ "$HAS_WEBRTC" = "1" ]; then
  while IFS= read -r so; do
    [ -n "$so" ] || continue
    abi="$(basename "$(dirname "$so")")"
    ADDITIONS+=("lib/$abi/$(basename "$so")=$so")
    echo "    + нативная библиотека: lib/$abi/$(basename "$so")"
  done < <(find "$WEBRTC_DIR/jni" -name '*.so' 2>/dev/null | sort)
fi
python3 "$ROOT/tools/zipalign.py" "$BUILD/base.apk" "$BUILD/aligned.apk" "${ADDITIONS[@]}"

if [ "${RELEASE:-0}" = "1" ]; then
  KS="${KEYSTORE:?Укажите KEYSTORE=/путь/keystore.jks}"
  KS_PASS="${KS_PASS:?Укажите KS_PASS=пароль}"
  KEY_ALIAS="${KEY_ALIAS:?Укажите KEY_ALIAS=алиас}"
  KEY_PASS="${KEY_PASS:-$KS_PASS}"
  SUFFIX="release"
else
  KS="$BIN/debug.keystore"
  KS_PASS="android"
  KEY_ALIAS="androiddebugkey"
  KEY_PASS="android"
  SUFFIX="debug"
fi

APK="$OUT/${APP_NAME}-${VERSION_NAME}-${SUFFIX}.apk"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$APK" \
  "$BUILD/aligned.apk"

echo
echo "==> Проверка подписи и манифеста"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$APK" > "$BUILD/verify.txt" 2>&1 || true
head -4 "$BUILD/verify.txt"
"$AAPT2" dump badging "$APK" > "$BUILD/badging.txt" 2>&1 || true
grep -E "^(package|launchable-activity|uses-permission)" "$BUILD/badging.txt" | head -14
echo
ls -lh "$APK"
# Копия для скачивания с сервера: /app/modar-family.apk
mkdir -p "$ROOT/parental/apk"
cp "$APK" "$ROOT/parental/apk/modar-family.apk"
cp "$APK" "$ROOT/parental/apk/$(basename "$APK")"
echo "Скопировано в parental/apk/modar-family.apk (отдаётся сервером по адресу /app/modar-family.apk)"
if [ "$HAS_WEBRTC" = "1" ]; then
  echo "Готово (с голосовыми вызовами через интернет)."
else
  echo "Готово. Голосовые вызовы через интернет выключены: запустите parental/tools/fetch-webrtc.sh и соберите снова."
fi
