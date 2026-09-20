#!/usr/bin/env bash
#
# Подключает модуль голосовых вызовов (WebRTC) для приложения родительского контроля.
#
# Что делает:
#   1. скачивает AAR-модуль WebRTC с Maven Central;
#   2. распаковывает classes.jar и нативные библиотеки (arm64-v8a, armeabi-v7a, x86_64);
#   3. кладёт всё в parental/tools/cache/webrtc.
# После этого parental/tools/build-apk.sh сам добавит модуль в APK и кнопка
# «Позвонить» начнёт работать как интернет-звонок (родитель ↔ ребёнок).
#
# Важно: нативные библиотеки добавляют к APK ~12–20 МБ.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE="${WEBRTC_DIR:-$ROOT/parental/tools/cache/webrtc}"
VERSION="${WEBRTC_VERSION:-125.6422.07}"
GROUP="io/github/webrtc-sdk/android"
BASE="https://repo1.maven.org/maven2/$GROUP/$VERSION"
AAR="android-$VERSION.aar"

mkdir -p "$CACHE"
cd "$CACHE"

echo "==> Скачиваю WebRTC $VERSION"
if [ ! -f "$AAR" ]; then
  curl -fSL -o "$AAR" "$BASE/$AAR" || {
    echo "Не удалось скачать $BASE/$AAR" >&2
    echo "Проверьте версию: https://repo1.maven.org/maven2/io/github/webrtc-sdk/android/" >&2
    exit 1
  }
fi
ls -lh "$AAR"

echo "==> Распаковываю"
rm -rf classes.jar jni
mkdir -p jni
python3 - "$CACHE/$AAR" "$CACHE" <<'PY'
import os, sys, zipfile
archive, target = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(archive) as z:
    if "classes.jar" in z.namelist():
        with open(os.path.join(target, "classes.jar"), "wb") as out:
            out.write(z.read("classes.jar"))
    for name in z.namelist():
        if name.startswith("jni/") and name.endswith(".so"):
            dest = os.path.join(target, name)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as out:
                out.write(z.read(name))
            print("   ", name)
PY

echo
echo "Готово. Модуль в: $CACHE"
echo "Теперь пересоберите APK: parental/tools/build-apk.sh"
echo "Проверка: в приложении на телефоне ребёнка в разделе разрешений появится"
echo "кнопка «🎙 Микрофон и камера» — без неё разговор не начнётся."
