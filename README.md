# Phone Transcript Recorder

An Android app for the **Redmi Note 14 5G (HyperOS 2 / Android 14)** that records and transcribes
**calls and in-person meetings fully on-device** (offline, using whisper.cpp), labels **who spoke
when** with on-device speaker diarization, and writes the result as a **qalarc-notes-compatible
Markdown note** so transcripts appear inside the [qalarc-notes](../qalarc-notes) app automatically.

Built and verified working on **2026-05-30**. See `PROJECT_BRIEF.md` for the original spec and
`meeting_transcriber/` for the App-Store product spec (incl. the Zoom/Meet/Teams bot path).

---

## Capture modes

The app has **three on-device capture modes**, all feeding the same
transcribe → diarize → qalarc-notes pipeline:

| Mode | How to use | Capture | Speaker labels |
|---|---|---|---|
| **In-person meeting** | "Record in-person meeting" button | Live mic → 16 kHz WAV | ✅ diarization |
| **Phone call (live)** | "Record call (speakerphone)" button | Live mic, **speaker on** | ✅ diarization |
| **Phone call (auto)** | "Start watching" — watches the OEM call-rec folder | OEM recorder file | ✅ diarization |

A fourth mode — **online meetings (Zoom/Meet/Teams)** with perfect per-participant separation —
is specced in `meeting_transcriber/MODEL_B_BOT_JOINER_SPEC.md` (requires a server + Recall.ai).

```
 ┌ In-person mic ─┐
 ┌ Call (speaker) ┤──▶ 16 kHz mono ──▶ whisper.cpp ──▶ text segments ─┐
 └ OEM call file ─┘     float buffer    (ggml-base.en)                 │
                              │                                        ▼
                              └──────▶ sherpa-onnx diarization ──▶ Speaker N per segment
                                       (pyannote-seg + TitaNet)         │
                                                                        ▼
                                            qalarc-notes .md  (frontmatter + [mm:ss] **Speaker N:**)
```

1. **Capture** — for calls, the app does **not** tap the call audio APIs (blocked since Android
   10 — see "Why can't we just record the call?" below). Live modes record the **microphone**
   (acoustically, with the call on **speakerphone**); the auto mode reads HyperOS's native
   call-recorder file. In-person mode just records the mic.
2. **Transcribe** — a foreground service decodes/records to 16 kHz mono float and runs
   **whisper.cpp** (`ggml-base.en`) on-device, in 120 s chunks. No audio ever leaves the phone.
3. **Diarize** — **sherpa-onnx** (pyannote-segmentation-3.0 + NeMo TitaNet-small, ~8 MB of ONNX
   models, fully offline on ARM) splits the audio into speaker turns. Each whisper text segment
   is assigned to the speaker whose turn overlaps it most → **Speaker 1 / Speaker 2 …** labels.
4. **Write to qalarc-notes** — formatted as a qalarc-notes Markdown note, written to
   `Documents/qalarc-notes/notes/<date>_<type>-<slug>.md` (durable mirror) **and** fired via the
   `com.qalarc.notes.CREATE_NOTE` intent for live injection.

### Note format produced

```markdown
---
title: "Call with Unknown — 2026-05-30 11:48"
created: 2026-05-30T11:48:33
updated: 2026-05-30T11:52:25
folder: ai_inbox
type: log
status: open
author: ai
mood: 📞
tags: [call, transcript, unknown]
source: voice
speakers: 2
---

# Call with Unknown — 2026-05-30 11:48

**Duration:** 20s
**Speakers detected:** 2

---

[00:00] **Speaker 1:** Hi, thanks for taking my call today. I wanted to discuss the project timeline.
[00:05] **Speaker 2:** Of course, happy to help. What part of the timeline did you want to go over?
[00:11] **Speaker 1:** Mainly the delivery date. Can we still make the end of the month?
[00:15] **Speaker 2:** I think so, but we might need an extra developer to be safe.
```

*(This is real verified output from the device — diarization correctly separated the two voices.)*
Meetings use `🎙️` / `type: meeting` and a meeting title instead of a contact. The format matches
qalarc-notes `STORAGE.md §5` exactly, so transcripts are indistinguishable from any other note and
can also be re-imported via qalarc-notes' "Import .md files" feature.

---

## Install

