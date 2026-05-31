# Phone Transcript Recorder — Project Brief

**Status:** Specification / not yet built
**Created:** 2026-05-30
**Author of brief:** Claude (for fivelidz), to be picked up by a separate build agent
**Target device:** Redmi Note 14 5G (HyperOS 2) — the user's primary phone
**Related projects:**
- `~/Documents/UTS/scripts/` — the desktop call-recording + faster-whisper pipeline that inspired this (working, tested)
- `~/projects/phone_projects/qalarc-notes/` — the notes app that should ingest the transcripts
- `~/projects/phone_projects/AGENT_GOTCHAS.md` — **READ FIRST** before any phone install work

---

## 1. One-paragraph description

A phone app that records phone calls (and optionally any audio), transcribes them to text using a Whisper-based pipeline, and writes the resulting transcript out as a qalarc-notes-compatible Markdown file so the transcript automatically appears inside the **qalarc-notes** application. The user already has a working desktop equivalent (ffmpeg capture → faster-whisper → `.txt`/`.srt`); this project ports that idea to the phone and wires the output into the existing qalarc-notes ecosystem.

---

## 2. Why this exists / motivation

- The user just built a desktop call-transcription pipeline for a UTS hearing (faster-whisper `small` model, ffmpeg dual-source audio capture). It worked well.
- The user wants the same capability on their phone for **phone calls** — record a call, get a clean searchable transcript afterwards.
- The user wants transcripts to flow into **qalarc-notes** so all their notes (typed, voice, drawings, photos, and now call transcripts) live in one searchable, AI-readable place.
- This fits the broader Qalarc/RFAI personal-knowledge-system theme the user is building.

---

## 3. Hard constraints & gotchas (READ BEFORE BUILDING)

### 3.1 Android call-recording is heavily restricted (THE BIG ONE)

Since **Android 10**, Google blocked third-party apps from capturing the *remote party's* audio on a phone call via the normal `MediaRecorder` / `AudioRecord` APIs. This is the single biggest technical risk in this project. The build agent MUST investigate which of the following capture strategies actually works on **this specific Redmi Note 14 5G / HyperOS 2** device before committing to an architecture:

| Strategy | Captures remote party? | Works on stock Android 10+? | Notes |
|---|---|---|---|
| `MediaRecorder` with `VOICE_CALL` / `VOICE_COMMUNICATION` source | Sometimes | Blocked on most stock Android 10+ | Worth testing — some Xiaomi/MIUI builds still allow `VOICE_CALL` |
| `MediaRecorder` with `MIC` source on speakerphone | Yes (acoustically) | ✅ Always works | **Most reliable fallback.** Records the mic while on speakerphone — picks up both sides acoustically. Lower quality but universal. |
| `AccessibilityService` + system audio | Partial | Complex | Not recommended for v1 |
| MIUI/HyperOS **built-in call recorder** + read its output files | Yes | ✅ On supported regions | HyperOS has a native call recorder that writes `.mp3`/`.m4a` to `/sdcard/MIUI/sound_recorder/call_rec/` or similar. **Best quality option if available.** The app could simply watch that folder and transcribe new files. |
| Root + `audioflinger` tap | Yes | Needs root | Out of scope unless phone is rooted |

**Recommended approach for v1 (in priority order):**
1. **Folder-watch the HyperOS native call recorder output.** If HyperOS's built-in call recording can be enabled, the cleanest design is: app watches the call-recording folder, and whenever a new audio file lands, it transcribes it and writes a qalarc-notes entry. This sidesteps the Android API restrictions entirely.
2. **Speakerphone + MIC capture fallback.** If native call recording is unavailable, record from the `MIC` source while the user is on speakerphone. Document this limitation for the user.

The build agent should **test option 1 first** — check whether `/sdcard/MIUI/sound_recorder/call_rec/` (or the HyperOS equivalent) exists and populates after a test call.

### 3.2 Installing on the Redmi Note 14 5G

- **DO NOT use plain `adb install`** — HyperOS auto-denies it. Use:
  `~/projects/phone_projects/camera_system/install_with_miui_dialog.sh path/to.apk`
