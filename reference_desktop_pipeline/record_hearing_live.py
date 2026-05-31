#!/usr/bin/env python3
"""
record_hearing_live.py — Live dual-channel hearing recorder + transcriber.

Captures TWO separate PulseAudio sources in parallel:
  - System monitor  (Committee voices through Teams)  -> labelled COMMITTEE
  - Microphone      (your voice)                       -> labelled YOU

Each stream is:
  1. Saved to its own WAV file (lossless 16kHz mono PCM)
  2. Sliced into ~5s chunks, transcribed with faster-whisper, printed LIVE
     with colour-coded labels so you can see who said what in real time.

A merged dual WAV is also written (for archival / re-transcription) and a
labelled transcript .txt + .srt is written at the end.

Stop with Ctrl+C — final transcript is finalised cleanly.

Usage:
    python3 ~/Documents/UTS/scripts/record_hearing_live.py
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import threading
import time
import wave
from datetime import datetime
from pathlib import Path
from queue import Queue, Empty

# Force unbuffered stdout so live text appears immediately in the terminal
# (Konsole / Gnome-Terminal buffer line-by-line; this forces flush on every write)
try:
    sys.stdout.reconfigure(line_buffering=True, write_through=True)  # type: ignore[attr-defined]
except Exception:
    pass
os.environ.setdefault("PYTHONUNBUFFERED", "1")

# ---------- Configuration ----------
SYSTEM_MONITOR = "alsa_output.pci-0000_c6_00.6.analog-stereo.monitor"
MIC_SOURCE = "alsa_input.usb-XIFT_Web_Camera_20251020-02.mono-fallback"
RECORDINGS_DIR = Path.home() / "Documents" / "UTS" / "recordings"
MODEL_SIZE = "small.en"  # cached locally
SAMPLE_RATE = 16000
CHUNK_SECONDS = 2.5  # short chunks = fast live updates
CHUNK_OVERLAP_SECONDS = 0.3  # carry forward to smooth boundaries
VAD_MIN_SILENCE_MS = 300
HEARTBEAT_SECONDS = 5.0  # print a "still listening" dot if no speech

# ---------- ANSI colours ----------
RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
GREEN = "\033[92m"  # YOU
BLUE = "\033[94m"  # COMMITTEE
GREY = "\033[90m"
YELLOW = "\033[93m"
RED = "\033[91m"

# ---------- Setup ----------
RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
TIMESTAMP = datetime.now().strftime("%Y%m%d_%H%M")
BASE = RECORDINGS_DIR / f"smac_hearing_{TIMESTAMP}"

WAV_COMMITTEE = BASE.with_name(BASE.name + "_committee.wav")
WAV_YOU = BASE.with_name(BASE.name + "_you.wav")
WAV_MIXED = BASE.with_name(BASE.name + ".wav")
TRANSCRIPT_TXT = BASE.with_name(BASE.name + "_transcript.txt")
TRANSCRIPT_SRT = BASE.with_name(BASE.name + "_transcript.srt")
LIVE_LOG = BASE.with_name(BASE.name + "_live.log")

print(f"{BOLD}{'=' * 62}{RESET}")
print(f"{BOLD}  SMAC HEARING — LIVE DUAL-CHANNEL RECORDER + TRANSCRIBER{RESET}")
print(f"{BOLD}{'=' * 62}{RESET}")
print(f"  {BLUE}COMMITTEE{RESET}  : {SYSTEM_MONITOR}")
print(f"  {GREEN}YOU{RESET}        : {MIC_SOURCE}")
print(f"  Output base : {BASE}")
print(f"  Model       : faster-whisper {MODEL_SIZE} (int8, CPU)")
print(
    f"  Chunk size  : {CHUNK_SECONDS}s (live updates appear ~{CHUNK_SECONDS + 0.5:.1f}s after speech)"
)
print(f"{BOLD}{'=' * 62}{RESET}")
sys.stdout.flush()

# ---------- ffmpeg capture: each source -> its own raw s16le pipe ----------
# We use ffmpeg with two SEPARATE processes (simplest, robust). Each writes
# a 16kHz mono s16le stream to its own WAV file via a TEE filter so we get
# (a) a stdout pipe for live chunking and (b) a permanent WAV.


def spawn_capture(source: str, wav_path: Path) -> subprocess.Popen:
    """Spawn an ffmpeg that writes a permanent WAV and also pipes raw PCM to stdout."""
    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "pulse",
        "-i",
        source,
        "-map",
        "0:a",
        "-ar",
        str(SAMPLE_RATE),
        "-ac",
        "1",
        # Output 1: permanent WAV file
        "-c:a",
        "pcm_s16le",
        "-f",
        "wav",
        str(wav_path),
        # Output 2: raw PCM to stdout for live processing
        "-map",
        "0:a",
        "-ar",
        str(SAMPLE_RATE),
        "-ac",
        "1",
        "-c:a",
        "pcm_s16le",
        "-f",
        "s16le",
        "pipe:1",
    ]
    return subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )


# ---------- Load whisper FIRST (before we start capturing audio) ----------
# Loading the model BEFORE we start capture is critical — otherwise audio
# piles up in the queue while whisper initialises (~3-5s) and the first
# batch of transcripts only appears after the backlog clears, which feels
# like the script "doesn't work until you stop it".
print(f"{DIM}  [loading whisper {MODEL_SIZE}... (~3s)]{RESET}", flush=True)
from faster_whisper import WhisperModel
import numpy as np

_model_t0 = time.monotonic()
model = WhisperModel(MODEL_SIZE, device="cpu", compute_type="int8")
print(f"{DIM}  [model ready in {time.monotonic() - _model_t0:.1f}s]{RESET}", flush=True)

# Now start capture — model is hot, queue will be processed immediately
cap_committee = spawn_capture(SYSTEM_MONITOR, WAV_COMMITTEE)
cap_you = spawn_capture(MIC_SOURCE, WAV_YOU)

print(f"\n  {YELLOW}{BOLD}*** RECORDING STARTED — press Ctrl+C to stop ***{RESET}")
print(f"  {DIM}Live transcript will appear below as people speak.{RESET}\n", flush=True)

# ---------- Live transcript collector ----------
transcript_lines: list[
    tuple[float, float, str, str]
] = []  # (start, end, speaker, text)
transcript_lock = threading.Lock()
stop_event = threading.Event()
session_start = time.monotonic()

BYTES_PER_SAMPLE = 2
CHUNK_BYTES = int(SAMPLE_RATE * CHUNK_SECONDS) * BYTES_PER_SAMPLE
OVERLAP_BYTES = int(SAMPLE_RATE * CHUNK_OVERLAP_SECONDS) * BYTES_PER_SAMPLE

# Transcription queue: (speaker_label, colour, raw_pcm_bytes, chunk_start_ts)
trans_q: Queue = Queue()


def reader_thread(proc: subprocess.Popen, speaker: str, colour: str):
    """Read s16le from ffmpeg stdout, batch into CHUNK_BYTES, enqueue."""
    buf = bytearray()
    carry = b""
    stdout = proc.stdout
    assert stdout is not None, "ffmpeg stdout pipe missing"
    while not stop_event.is_set():
        try:
            data = stdout.read(4096)
        except Exception:
            break
        if not data:
            time.sleep(0.05)
            if proc.poll() is not None:
                break
            continue
        buf.extend(data)
        while len(buf) >= CHUNK_BYTES:
            chunk = carry + bytes(buf[:CHUNK_BYTES])
            del buf[:CHUNK_BYTES]
            # keep tail for overlap
            carry = chunk[-OVERLAP_BYTES:] if OVERLAP_BYTES > 0 else b""
            ts = time.monotonic() - session_start
            trans_q.put((speaker, colour, chunk, ts))


# ---------- Heartbeat: print last-activity status every few seconds ----------
last_transcribed_at = [time.monotonic()]  # mutable holder so threads can update


def heartbeat_thread():
    """Print a 'still listening' line if no transcript has come out for a while.
    Prevents the user thinking the script has frozen during silent periods."""
    last_beat = time.monotonic()
    while not stop_event.is_set():
        time.sleep(1.0)
        now = time.monotonic()
        since_text = now - last_transcribed_at[0]
        if since_text > HEARTBEAT_SECONDS and (now - last_beat) > HEARTBEAT_SECONDS:
            qsize = trans_q.qsize()
            elapsed = int(now - session_start)
            m, s = divmod(elapsed, 60)
            print(
                f"{DIM}  [{m:02d}:{s:02d}] ...listening... "
                f"(queue={qsize}, last text {int(since_text)}s ago){RESET}",
                flush=True,
            )
            last_beat = now


# ---------- Transcriber thread (single worker to avoid CPU thrash) ----------
def transcriber_thread():
    live_fh = open(LIVE_LOG, "w")
    live_fh.write(
        f"# Live hearing transcript — started {datetime.now().isoformat()}\n\n"
    )
    live_fh.flush()
    while not (stop_event.is_set() and trans_q.empty()):
        try:
            speaker, colour, pcm_bytes, ts = trans_q.get(timeout=0.5)
        except Empty:
            continue
        # Convert s16le bytes -> float32 numpy in [-1, 1]
        audio = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32) / 32768.0
        if audio.size == 0:
            continue
        # Skip near-silent chunks fast (avoid hallucinated text on silence)
        rms = float(np.sqrt(np.mean(audio * audio))) if audio.size else 0.0
        if rms < 0.003:  # ~ -50 dBFS
            continue
        try:
            segments, _info = model.transcribe(
                audio,
                language="en",
                beam_size=1,
                vad_filter=True,
                vad_parameters=dict(min_silence_duration_ms=VAD_MIN_SILENCE_MS),
                condition_on_previous_text=False,
            )
            for seg in segments:
                text = seg.text.strip()
                if not text:
                    continue
                # Filter common whisper hallucinations on near-silence
                low = text.lower()
                if low in {
                    "thank you.",
                    "thanks for watching.",
                    "thanks for watching!",
                    "you",
                    ".",
                }:
                    continue
                line_start = ts + seg.start
                line_end = ts + seg.end
                with transcript_lock:
                    transcript_lines.append((line_start, line_end, speaker, text))
                last_transcribed_at[0] = time.monotonic()
                m, s = divmod(int(line_start), 60)
                stamp = f"{m:02d}:{s:02d}"
                line = f"{DIM}[{stamp}]{RESET} {colour}{BOLD}{speaker:>9}{RESET}{colour}: {text}{RESET}"
                print(line, flush=True)
                sys.stdout.flush()
                live_fh.write(f"[{stamp}] {speaker:>9}: {text}\n")
                live_fh.flush()
        except Exception as e:
            print(f"{RED}  [transcribe error: {e}]{RESET}", flush=True)
    live_fh.close()


# Start transcriber + heartbeat FIRST, so any queued audio is processed immediately
t_trans = threading.Thread(target=transcriber_thread, daemon=True)
t_trans.start()
t_heartbeat = threading.Thread(target=heartbeat_thread, daemon=True)
t_heartbeat.start()

# Now start the reader threads (they feed the transcriber)
t_committee = threading.Thread(
    target=reader_thread, args=(cap_committee, "COMMITTEE", BLUE), daemon=True
)
t_you = threading.Thread(
    target=reader_thread, args=(cap_you, "YOU", GREEN), daemon=True
)
t_committee.start()
t_you.start()


# ---------- Signal handling ----------
def shutdown(signum=None, frame=None):
    if stop_event.is_set():
        return
    print(
        f"\n{YELLOW}  [stopping... finalising recordings and transcript]{RESET}",
        flush=True,
    )
    stop_event.set()
    for p in (cap_committee, cap_you):
        try:
            p.send_signal(signal.SIGINT)
        except Exception:
            pass
    for p in (cap_committee, cap_you):
        try:
            p.wait(timeout=5)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass


signal.signal(signal.SIGINT, shutdown)
signal.signal(signal.SIGTERM, shutdown)

# ---------- Main wait loop ----------
try:
    while not stop_event.is_set():
        time.sleep(0.5)
        if cap_committee.poll() is not None and cap_you.poll() is not None:
            break
except KeyboardInterrupt:
    shutdown()

# Wait for queue to drain
print(
    f"{DIM}  [draining transcription queue ({trans_q.qsize()} chunks)...]{RESET}",
    flush=True,
)
stop_event.set()
t_trans.join(timeout=120)

# ---------- Build mixed WAV from the two captured WAVs (post-hoc, lossless) ----------
print(f"{DIM}  [building mixed WAV...]{RESET}", flush=True)
try:
    subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(WAV_COMMITTEE),
            "-i",
            str(WAV_YOU),
            "-filter_complex",
            "[0:a][1:a]amix=inputs=2:duration=longest:dropout_transition=0[a]",
            "-map",
            "[a]",
            "-ar",
            str(SAMPLE_RATE),
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            str(WAV_MIXED),
        ],
        check=False,
        timeout=60,
    )
except Exception as e:
    print(f"{RED}  [mix build failed: {e}]{RESET}")

# ---------- Write final transcript ----------
with transcript_lock:
    lines = sorted(transcript_lines, key=lambda x: x[0])


def fmt_ts(t):
    m, s = divmod(int(t), 60)
    h, m = divmod(m, 60)
    ms = int((t % 1) * 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


with open(TRANSCRIPT_TXT, "w") as f:
    f.write(f"# SMAC Hearing — Live Transcript\n")
    f.write(f"# Recorded: {datetime.now().isoformat()}\n")
    f.write(f"# Source files:\n")
    f.write(f"#   COMMITTEE audio : {WAV_COMMITTEE.name}\n")
    f.write(f"#   YOU audio       : {WAV_YOU.name}\n")
    f.write(f"#   Mixed audio     : {WAV_MIXED.name}\n")
    f.write(
        f"# Model: faster-whisper {MODEL_SIZE} (live, chunked, may have small boundary errors)\n\n"
    )
    for start, end, speaker, text in lines:
        m, s = divmod(int(start), 60)
        f.write(f"[{m:02d}:{s:02d}] {speaker:>9}: {text}\n")

with open(TRANSCRIPT_SRT, "w") as f:
    for i, (start, end, speaker, text) in enumerate(lines, 1):
        f.write(f"{i}\n{fmt_ts(start)} --> {fmt_ts(end)}\n[{speaker}] {text}\n\n")

print()
print(f"{BOLD}{'=' * 62}{RESET}")
print(f"{BOLD}  RECORDING COMPLETE{RESET}")
print(f"{BOLD}{'=' * 62}{RESET}")
print(f"  {BLUE}COMMITTEE WAV{RESET} : {WAV_COMMITTEE}")
print(f"  {GREEN}YOU WAV{RESET}       : {WAV_YOU}")
print(f"  Mixed WAV     : {WAV_MIXED}")
print(f"  Live log      : {LIVE_LOG}")
print(f"  Transcript    : {TRANSCRIPT_TXT}")
print(f"  SRT subtitles : {TRANSCRIPT_SRT}")
print(f"{BOLD}{'=' * 62}{RESET}")
print()
print(f"{DIM}  Tip: for a higher-quality final pass, run:")
print(f'  bash ~/Documents/UTS/scripts/transcribe_hearing.sh "{WAV_MIXED}"{RESET}')
print()
