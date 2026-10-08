#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$ROOT_DIR/unity/RiftArenaUnity"
OUTPUT_APK="${1:-$ROOT_DIR/dist/RiftArena-Unity-0.3.0.apk}"
if [[ "$OUTPUT_APK" != /* ]]; then OUTPUT_APK="$ROOT_DIR/$OUTPUT_APK"; fi
LOG_FILE="$ROOT_DIR/dist/unity-android-build.log"
mkdir -p "$(dirname "$OUTPUT_APK")" "$ROOT_DIR/dist"

UNITY_EDITOR_BIN="${UNITY_EDITOR:-}"
if [[ -z "$UNITY_EDITOR_BIN" ]]; then
  for candidate in unity Unity; do
    if command -v "$candidate" >/dev/null 2>&1; then
      UNITY_EDITOR_BIN="$(command -v "$candidate")"
      break
    fi
  done
fi
if [[ -z "$UNITY_EDITOR_BIN" ]]; then
  for candidate in /opt/unity/Editor/Unity /opt/Unity/Editor/Unity /Applications/Unity/Hub/Editor/*/Unity.app/Contents/MacOS/Unity; do
    if [[ -x "$candidate" ]]; then
      UNITY_EDITOR_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$UNITY_EDITOR_BIN" || ! -x "$UNITY_EDITOR_BIN" ]]; then
  echo "Unity Editor was not found. Install Unity 2022.3 LTS with Android Build Support, or set UNITY_EDITOR=/path/to/Unity." >&2
  exit 2
fi

UNITY_EDITOR_BIN="$(cd "$(dirname "$UNITY_EDITOR_BIN")" && pwd)/$(basename "$UNITY_EDITOR_BIN")"
echo "Building project: $PROJECT_DIR"
echo "APK output:      $OUTPUT_APK"
echo "Unity log:       $LOG_FILE"
"$UNITY_EDITOR_BIN" \
  -batchmode -quit -accept-apiupdate \
  -projectPath "$PROJECT_DIR" \
  -buildTarget Android \
  -executeMethod RiftArenaProjectSetup.PrepareAndroidBuild \
  "-riftOutput=$OUTPUT_APK" \
  -logFile "$LOG_FILE"

if [[ ! -s "$OUTPUT_APK" ]]; then
  echo "Unity finished without producing the expected APK. Review $LOG_FILE." >&2
  exit 1
fi
printf '\nBuilt APK: %s\n' "$OUTPUT_APK"
ls -lh "$OUTPUT_APK"
sha256sum "$OUTPUT_APK"
