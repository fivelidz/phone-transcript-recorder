#!/usr/bin/env bash
# record_hearing_dual.sh
# Records BOTH system audio (Committee voices) AND microphone (your voice)
# mixed into a single 16kHz mono WAV for Whisper transcription.
#
# Sources:
#   System monitor : alsa_output.pci-0000_c6_00.6.analog-stereo.monitor
#   Microphone     : alsa_input.usb-XIFT_Web_Camera_20251020-02.mono-fallback
#
# Usage:
#   ./record_hearing_dual.sh
#   Press Ctrl+C to stop — ffmpeg will finalise the WAV header cleanly.

set -euo pipefail

SYSTEM_MONITOR="alsa_output.pci-0000_c6_00.6.analog-stereo.monitor"
MIC_SOURCE="alsa_input.usb-XIFT_Web_Camera_20251020-02.mono-fallback"
RECORDINGS_DIR="$HOME/Documents/UTS/recordings"
TRANSCRIBE_SCRIPT="$HOME/Documents/UTS/scripts/transcribe_hearing.sh"

mkdir -p "$RECORDINGS_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M)
OUTPUT_WAV="$RECORDINGS_DIR/smac_hearing_${TIMESTAMP}.wav"

echo ""
echo "=============================================="
echo "  SMAC HEARING RECORDER — DUAL SOURCE"
echo "=============================================="
echo "  System audio : $SYSTEM_MONITOR"
echo "  Microphone   : $MIC_SOURCE"
echo "  Output file  : $OUTPUT_WAV"
echo "=============================================="
echo ""
echo "  *** RECORDING STARTED ***"
echo "  Press Ctrl+C to stop and auto-transcribe."
echo ""

# Trap Ctrl+C — ffmpeg handles SIGINT gracefully and closes the WAV header
cleanup() {
    echo ""
    echo "=============================================="
    echo "  Recording stopped."
    echo "  Saved to: $OUTPUT_WAV"
    echo "=============================================="
    echo ""

    if [ -f "$OUTPUT_WAV" ]; then
        SIZE=$(du -h "$OUTPUT_WAV" | cut -f1)
        DURATION=$(ffprobe "$OUTPUT_WAV" 2>&1 | grep Duration | awk '{print $2}' | tr -d ,)
        echo "  File size : $SIZE"
        echo "  Duration  : $DURATION"
        echo ""
    fi

    echo "  Launching transcription — please wait..."
    echo "  (This may take a few minutes depending on recording length)"
    echo ""

    if [ -f "$TRANSCRIBE_SCRIPT" ]; then
        bash "$TRANSCRIBE_SCRIPT" "$OUTPUT_WAV"
    else
        # Inline fallback transcription
        echo "  Transcribe script not found — running inline..."
        python3 - "$OUTPUT_WAV" <<'PYEOF'
import sys
from faster_whisper import WhisperModel

audio_file = sys.argv[1]
transcript_file = audio_file.replace(".wav", "_transcript.txt")

print(f"  Loading Whisper model (small)...")
model = WhisperModel("small", device="cpu", compute_type="int8")

print(f"  Transcribing {audio_file} ...")
segments, info = model.transcribe(audio_file, beam_size=5, language="en")

print(f"  Detected language: {info.language} (probability: {info.language_probability:.2f})")
print()

with open(transcript_file, "w") as f:
    for segment in segments:
        line = f"[{segment.start:.1f}s -> {segment.end:.1f}s] {segment.text.strip()}"
        print(line)
        f.write(line + "\n")

print()
print(f"  Transcript saved to: {transcript_file}")
PYEOF
    fi
}

trap cleanup EXIT

# Start recording — ffmpeg runs until Ctrl+C
ffmpeg -hide_banner -loglevel warning \
    -f pulse -i "$SYSTEM_MONITOR" \
    -f pulse -i "$MIC_SOURCE" \
    -filter_complex "[0:a][1:a]amix=inputs=2:duration=longest:dropout_transition=0[a]" \
    -map "[a]" \
    -ar 16000 -ac 1 -c:a pcm_s16le \
    "$OUTPUT_WAV"
