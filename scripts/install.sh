#!/usr/bin/env bash
# install.sh — build, install, push model, and grant perms for Phone Transcript Recorder.
#
# Usage: ./scripts/install.sh [model]
#   model defaults to ggml-base.en.bin
#
# Handles the HyperOS adb-install dialog automatically (see AGENT_GOTCHAS.md).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
PKG="com.fivelidz.transcriber.debug"
MODEL="${1:-ggml-base.en.bin}"
MODEL_SRC="$HOME/projects/MASTER_PROJECTS/whisper-cpp/models/$MODEL"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"

echo "▸ Checking device…"
adb get-state >/dev/null 2>&1 || { echo "❌ No adb device. Plug in the phone."; exit 1; }

echo "▸ Building debug APK…"
( cd "$ANDROID_DIR" && echo "sdk.dir=$ANDROID_HOME" > local.properties && \
  ./gradlew :app:assembleDebug --no-daemon ) || { echo "❌ build failed"; exit 1; }

APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo "▸ Disabling install verifiers…"
adb shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1
adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1

echo "▸ Installing (auto-tapping MIUI dialog)…"
adb push "$APK" /data/local/tmp/tr.apk >/dev/null
adb shell input keyevent KEYCODE_WAKEUP; sleep 0.3
( adb shell "pm install -r -t /data/local/tmp/tr.apk" 2>&1 ) &
PID=$!
sleep 2
# Dialog button coordinates for Redmi Note 14 5G (1080x2400). Adjust if they drift.
adb shell input tap 539 1976   # "Remember my choice"
sleep 0.4
adb shell input tap 310 2103   # "Install"
wait $PID

echo "▸ Pushing whisper model ($MODEL)…"
if [[ -f "$MODEL_SRC" ]]; then
    adb push "$MODEL_SRC" "/sdcard/Download/$MODEL" >/dev/null
    echo "   pushed to /sdcard/Download/$MODEL"
else
    echo "   ⚠ model not found at $MODEL_SRC — push it manually to /sdcard/Download/"
fi

echo "▸ Granting permissions…"
for p in RECORD_AUDIO READ_PHONE_STATE READ_CONTACTS READ_MEDIA_AUDIO POST_NOTIFICATIONS; do
    adb shell pm grant "$PKG" android.permission.$p 2>/dev/null
done
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null

echo "▸ Ensuring native call recorder is enabled…"
adb shell pm enable com.mediatek.callrecorder 2>/dev/null

echo ""
echo "✅ Installed. Open 'Transcriber', tap 'Grant all-files access', then 'Start watching'."
echo "   Recorded calls in /sdcard/MIUI/sound_recorder/call_rec/ will auto-transcribe into"
echo "   qalarc-notes (Documents/qalarc-notes/notes/*.md)."