```bash
./scripts/install.sh                 # builds, installs, pushes model, grants perms
# or with a different model:
./scripts/install.sh ggml-small.en.bin
```

Then on the phone, open **Transcriber** and:
1. Tap **Grant permissions** (RECORD_AUDIO, READ_PHONE_STATE, etc.).
2. Tap **Grant all-files access** — *required* so the app can watch the call-rec folder
   (which is owned by the `media_rw` group; scoped storage can't see it).
3. Tap **Start watching call recordings**.

The status panel shows permission state, whether the model is found, and which call-rec
folders exist.

### Enabling native call recording on the phone

The MediaTek call-recorder engine (`com.mediatek.callrecorder`) ships **disabled** in the AU
region. The install script enables it (`pm enable com.mediatek.callrecorder`). You then turn
on call recording in **Phone app → Settings → Call recording** (or it records automatically
once enabled). Recordings appear in `/sdcard/MIUI/sound_recorder/call_rec/`.

> **Legal (NSW, Australia):** as a principal party to a private conversation you may record it
> for personal use without the other party's consent (*Surveillance Devices Act 2007* (NSW)),
> provided you don't publish it to non-parties. This app is for personal transcripts only and
> does not upload anything.

---

## The model

The whisper model is **not** bundled in the APK (it's ~142 MB). The app looks for it, in order:

1. `/data/data/<pkg>/files/models/ggml-base.en.bin`
2. `/sdcard/Android/data/<pkg>/files/models/ggml-base.en.bin`
3. `/sdcard/Download/ggml-base.en.bin`  ← where `install.sh` pushes it

Models live on the desktop at `~/projects/MASTER_PROJECTS/whisper-cpp/models/`:
- `ggml-tiny.en.bin` (75 MB) — fastest, least accurate
- `ggml-base.en.bin` (142 MB) — **default**, good balance
- `ggml-small.en.bin` (466 MB) — best accuracy, slower on phone

To switch model, push it to `/sdcard/Download/` and change `model_name` in the app's prefs
(or edit `Settings.modelName`).

---

## Manual / one-off transcription

In the app, tap **Transcribe an audio file…** to pick any audio file (e.g. an old recording)
and transcribe it into a qalarc note. Useful for testing or backfilling.

---

## Architecture / source layout

```
android/
├── app/                         the app (Kotlin)
│   └── src/main/
│       ├── java/com/fivelidz/transcriber/
│       │   ├── MainActivity.kt          control panel UI (3 capture modes) + permissions
│       │   ├── RecorderService.kt       LIVE mic recorder (meeting + call/speakerphone),
│       │   │                            speakerphone detection, silence warnings
│       │   ├── WavWriter.kt             streams 16 kHz mono PCM → .wav
│       │   ├── TranscriberService.kt    foreground service, FileObserver watcher (auto mode)
│       │   ├── TranscriptionPipeline.kt decode → whisper → diarize → note (chunked)
│       │   ├── Diarizer.kt              sherpa-onnx speaker diarization wrapper
│       │   ├── AudioDecoder.kt          any audio → 16 kHz mono float
│       │   ├── QalarcNotesWriter.kt     transcript (+ speaker labels) → qalarc-notes .md
│       │   ├── ModelManager.kt          locates the ggml model
│       │   ├── Settings.kt              prefs (diarization toggle, watched folders)
│       │   └── BootReceiver.kt          restart watcher after reboot
│       ├── java/com/k2fsa/sherpa/onnx/  sherpa-onnx Kotlin API (vendored, diarization)
│       ├── jniLibs/{arm64-v8a,armeabi-v7a}/  sherpa-onnx + onnxruntime prebuilt .so
│       └── assets/diarization/          segmentation.onnx + embedding.onnx (~41 MB)
├── whisperlib/                  reusable whisper.cpp JNI module (com.whispercpp.whisper)
│   └── src/main/
│       ├── java/.../LibWhisper.kt     WhisperContext API + transcribeSegments()
│       └── jni/whisper/{jni.c,CMakeLists.txt}
└── whisper-cpp-src/             vendored whisper.cpp 1.8.3 source (self-contained)
```

The diarization stack is **sherpa-onnx** (k2-fsa) with prebuilt ARM `.so` files (no NDK build
needed) + two small ONNX models:
- `segmentation.onnx` — pyannote-segmentation-3.0 int8 (~1.5 MB) — speaker-turn boundaries
- `embedding.onnx` — NeMo TitaNet-small (~39 MB) — speaker voiceprints
Auto-detects the number of speakers (clustering threshold 0.5). Runs fully offline; RTF well
under 1 (a 20 s clip diarizes in seconds).

### Build notes

- **NDK:** must be `28.2.13676358` — the other NDK installs on this machine are incomplete
  (missing `clang`/`llvm-strip`). Both `app` and `whisperlib` pin this in their `ndkVersion`.
- **AGP 8.2.2 / Gradle 8.4 / Kotlin 1.9.22 / JDK 17** (matches the qalarc-notes android project).
- ABIs built: `arm64-v8a` (the phone uses `libwhisper_v8fp16_va.so`, fp16-accelerated) and
  `armeabi-v7a`.

---

## Why can't we just record the call directly? (and the speakerphone requirement)

Android **blocks third-party apps from tapping call audio** — it's architectural, not just
policy:
- `VOICE_CALL` / `VOICE_UPLINK` / `VOICE_DOWNLINK` audio sources require the
  `CAPTURE_AUDIO_OUTPUT` permission, which is `signature|privileged` — **only OEM/system apps**
  (like Samsung's or Pixel's built-in recorders) can hold it. No Play Store app ever can.
- The `AudioPlaybackCapture` API (screen-recorder loopback) **hard-excludes** voice-call audio
  (`USAGE_VOICE_COMMUNICATION`) at the framework mixer level.
- The Accessibility-API call-recording trick was **banned by Google Play in May 2022**.

So the only legal route for a live call is the **acoustic one**: put the call on **speakerphone**
and record the **microphone** — the mic hears both your voice and (acoustically) the remote party
from the loudspeaker. That's why the "Record call" mode requires speakerphone, detects the audio
route (`getCommunicationDevice()` / `isSpeakerphoneOn()`), and warns you in the notification if
the speaker is off or the recording is silent. (The **auto mode** sidesteps this by reading
HyperOS's own native call-recorder file, which is the highest quality when available.)

For **perfect** per-speaker separation on **online meetings**, see
`meeting_transcriber/MODEL_B_BOT_JOINER_SPEC.md` — a bot joins the Zoom/Meet/Teams call and gets
each participant on a separate stream (server-side; the industry-standard Recall.ai approach).

---

## Known limitations

- **Speakerphone required for live calls.** Android won't let apps tap call audio; live call
  recording is acoustic, so the call must be on speakerphone (the app guides + warns you).
  Quality depends on room acoustics. The **auto/OEM-file mode** has no such limitation.
- **Diarization quality.** On-device diarization is very good for 2–4 alternating speakers but
  can struggle with heavy overlap or very similar voices. Auto-detects speaker count; if it's
  off, the clustering threshold can be tuned (`Diarizer.kt`, default 0.5).
- **Speed.** The `base` model transcribes roughly real-time-ish on this phone's CPU; diarization
  adds only seconds. Use `tiny` for speed or `small` for accuracy.
- **Live note injection.** Android 14 BAL rules can block the `CREATE_NOTE` intent when the app
  is backgrounded. The **durable `.md` mirror always succeeds**, so no transcript is ever lost.
- **APK size.** The debug APK bundles the diarization ONNX models + onnxruntime (~108 MB). For a
  store build these (and the whisper model) should be downloaded on first run instead of bundled.

---

## Verified end-to-end (2026-05-30)

All three on-device modes verified working on the device:
- **Diarization:** a 2-speaker test clip in `call_rec/` → transcript correctly labelled
  **Speaker 1 / Speaker 2** on every turn, `speakers: 2` in frontmatter. ✅
- **Live meeting recording:** "Record in-person meeting" → mic → 16 kHz WAV → whisper →
  `Documents/qalarc-notes/notes/..._meeting-meeting.md` with `type: meeting`, `🎙️`. ✅
- **Call auto-transcribe:** OEM recorder file → transcript with correct number parsing + speaker
  labels. ✅

whisper runs via `libwhisper_v8fp16_va.so` (fp16-accelerated, 2 threads); diarization via
sherpa-onnx (`libsherpa-onnx-jni.so` + `libonnxruntime.so`).
