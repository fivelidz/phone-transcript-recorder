#!/usr/bin/env bash
# transcribe_hearing.sh — Transcribe a meeting recording using faster-whisper
# Usage: bash ~/Documents/UTS/scripts/transcribe_hearing.sh [wav_file]
#        If no argument given, uses the most recent file in ~/Documents/UTS/recordings/

RECORDINGS_DIR="$HOME/Documents/UTS/recordings"

# Determine input file
if [ -n "$1" ]; then
    INPUT_WAV="$1"
else
    INPUT_WAV=$(ls -t "$RECORDINGS_DIR"/*.wav 2>/dev/null | head -1)
    if [ -z "$INPUT_WAV" ]; then
        echo "ERROR: No WAV files found in $RECORDINGS_DIR"
        echo "Usage: bash $0 /path/to/recording.wav"
        exit 1
    fi
    echo "No file specified — using most recent: $INPUT_WAV"
fi

if [ ! -f "$INPUT_WAV" ]; then
    echo "ERROR: File not found: $INPUT_WAV"
    exit 1
fi

# Output text file alongside the wav
OUTPUT_TXT="${INPUT_WAV%.wav}.txt"
OUTPUT_SRT="${INPUT_WAV%.wav}.srt"

echo ""
echo "=========================================="
echo " TRANSCRIPTION STARTING"
echo " Input:  $INPUT_WAV"
echo " Output: $OUTPUT_TXT"
echo " Model:  faster-whisper small (cached)"
echo "=========================================="
echo ""

# Use faster-whisper (small model — already cached, fast, accurate for meetings)
python3 - "$INPUT_WAV" "$OUTPUT_TXT" "$OUTPUT_SRT" << 'PYEOF'
import sys
import time
from pathlib import Path

input_wav = sys.argv[1]
output_txt = sys.argv[2]
output_srt = sys.argv[3]

try:
    from faster_whisper import WhisperModel
except ImportError:
    print("ERROR: faster_whisper not importable. Check your Python environment.")
    sys.exit(1)

print("Loading faster-whisper 'small' model (int8, CPU)...")
t0 = time.time()
model = WhisperModel("small", device="cpu", compute_type="int8")
print(f"Model loaded in {time.time()-t0:.1f}s")

print("Transcribing...")
t1 = time.time()
segments, info = model.transcribe(
    input_wav,
    language="en",
    beam_size=5,
    vad_filter=True,
    vad_parameters=dict(min_silence_duration_ms=500),
)

lines = []
srt_lines = []
seg_list = list(segments)  # consume generator

for i, seg in enumerate(seg_list, 1):
    line = f"[{seg.start:.1f}s - {seg.end:.1f}s] {seg.text.strip()}"
    lines.append(line)
    print(line)
    # SRT format
    def fmt_srt_time(t):
        h = int(t // 3600); m = int((t % 3600) // 60); s = int(t % 60); ms = int((t % 1) * 1000)
        return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"
    srt_lines.append(f"{i}\n{fmt_srt_time(seg.start)} --> {fmt_srt_time(seg.end)}\n{seg.text.strip()}\n")

elapsed = time.time() - t1
duration = info.duration if hasattr(info, 'duration') else 0
rtf = elapsed / duration if duration > 0 else 0

print("")
print(f"Transcription done in {elapsed:.1f}s | Audio duration: {duration:.1f}s | RTF: {rtf:.2f}x")
print(f"Language detected: {info.language} (prob {info.language_probability:.1%})")
print(f"Segments: {len(lines)}")

# Write plain text (with timestamps)
header = f"# Meeting Transcription\n# File: {input_wav}\n# Transcribed: {time.strftime('%Y-%m-%d %H:%M:%S')}\n# Model: faster-whisper small\n# Duration: {duration:.1f}s | RTF: {rtf:.2f}x\n\n"
with open(output_txt, 'w') as f:
    f.write(header)
    f.write('\n'.join(lines))
    f.write('\n')

# Write SRT
with open(output_srt, 'w') as f:
    f.write('\n'.join(srt_lines))

print(f"\nSaved plain text: {output_txt}")
print(f"Saved SRT:        {output_srt}")
PYEOF

EXIT_CODE=$?
if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo " TRANSCRIPTION COMPLETE"
    echo " Text: $OUTPUT_TXT"
    echo " SRT:  $OUTPUT_SRT"
    echo "=========================================="
else
    echo "ERROR: Transcription failed (exit $EXIT_CODE)"
    exit $EXIT_CODE
fi
