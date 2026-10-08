#!/usr/bin/env bash
#
# Сборка APK лаунчера Modar SAMP (samp-launcher/app) общим конвейером
# aapt2 → ecj → d8 → zipalign → apksigner (см. ../../tools/build-apk.sh).
#
# Использование:
#   samp-launcher/tools/build-apk.sh              # debug-подпись
#   RELEASE=1 KEYSTORE=... KS_PASS=... KEY_ALIAS=... samp-launcher/tools/build-apk.sh
#
# Необязательно:
#   API_URL=http://192.168.1.10:8090 — адрес сервера лаунчера, подставится при первом запуске
#   GAME_DIR=/storage/emulated/0/GTA — каталог игры SA-MP
#   VERSION_CODE=2 VERSION_NAME=1.1
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

export APP_DIR="samp-launcher/app"
export APP_NAME="${APP_NAME:-ModarSAMP}"
export PKG="com.modar.samp"
export OUT_DIR="${OUT_DIR:-$ROOT/samp-launcher/apk}"
export VERSION_CODE="${VERSION_CODE:-1}"
export VERSION_NAME="${VERSION_NAME:-1.0}"
export MIN_SDK="${MIN_SDK:-24}"
export TARGET_SDK="${TARGET_SDK:-34}"

exec "$ROOT/tools/build-apk.sh"
