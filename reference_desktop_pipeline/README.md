# Reference: Desktop Recording + Transcription Pipeline

These are the **working, tested** desktop scripts that inspired the phone transcript
recorder project. They were built (and used in anger) on 28 May 2026 to record and
transcribe a UTS misconduct appeal hearing held over Microsoft Teams.

**Copied here on 2026-05-30 as a reference implementation.** The originals live at
`~/Documents/UTS/scripts/` — these are copies, do not treat them as the live versions.

The phone app is conceptually the *same pipeline* — `capture audio → 16 kHz mono WAV →
Whisper → text` — just with a different capture source (a phone call instead of desktop
PulseAudio) and a different output target (qalarc-notes `.md` instead of a loose `.txt`).

Study these before building. They prove the core ideas work and contain solved problems
you'll hit again on the phone (chunking, VAD, speaker separation, clean Ctrl+C handling,
SRT generation, faster-whisper invocation).

---

## What each file does

### `transcribe_hearing.sh` ⭐ THE CORE TRANSCRIBER
The reference transcription step. Takes a WAV, runs **faster-whisper `small`** (int8, CPU),
and writes both a timestamped `.txt` and an `.srt`.

Key parameters worth copying to the phone:
- `WhisperModel("small", device="cpu", compute_type="int8")` — small model, int8 quantised,
  fast and accurate enough for speech. On phone, the equivalent is whisper.cpp with
  `ggml-small.en.bin` (or `ggml-base.en.bin` if the phone is slow).
- `vad_filter=True, vad_parameters=dict(min_silence_duration_ms=500)` — voice-activity
  detection skips silence (so silent gaps don't produce garbage segments). Important for
  phone calls with hold music / pauses.
- `beam_size=5` — good accuracy/speed tradeoff.
- Performance observed: **RTF ≈ 0.07** on desktop CPU (a 60-min recording transcribes in
  ~4 min). The phone will be slower — budget for that, or use Path B (desktop transcribes).
- Output format: `[12.3s - 15.8s] text...` per segment, plus standard SRT. The phone's
  qalarc-notes writer should reformat these as `[mm:ss] Speaker: text`.

### `record_hearing_live.py` ⭐ DUAL-CHANNEL + SPEAKER LABELS + LIVE
The most sophisticated reference. Captures **two separate sources in parallel**:
- System monitor (the remote parties — "COMMITTEE")
- Microphone (the local user — "YOU")

…and transcribes each in ~2.5 s chunks **live**, colour-coded by speaker, while also saving
per-channel WAVs and a merged WAV.

**Why this matters for the phone:** speaker separation is the hard part of call transcripts.
On the desktop it's trivial because the two parties arrive on two different audio sources
(system vs mic). On a phone call, if you capture from the native call recorder you usually
get a single mixed stream — so you lose easy speaker separation and would need diarisation.
BUT if you capture mic + call-downlink as two streams (where the device allows it), you can
reuse this exact two-source-labelled approach. This file is the blueprint for that.

Solved problems in here to copy:
- Threaded parallel capture of two sources without blocking.
- Chunked streaming transcription with overlap (`CHUNK_OVERLAP_SECONDS`) to smooth segment
  boundaries.
- Clean `Ctrl+C` finalisation (writes the final transcript even on interrupt).
- Unbuffered stdout for live terminal output.
- Heartbeat ("still listening") so the user knows it's alive during silence.

### `record_hearing_dual.sh` — SIMPLE DUAL-SOURCE CAPTURE (ffmpeg)
The simplest way to capture system audio + mic mixed into one WAV, using a single ffmpeg
command with `amix`. Less sophisticated than the live Python version (no speaker labels,
no live transcription — it records then transcribes at the end) but rock-solid and minimal.

The ffmpeg pattern is the key takeaway:
```
ffmpeg -f pulse -i <system.monitor> -f pulse -i <mic> \
  -filter_complex "[0:a][1:a]amix=inputs=2:duration=longest[a]" \
  -map "[a]" -ar 16000 -ac 1 -c:a pcm_s16le out.wav
```
On Android, the equivalent mixing can be done with the bundled ffmpeg or `MediaCodec`/
`AudioRecord` + manual PCM mixing.

### `record_hearing.sh` — SINGLE-SOURCE CAPTURE (parecord)
The most minimal reference — captures ONE PulseAudio source (system monitor) to a WAV via
`parecord`. Useful as the absolute baseline. The phone equivalent is recording a single
mixed call stream (e.g. from the HyperOS native call recorder folder).

### `record_hearing_bt.py` — BLUETOOTH-AWARE CAPTURE
A variant that handles Bluetooth audio routing (e.g. when the call audio is on a BT headset).
Relevant for the phone because many calls happen on BT earbuds/headsets, and the audio
routing changes which source has the audio. Study this if BT call capture is in scope.

### `HEARING_RECORDING_README.md` — the original usage docs
The user-facing instructions for the desktop scripts. Shows the intended UX:
"run this before the meeting, Ctrl+C after, transcript appears automatically."

---

## How this maps to the phone project

| Desktop (these scripts) | Phone equivalent |
|---|---|
| PulseAudio system monitor source | HyperOS native call-rec folder OR call downlink stream |
| PulseAudio mic source | Phone microphone (`AudioRecord` MIC source) |
| ffmpeg `amix` / parallel WAV capture | `MediaCodec` mixing OR two-stream `AudioRecord` |
| faster-whisper `small` (Python, CPU) | whisper.cpp `ggml-small.en` (NDK ARM build) on-device, OR ship audio to desktop and reuse `transcribe_hearing.sh` verbatim (Path B) |
| `.txt` + `.srt` output | qalarc-notes `.md` with YAML frontmatter (see PROJECT_BRIEF.md §5) |
| Runs on `superlocal` desktop | Runs on Redmi Note 14 5G (HyperOS — see AGENT_GOTCHAS.md) |

**Fastest v1 path (Path B):** phone records the call audio → file lands on desktop (Syncthing
/ adb pull / shared folder) → desktop runs `transcribe_hearing.sh` **unchanged** → a small
wrapper rewrites the `.txt` into qalarc-notes `.md` format. This reuses 100% of the proven
transcription code and only requires building the phone-side capture + the `.md` formatter.

---

## Environment facts (from the working desktop setup)

- **faster-whisper** v1.1.1 installed; `small` model cached at `~/.cache/huggingface/hub/`.
- **whisper.cpp** desktop (HIP/GPU) build at
  `~/projects/MASTER_PROJECTS/whisper-cpp/build-hip/bin/whisper-cli` with `ggml-small.en.bin`
  — same source cross-compiles for Android via the NDK.
- Audio target format throughout: **16 kHz, mono, 16-bit PCM** (whisper's native input).
- Desktop PulseAudio sources used:
  - system: `alsa_output.pci-0000_c6_00.6.analog-stereo.monitor`
  - mic: `alsa_input.usb-XIFT_Web_Camera_20251020-02.mono-fallback`
  (These are desktop-specific; the phone has its own sources.)

---

See `../PROJECT_BRIEF.md` for the full phone-app specification.