- Full details: `~/projects/phone_projects/AGENT_GOTCHAS.md` (read sections 1–3 minimum).
- Pre-grant runtime perms via `adb shell pm grant`:
  - `android.permission.RECORD_AUDIO`
  - `android.permission.READ_MEDIA_AUDIO` (Android 13+) or `READ_EXTERNAL_STORAGE`
  - `android.permission.WRITE_EXTERNAL_STORAGE` (scoped storage caveats apply)
  - `android.permission.FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE`
  - For call-state detection: `READ_PHONE_STATE`

### 3.3 Legal note (one-party consent)

The user is in **NSW, Australia**. Under the *Surveillance Devices Act 2007* (NSW), a principal party to a private conversation may record it for personal use without the other party's consent, provided it is not published to non-parties. This app is for the user's **personal** transcripts. The app should include a settings note reminding the user that distributing recordings/transcripts of others without consent may have legal implications. Do not build any auto-share / cloud-upload feature in v1.

---

## 4. Architecture (recommended)

Two viable build paths. The build agent should pick based on what the device supports (see §3.1).

### Path A — On-device transcription (preferred, fully offline)

```
┌─────────────────────────────────────────────────────────────┐
│  Phone Transcript Recorder (Android app)                      │
│                                                              │
│  [1] Call detector (READ_PHONE_STATE) OR folder watcher      │
│        │                                                     │
│        ▼                                                     │
│  [2] Audio source:                                           │
│        • HyperOS call-rec folder watch (best), OR            │
│        • MIC capture on speakerphone (fallback)              │
│        │                                                     │
│        ▼                                                     │
│  [3] whisper.cpp (on-device, ARM build) with ggml-small.en   │
│        │   → runs locally, no network                        │
│        ▼                                                     │
│  [4] Transcript writer → qalarc-notes .md format             │
│        │                                                     │
│        ▼                                                     │
│  [5] Drop .md into qalarc-notes storage location             │
└─────────────────────────────────────────────────────────────┘
```

