#!/usr/bin/env bash
#
# Сборка APK без Android Studio и без Gradle:
#   aapt2 (ресурсы) -> ecj (javac) -> d8 (dex) -> zipalign.py -> apksigner
#
# Требуется каталог с инструментами (см. tools/fetch-toolchain.sh):
#   $TOOLCHAIN_DIR/bin/{aapt2,android.jar,d8.jar,apksigner.jar,ecj-3.45.0.jar,debug.keystore}
#   $TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime/bin/java
#
# Использование:
#   tools/build-apk.sh            # debug-подпись, ключ из отладочного keystore
#   RELEASE=1 tools/build-apk.sh  # подпись своим ключом (KEYSTORE/PASS/ALIAS)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.cache/tc}"
BIN="$TOOLCHAIN_DIR/bin"

APP_NAME="ModarAI"
PKG="com.modar.ai"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
MIN_SDK="${MIN_SDK:-24}"
TARGET_SDK="${TARGET_SDK:-34}"

JAVA_HOME_DIR="${JAVA_HOME_DIR:-$TOOLCHAIN_DIR/jdkpy/jdk4py/java-runtime}"
JAVA="$JAVA_HOME_DIR/bin/java"
AAPT2="$BIN/aapt2"
ANDROID_JAR="$BIN/android.jar"
D8_JAR="$BIN/d8.jar"
ECJ_JAR="$BIN/ecj-3.45.0.jar"
APKSIGNER_JAR="$BIN/apksigner.jar"

for f in "$JAVA" "$AAPT2" "$ANDROID_JAR" "$D8_JAR" "$ECJ_JAR" "$APKSIGNER_JAR"; do
  [ -e "$f" ] || { echo "Не найден инструмент: $f" >&2; exit 1; }
done

BUILD="$ROOT/build"
OUT="$ROOT/dist"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
RES_DIR="$ROOT/app/src/main/res"
SRC_DIR="$ROOT/app/src/main/java"

echo "==> 1/6 Подготовка манифеста (package=$PKG)"
mkdir -p "$BUILD/manifest"
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">|" \
  "$MANIFEST" > "$BUILD/manifest/AndroidManifest.xml"
grep -q "package=\"$PKG\"" "$BUILD/manifest/AndroidManifest.xml" || {
  echo "Не удалось подставить package в манифест" >&2; exit 1; }

echo "==> 2/6 aapt2 compile (ресурсы)"
find "$RES_DIR" -type f | sort > "$BUILD/res-files.txt"
"$AAPT2" compile --dir "$RES_DIR" -o "$BUILD/res.zip"

echo "==> 3/6 aapt2 link (ресурсы + R.java)"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD/manifest/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "==> 4/6 ecj (компиляция Java)"
find "$SRC_DIR" "$BUILD/gen" -name '*.java' | sort > "$BUILD/sources.txt"
wc -l < "$BUILD/sources.txt" | xargs echo "    файлов Java:"
# Важно: именно -source/-target 8. В android.jar лежат заглушки java.*,
# и на compliance level 9+ ECJ видит java.util одновременно в java.base и в
# classpath («accessible from more than one module») и падает.
"$JAVA" -jar "$ECJ_JAR" \
  -source 8 -target 8 \
  -encoding UTF-8 \
  -proc:none -nowarn \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "==> 5/6 d8 (dex)"
find "$BUILD/classes" -name '*.class' | sort > "$BUILD/classes.txt"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  $(cat "$BUILD/classes.txt")

echo "==> 6/6 упаковка, выравнивание и подпись"
python3 "$ROOT/tools/zipalign.py" "$BUILD/base.apk" "$BUILD/aligned.apk" "classes.dex=$BUILD/dex/classes.dex"

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
echo "==> Проверка подписи"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$APK" | head -6
echo
echo "==> Содержимое манифеста"
"$AAPT2" dump badging "$APK" | head -12
echo
ls -lh "$APK"
echo "Готово: $APK"
