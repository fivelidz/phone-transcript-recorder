# SMAC Hearing Recording — Quick Reference

## ⭐ RECOMMENDED: LIVE DUAL-CHANNEL RECORDER (separated speakers + live transcript)

### OPTION 1 — Desktop Shortcut (easiest):
Double-click **"Record SMAC Hearing"** on your Desktop.
A Konsole window opens. After ~1s the whisper model loads, then:
- 🔵 **COMMITTEE** lines (in blue) — what came out of your speakers (Teams)
- 🟢 **YOU** lines (in green) — what your microphone picked up
appear LIVE on screen, ~3 seconds behind real speech.

Press **Ctrl+C** when the hearing ends — the final transcript, mixed WAV, and separated WAVs are saved.

### OPTION 2 — Terminal one-liner:
```bash
python3 ~/Documents/UTS/scripts/record_hearing_live.py
```

### What's produced (all in `~/Documents/UTS/recordings/`):
| File | Contents |
|------|----------|
| `smac_hearing_YYYYMMDD_HHMM_committee.wav` | System audio only (Committee voices) |
| `smac_hearing_YYYYMMDD_HHMM_you.wav` | Your mic only |
| `smac_hearing_YYYYMMDD_HHMM.wav` | Mixed WAV (both, for higher-quality post-hoc transcription) |
| `smac_hearing_YYYYMMDD_HHMM_transcript.txt` | Speaker-labelled transcript |
| `smac_hearing_YYYYMMDD_HHMM_transcript.srt` | Subtitle file with speaker labels |
| `smac_hearing_YYYYMMDD_HHMM_live.log` | Plain-text mirror of what scrolled on screen |

### For a higher-quality transcript afterwards (optional):
The live transcript uses 2.5s chunks with `beam_size=1` for speed. For a cleaner final pass:
```bash
bash ~/Documents/UTS/scripts/transcribe_hearing.sh ~/Documents/UTS/recordings/smac_hearing_YYYYMMDD_HHMM.wav
```
That runs the full `small.en` model with `beam_size=5` and VAD on the mixed WAV — slower but more accurate. Run it after the hearing.

---

## BACKUP OPTION: SINGLE-PASS DUAL-SOURCE (no live display, no separation)

If the live script has issues, fall back to:
```bash
bash ~/Documents/UTS/scripts/record_hearing_dual.sh
```
- Records both Committee voices (system audio) AND your voice (webcam mic) mixed into one WAV
- Press **Ctrl+C** to stop — transcript is generated automatically at the end (NOT live)

### What gets recorded:
| Source | Device |
|--------|--------|
| System audio (Committee) | `alsa_output.pci-0000_c6_00.6.analog-stereo.monitor` |
| Your microphone | `alsa_input.usb-XIFT_Web_Camera_20251020-02.mono-fallback` |

### Output files:
- **Recording:** `~/Documents/UTS/recordings/smac_hearing_YYYYMMDD_HHMM.wav`
- **Transcript:** `~/Documents/UTS/recordings/smac_hearing_YYYYMMDD_HHMM.txt` (timestamped lines)
- **SRT:**        `~/Documents/UTS/recordings/smac_hearing_YYYYMMDD_HHMM.srt` (subtitle format)

---

## BACKUP: System Audio Only (old script — no mic)

If the dual-source script has issues, fall back to this:
```bash
bash ~/Documents/UTS/scripts/record_hearing.sh
```
Then transcribe manually afterwards:
```bash
bash ~/Documents/UTS/scripts/transcribe_hearing.sh
```

---

## TECHNICAL DETAILS:
- Mix method: `ffmpeg` with `amix=inputs=2` filter (no virtual sinks needed)
- Format: 16kHz mono 16-bit PCM WAV (whisper-native)
- Transcription: faster-whisper v1.1.1, `small` model, int8/CPU, VAD filter ON
- Transcription speed: ~0.07x RTF (14x faster than real-time)

## IF AUDIO IS SILENT / MISSING:
Check PipeWire monitor source is correct:
```bash
pactl list sources short | grep monitor
pactl get-default-source
```
If source names changed, edit `SYSTEM_MONITOR=` and `MIC_SOURCE=` in `record_hearing_dual.sh`.

## ONE-LINER (copy this if the desktop shortcut fails):
```
bash ~/Documents/UTS/scripts/record_hearing_dual.sh
```