- **whisper.cpp has an Android/ARM build.** The user already has whisper.cpp at
  `~/projects/MASTER_PROJECTS/whisper-cpp/build-hip/bin/whisper-cli` (that's the HIP/GPU desktop build — the phone needs a separate ARM/NEON build, but the same source compiles for Android via NDK).
- Model: `ggml-small.en.bin` (~466 MB) is a good size/accuracy tradeoff for phone. `ggml-base.en.bin` (~142 MB) is faster/smaller if the phone struggles.
- This path is **fully offline** — no audio leaves the phone. Best for privacy.

### Path B — Phone records, desktop transcribes (simpler v1, needs the desktop)

```
Phone records call audio  →  file synced to desktop (Syncthing / adb pull / shared folder)
                          →  desktop runs faster-whisper (the existing UTS pipeline)
                          →  desktop writes qalarc-notes .md
```

- Reuses the **already-working** desktop pipeline at `~/Documents/UTS/scripts/transcribe_hearing.sh`.
- Faster to ship a v1. Downside: requires the desktop (`superlocal`) to be on and reachable.
- Good as a **stepping stone** — ship Path B first, then move transcription on-device (Path A) once whisper.cpp is building on the phone.

**Recommendation: build Path B first (1–2 hours to working), then upgrade to Path A.**

---

## 5. qalarc-notes integration (the important bit)

The whole point is that transcripts show up in qalarc-notes. qalarc-notes reads
YAML-frontmatter Markdown files. The transcript writer must produce **exactly** this format.

### 5.1 Transcript note format

```yaml
---
title: "Call with <contact name or number> — 2026-05-30 14:32"
created: 2026-05-30T14:32:06
updated: 2026-05-30T14:55:40
folder: notes
type: log
status: open
author: ai
mood: 📞
tags: [call, transcript, <contact-slug>]
source: voice
media: [media_<audio_id>]
---

# Call with <contact> — 2026-05-30 14:32

**Duration:** 23m 34s
**Direction:** incoming / outgoing
**Number:** +61 4xx xxx xxx

---

[00:00] Speaker A: ...
[00:14] Speaker B: ...
[00:31] Speaker A: ...
...
```

Notes on fields:
- `source: voice` — this is the existing qalarc-notes value for voice-derived notes. **Reuse it.**
- `type: log` — transcripts are best filed as `log` (or add a new type `transcript` if the user wants; check qalarc-notes `index.html` for the allowed type list before adding).
- `folder: notes` — or consider a dedicated `call_log` folder (check what folders qalarc-notes supports first; current list: journal, notes, ai_inbox, ideas, daily_log, drawings, photos, bugs, test_plans, projects).
- `author: ai` — the transcript was machine-generated.
- `media: [media_<audio_id>]` — optionally attach the source audio as a media object so the user can replay it. qalarc-notes stores media at `<files>/media/<id>.{png,jpg,m4a}` — `.m4a` audio is already supported per STORAGE.md.
- Timestamps `[mm:ss]` in the body come straight from the whisper `.srt`/segment output.

### 5.2 Where to write the file

**On phone (Path A):** Write into the qalarc-notes app storage. qalarc-notes uses
IndexedDB + on-disk `.md` when running as an APK. The cleanest integration is one of:
  - (a) Write `.md` files to the qalarc-notes import folder and trigger its existing
    `.md` import path (qalarc-notes "Import .md files with frontmatter" feature — see README).
  - (b) Use the `NotesBridge.kt` interface if qalarc-notes exposes a content provider or
    intent for adding notes. **Check `~/projects/phone_projects/qalarc-notes/android/app/src/main/java/com/qalarc/notes/NotesBridge.kt`** to see what bridge methods exist before designing this.

**On desktop (Path B):** Write into the desktop notes mirror:
  `~/projects/phone_projects/redmi_note_14_5g/phone_dashboard/notes/ai_inbox/`
  (per qalarc-notes STORAGE.md §6 — the desktop mirror location). Filename convention:
  `YYYY-MM-DD_call-<slug>.md`

### 5.3 Read the actual bridge before designing

Before writing any integration code, the build agent MUST read:
- `~/projects/phone_projects/qalarc-notes/STORAGE.md` (full file — note format + storage model)
- `~/projects/phone_projects/qalarc-notes/android/app/src/main/java/com/qalarc/notes/NotesBridge.kt` (what the JS↔native bridge exposes)
- `~/projects/phone_projects/qalarc-notes/public/index.html` (the import path + allowed folders/types)

Do NOT invent a new note schema. Match the existing one exactly so transcripts are indistinguishable from any other qalarc-notes entry.

---

## 6. Suggested build plan (for the build agent)

1. **Recon (30 min):**
   - Read AGENT_GOTCHAS.md, qalarc-notes STORAGE.md, NotesBridge.kt, index.html.
   - Plug in the phone, check `adb devices`.
   - Test whether HyperOS native call recording exists: make a test call, look for new files in `/sdcard/MIUI/sound_recorder/`, `/sdcard/Recordings/`, `/sdcard/Sounds/`. Run `adb shell find /sdcard -iname '*call*' -newermt '5 minutes ago'` (WITH a timeout).
   - Decide Path A vs Path B based on what's available.

2. **v1 — Path B (ship something working fast):**
   - Simple Android app (or even a Termux/Tasker script first) that:
     - Detects call end (READ_PHONE_STATE) or watches the call-rec folder.
     - Copies the audio file to a synced location (or `adb pull` target).
   - Desktop side: a watcher that runs the existing `~/Documents/UTS/scripts/transcribe_hearing.sh` on new files and writes a qalarc-notes `.md` into the ai_inbox mirror.
   - Verify a transcript appears in qalarc-notes.

3. **v2 — Path A (on-device, offline):**
   - Cross-compile whisper.cpp for Android (NDK, ARM64, NEON). Bundle `ggml-small.en.bin` or `ggml-base.en.bin`.
   - Run transcription on-device in a foreground service.
   - Write `.md` directly into qalarc-notes via the import folder or bridge.

4. **Polish:**
   - Contact-name lookup (map number → name from contacts, with READ_CONTACTS perm — optional).
   - Settings screen: model selection, language, auto-transcribe on/off, retention.
   - Foreground-service notification while recording/transcribing.

---

## 7. Tech stack suggestions

- **Language:** Kotlin (consistent with qalarc-notes' `MainActivity.kt` / `NotesBridge.kt`).
- **Build:** Gradle, mirror the qalarc-notes android/ project structure.
- **Transcription engine:**
  - On-device: **whisper.cpp** (ARM build via NDK). Source already on disk at `~/projects/MASTER_PROJECTS/whisper-cpp/`.
  - Off-device: reuse **faster-whisper** desktop pipeline.
- **Audio format:** 16 kHz mono 16-bit PCM/WAV for whisper (same as the desktop scripts produce). Transcode call recordings (often `.m4a`/`.mp3`) to this with on-device ffmpeg or `MediaCodec` before transcription.
- **Models cached at:** desktop has faster-whisper `small` at `~/.cache/huggingface/hub/`; whisper.cpp `ggml-small.en.bin` referenced in the UTS scripts.

---

## 8. Reference: the working desktop pipeline (copy its ideas)

**The working, tested desktop scripts have been copied into this project folder** at:

```
phone_transcript_recorder/reference_desktop_pipeline/
├── README.md                    ← explains each file + how it maps to the phone
├── transcribe_hearing.sh        ← ⭐ the core faster-whisper transcriber
├── record_hearing_live.py       ← ⭐ dual-channel speaker-labelled LIVE transcription
├── record_hearing_dual.sh       ← simple ffmpeg dual-source (system + mic) capture
├── record_hearing.sh            ← minimal single-source parecord capture
├── record_hearing_bt.py         ← Bluetooth-aware capture variant
└── HEARING_RECORDING_README.md  ← original usage docs
```

**Read `reference_desktop_pipeline/README.md` first** — it explains what each script
demonstrates and contains a desktop→phone mapping table.

The two most important references:
- **`transcribe_hearing.sh`** — the exact faster-whisper invocation (small model, int8, VAD
  filter, beam_size=5) that produces `.txt` + `.srt`. For Path B (desktop transcription),
  this can be reused **verbatim**.
- **`record_hearing_live.py`** — dual-source capture with speaker labels (COMMITTEE vs YOU)
  and live chunked transcription. This is the blueprint for phone-call speaker separation
  if you can capture mic + downlink as two streams.

The originals remain at `~/Documents/UTS/scripts/` (do not delete — they are still in use
for the user's hearing). These copies are frozen references.

The phone app is conceptually the same pipeline (capture → 16 kHz WAV → whisper → text), with:
1. A different capture source (phone call audio instead of desktop PulseAudio).
2. A different output target (qalarc-notes `.md` instead of a loose `.txt`).

---

## 9. Definition of done (v1)

- [ ] User can record (or auto-capture) a phone call on the Redmi Note 14 5G.
- [ ] After the call, a transcript is produced (on-device or via desktop).
- [ ] The transcript appears in qalarc-notes as a properly-formatted note (`source: voice`, correct frontmatter, timestamped body).
- [ ] The source audio is optionally attached as a `.m4a` media object the user can replay.
- [ ] Install works on HyperOS via the MIUI-dialog install script.
- [ ] A README documents how to use it and the call-recording limitations on this device.

---

## 10. Open questions for the user (resolve before/early in build)

1. **Native call recording:** Is HyperOS's built-in call recorder available in your region/build? (If yes → folder-watch design is far simpler and higher quality.)
2. **On-device vs desktop transcription:** Do you want it fully offline on the phone (v2, more work), or is desktop-assisted transcription (v1, faster to ship) acceptable to start?
3. **qalarc-notes folder/type:** Should call transcripts go in `notes` with `type: log`, or do you want a dedicated `call_log` folder + `transcript` type added to qalarc-notes?
4. **Attach audio?** Do you want the original call audio attached to each note (replayable), or transcript-only (smaller, more private)?
5. **Contact names:** OK to grant READ_CONTACTS so transcripts are titled with contact names instead of raw numbers?
