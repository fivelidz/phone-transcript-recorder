#!/usr/bin/env python3
"""
record_hearing_bt.py — LIVE hearing recorder targeting the Bluetooth headset.

This is a parallel script to record_hearing_live.py. It does NOT interfere
with any running recording.

Captures:
  - COMMITTEE: bluez_output.FA_00_11_12_16_B1.1.monitor  (BT headset output)
  - YOU      : alsa_input.usb-XIFT_Web_Camera_20251020-02.mono-fallback

Outputs to: ~/Documents/UTS/recordings/bt_hearing_YYYYMMDD_HHMMSS_*
(distinct prefix so it never collides with the other script's files)
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path
from queue import Queue, Empty

# Unbuffered stdout
try:
    sys.stdout.reconfigure(line_buffering=True, write_through=True)  # type: ignore[attr-defined]
except Exception:
    pass

# ---------- Configuration ----------
COMMITTEE_SOURCE = "bluez_output.FA_00_11_12_16_B1.1.monitor"
MIC_SOURCE = "alsa_input.usb-XIFT_Web_Camera_20251020-02.mono-fallback"
RECORDINGS_DIR = Path.home() / "Documents" / "UTS" / "recordings"
MODEL_SIZE = "small.en"
SAMPLE_RATE = 16000
CHUNK_SECONDS = 2.5
CHUNK_OVERLAP_SECONDS = 0.3
VAD_MIN_SILENCE_MS = 300
HEARTBEAT_SECONDS = 5.0

# ---------- ANSI ----------
RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
GREEN = "\033[92m"
BLUE = "\033[94m"
YELLOW = "\033[93m"
RED = "\033[91m"

# ---------- Setup ----------
RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
TIMESTAMP = datetime.now().strftime("%Y%m%d_%H%M%S")
BASE = RECORDINGS_DIR / f"bt_hearing_{TIMESTAMP}"
WAV_COMMITTEE = Path(str(BASE) + "_committee.wav")
WAV_YOU = Path(str(BASE) + "_you.wav")
WAV_MIXED = Path(str(BASE) + ".wav")
TRANSCRIPT_TXT = Path(str(BASE) + "_transcript.txt")
TRANSCRIPT_SRT = Path(str(BASE) + "_transcript.srt")
LIVE_LOG = Path(str(BASE) + "_live.log")

print(f"{BOLD}{'=' * 62}{RESET}")
print(f"{BOLD}  SMAC HEARING — BLUETOOTH LIVE RECORDER{RESET}")
print(f"{BOLD}{'=' * 62}{RESET}")
print(f"  {BLUE}COMMITTEE{RESET} : {COMMITTEE_SOURCE}")
print(f"  {GREEN}YOU{RESET}       : {MIC_SOURCE}")
print(f"  Output    : {BASE}")
print(f"{BOLD}{'=' * 62}{RESET}")
sys.stdout.flush()

# ---------- Load whisper FIRST ----------
print(f"{DIM}  [loading whisper {MODEL_SIZE}...]{RESET}", flush=True)
from faster_whisper import WhisperModel
import numpy as np

_t0 = time.monotonic()
model = WhisperModel(MODEL_SIZE, device="cpu", compute_type="int8")
print(f"{DIM}  [model ready in {time.monotonic() - _t0:.1f}s]{RESET}", flush=True)


# ---------- ffmpeg capture ----------
def spawn(source: str, wav: Path) -> subprocess.Popen:
    return subprocess.Popen(
        [
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
            "-c:a",
            "pcm_s16le",
            "-f",
            "wav",
            str(wav),
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
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )


cap_c = spawn(COMMITTEE_SOURCE, WAV_COMMITTEE)
cap_y = spawn(MIC_SOURCE, WAV_YOU)

# Give ffmpeg 1s to start and detect failures
time.sleep(1.0)
for name, p in [("COMMITTEE", cap_c), ("YOU", cap_y)]:
    if p.poll() is not None:
        err = p.stderr.read().decode() if p.stderr else ""
        print(f"{RED}  [{name} capture FAILED to start: {err}]{RESET}", flush=True)
        sys.exit(1)

print(f"\n  {YELLOW}{BOLD}*** RECORDING — Ctrl+C to stop ***{RESET}")
print(f"  {DIM}First text appears ~3s after speech.{RESET}\n", flush=True)

# ---------- Shared state ----------
transcript_lines: list[tuple[float, float, str, str]] = []
lock = threading.Lock()
stop_event = threading.Event()
t_start = time.monotonic()
last_text_at = [time.monotonic()]

BPS = 2
CHUNK_BYTES = int(SAMPLE_RATE * CHUNK_SECONDS) * BPS
OVERLAP_BYTES = int(SAMPLE_RATE * CHUNK_OVERLAP_SECONDS) * BPS
q: Queue = Queue()


def reader(proc, speaker, colour):
    buf = bytearray()
    carry = b""
    stdout = proc.stdout
    assert stdout is not None
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
            carry = chunk[-OVERLAP_BYTES:] if OVERLAP_BYTES else b""
            ts = time.monotonic() - t_start
            q.put((speaker, colour, chunk, ts))


def heartbeat():
    last = time.monotonic()
    while not stop_event.is_set():
        time.sleep(1.0)
        now = time.monotonic()
        if now - last_text_at[0] > HEARTBEAT_SECONDS and now - last > HEARTBEAT_SECONDS:
            qs = q.qsize()
            el = int(now - t_start)
            m, s = divmod(el, 60)
            print(
                f"{DIM}  [{m:02d}:{s:02d}] ...listening... (queue={qs}){RESET}",
                flush=True,
            )
            last = now


def transcriber():
    live_fh = open(LIVE_LOG, "w")
    live_fh.write(f"# Started {datetime.now().isoformat()}\n\n")
    live_fh.flush()
    while not (stop_event.is_set() and q.empty()):
        try:
            speaker, colour, pcm, ts = q.get(timeout=0.5)
        except Empty:
            continue
        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        if audio.size == 0:
            continue
        rms = float(np.sqrt(np.mean(audio * audio)))
        if rms < 0.003:
            continue
        try:
            segs, _ = model.transcribe(
                audio,
                language="en",
                beam_size=1,
                vad_filter=True,
                vad_parameters=dict(min_silence_duration_ms=VAD_MIN_SILENCE_MS),
                condition_on_previous_text=False,
            )
            for seg in segs:
                text = seg.text.strip()
                if not text:
                    continue
                low = text.lower()
                if low in {
                    "thank you.",
                    "thanks for watching.",
                    "thanks for watching!",
                    "you",
                    ".",
                    "okay.",
                    "bye.",
                }:
                    continue
                s_t = ts + seg.start
                e_t = ts + seg.end
                with lock:
                    transcript_lines.append((s_t, e_t, speaker, text))
                last_text_at[0] = time.monotonic()
                m, s = divmod(int(s_t), 60)
                stamp = f"{m:02d}:{s:02d}"
                print(
                    f"{DIM}[{stamp}]{RESET} {colour}{BOLD}{speaker:>9}{RESET}{colour}: {text}{RESET}",
                    flush=True,
                )
                live_fh.write(f"[{stamp}] {speaker:>9}: {text}\n")
                live_fh.flush()
        except Exception as e:
            print(f"{RED}  [transcribe error: {e}]{RESET}", flush=True)
    live_fh.close()


# Start transcriber + heartbeat FIRST
threading.Thread(target=transcriber, daemon=True).start()
threading.Thread(target=heartbeat, daemon=True).start()
threading.Thread(target=reader, args=(cap_c, "COMMITTEE", BLUE), daemon=True).start()
threading.Thread(target=reader, args=(cap_y, "YOU", GREEN), daemon=True).start()


def shutdown(*_):
    if stop_event.is_set():
        return
    print(f"\n{YELLOW}  [stopping...]{RESET}", flush=True)
    stop_event.set()
    for p in (cap_c, cap_y):
        try:
            p.send_signal(signal.SIGINT)
        except Exception:
            pass
    for p in (cap_c, cap_y):
        try:
            p.wait(timeout=5)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass


signal.signal(signal.SIGINT, shutdown)
signal.signal(signal.SIGTERM, shutdown)

try:
    while not stop_event.is_set():
        time.sleep(0.5)
        if cap_c.poll() is not None and cap_y.poll() is not None:
            break
except KeyboardInterrupt:
    shutdown()

print(f"{DIM}  [draining queue ({q.qsize()})...]{RESET}", flush=True)
stop_event.set()
time.sleep(3)  # let transcriber drain

# Mix
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
        timeout=60,
    )
except Exception as e:
    print(f"{RED}  [mix failed: {e}]{RESET}")

with lock:
    lines = sorted(transcript_lines, key=lambda x: x[0])


def fmt(t):
    m, s = divmod(int(t), 60)
    h, m = divmod(m, 60)
    return f"{h:02d}:{m:02d}:{s:02d},{int((t % 1) * 1000):03d}"


with open(TRANSCRIPT_TXT, "w") as f:
    f.write(f"# BT Hearing Transcript — {datetime.now().isoformat()}\n\n")
    for s_t, _, spk, txt in lines:
        m, s = divmod(int(s_t), 60)
        f.write(f"[{m:02d}:{s:02d}] {spk:>9}: {txt}\n")

with open(TRANSCRIPT_SRT, "w") as f:
    for i, (s_t, e_t, spk, txt) in enumerate(lines, 1):
        f.write(f"{i}\n{fmt(s_t)} --> {fmt(e_t)}\n[{spk}] {txt}\n\n")

print(f"\n{BOLD}{'=' * 62}{RESET}")
print(f"{BOLD}  DONE{RESET}")
print(f"  Committee : {WAV_COMMITTEE}")
print(f"  You       : {WAV_YOU}")
print(f"  Mixed     : {WAV_MIXED}")
print(f"  Transcript: {TRANSCRIPT_TXT}")
print(f"{BOLD}{'=' * 62}{RESET}")
