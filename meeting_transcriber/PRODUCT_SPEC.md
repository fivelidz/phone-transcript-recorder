# Meeting Transcriber — Product & Architecture Spec

**Status:** Specification (not yet built)
**Created:** 2026-05-30
**Author:** Claude (for fivelidz)
**Relationship to existing work:** This is the **App-Store-publishable** sibling of the
`phone_transcript_recorder` (the on-device call transcriber in `../android/`). The call
transcriber solved "transcribe a cellular call into qalarc-notes" but is constrained to a
single mixed audio stream (OEM recorder file) and is region-/legality-gated. This product
sidesteps every one of those constraints by being the app that **hosts or joins the
conversation**, so it legitimately owns each participant's audio stream — giving perfect
per-speaker separation, no special permissions, and a clean path to the Play Store / App Store.

---

## 0. The one-sentence pitch

> A privacy-first meeting recorder & transcriber that captures **each participant on their own
> clean audio channel**, transcribes on-device (offline) or in the cloud (optional), and
> produces a speaker-labelled, searchable transcript that flows into your notes system.

---

## 1. Why this exists — the technical insight that makes it possible

From the call-recording research (`../README.md` and the dual-stream investigation), three
facts are settled:

1. **You cannot tap cellular or third-party VoIP (WhatsApp/Signal/Teams) call audio** from
   outside. `VOICE_CALL`/`VOICE_DOWNLINK`/`VOICE_UPLINK` need `CAPTURE_AUDIO_OUTPUT`
   (signature|privileged). `AudioPlaybackCapture` hard-excludes `USAGE_VOICE_COMMUNICATION`.
   Accessibility-API call recording is banned by Play policy (since May 2022).
2. **The acoustic speakerphone+mic fallback** works but is mixed-mono, low quality, and
   OEM-dependent (the mic can return silence during a cellular call on some devices).
3. **BUT if your app is the one running the call, you already hold both streams** — near-end
   from the mic (`AudioRecord`), far-end from your own network decode — *before* they hit the
   speaker. Clean separation is free.

**Therefore the product is not a "call recorder". It is a meeting platform (or meeting
*joiner*) that happens to transcribe.** This is exactly how Otter.ai, Fireflies, Fathom, and
Granola operate — they never wiretap the OS; they are a participant.

There are **two capture models**, and the product should ship Model A first, then Model B:

| Model | What it is | Separation | Effort | Play/App-Store risk |
|---|---|---|---|---|
| **A. Native multi-party VoIP** | Your own WebRTC room; people join via link | Perfect, per-participant | Medium | None |
| **B. Meeting "bot" / joiner** | A bot joins Zoom/Meet/Teams as a participant | Perfect, per-participant | High (per-platform) | None, but ToS-sensitive |
| (C. In-person / device mic) | Single mic in a room + diarization | Diarization-only | Low | None |

Model C is the degenerate fallback (same as the existing call app + diarization) for in-person
meetings where there is no VoIP layer to join.

---

## 2. Target users & jobs-to-be-done

- **Knowledge workers / founders** who want searchable, speaker-attributed records of meetings.
- **Researchers / journalists** doing interviews (consent-first).
- **Students** recording lectures / study groups.
- **The author (fivelidz)** — feeding clean, speaker-labelled transcripts into qalarc-notes /
  the RFAI personal-knowledge system.

JTBD: *"After a meeting, give me an accurate transcript that knows who said what, that I can
search, summarise, and drop into my notes — without me having to take notes during the call."*

---

## 3. Capture architecture (the core)

### 3.1 Model A — Native multi-party VoIP (build this first)

```
                         ┌──────────────────── Your app / SFU room ─────────────────────┐
  Participant A (phone) ──┤ mic→Opus→RTP ─┐                                              │
  Participant B (laptop)──┤ mic→Opus→RTP ─┤──▶  SFU (Selective Forwarding Unit)          │
  Participant C (phone) ──┤ mic→Opus→RTP ─┘        │  routes each track to everyone      │
                         └───────────────────────── │ ───────────────────────────────────┘
                                                     │
                                                     ▼
                              ┌──────────  Recorder (your code) ───────────┐
                              │  For each participant track:                │
                              │   Opus RTP → decode → 16 kHz mono PCM  ──────┼──▶ per-speaker WAV
                              │   (this is the SAME PCM that whisper wants)  │
                              │  Near-end (this device's own mic):           │
                              │   AudioRecord(VOICE_COMMUNICATION) (AEC on)  ┼──▶ "You" WAV
                              └─────────────────────────────────────────────┘
                                                     │
                                                     ▼
                              per-speaker WAVs ─▶ whisper.cpp (reuse existing whisperlib)
                                                     │
                                                     ▼
                              merge segments by timestamp ─▶ speaker-labelled transcript
                                                     │
                                                     ▼
                              qalarc-notes .md  (reuse existing QalarcNotesWriter, extended)
```

**Key reuse:** The decoded far-end PCM is *exactly* the `FloatArray` whisper consumes. The
existing `whisperlib` module, `AudioDecoder` (PCM→float), `TranscriptionPipeline`, and
`QalarcNotesWriter` all carry over. Only the **capture front-end** is new.

**WebRTC stack options:**
- **`libwebrtc` (Google)** via the maintained `io.github.webrtc-sdk:android` / `stream-webrtc-android`
  bindings — full control, you get raw decoded audio frames via `AudioTrackSink` /
  `addSink`-style callbacks per remote `AudioTrack`.
- **LiveKit** (open-source SFU + Android SDK) — easiest path to multi-party + server-side
  recording (LiveKit Egress can record each track separately on the server). Self-hostable.
- **Daily / Twilio / Agora** — commercial SFUs with recording APIs; faster to ship, recurring cost.

**Recommendation:** **LiveKit** for v1 of Model A. Self-hostable (privacy story), multi-party
out of the box, and **Egress** can produce per-participant audio files server-side, OR you tap
tracks client-side for on-device transcription. Start client-side/on-device to keep the
privacy-first positioning.

**Per-track tap (client-side, on-device transcription):**
- Each remote participant arrives as a separate `AudioTrack`. Attach an audio sink to each →
  you receive that participant's decoded PCM frames, tagged with their identity.
- Local participant: capture your own mic separately with `AudioRecord(VOICE_COMMUNICATION)`
  (echo-cancelled so the others' audio doesn't bleed into your channel).
- Result: N perfectly-isolated mono streams, each already labelled with a participant ID.

### 3.2 Model B — Meeting "bot" joiner (later; high value, high effort)

A headless participant joins an existing Zoom / Google Meet / Teams meeting (via link) and
receives every other participant's audio as separate network streams. This is what Otter/Fireflies
do. Two sub-approaches:

- **Official platform APIs/SDKs** (preferred, ToS-safe):
  - Zoom: **Meeting SDK** + **Raw Audio** access (per-participant raw audio is available to
    SDK apps with the user's consent) and/or **Cloud Recording API**.
  - Google Meet: **Meet Media API** (per-participant media streams, gated/allowlisted) or the
    Workspace recording/transcript APIs.
  - Microsoft Teams: **Graph cloud-communications / real-time media bots** (Application-hosted
    media bots receive per-participant streams).
- **Headless browser bot** (a Chromium instance joins via the web client and captures tab audio
  per participant) — works everywhere but is fragile and rides closer to platform ToS limits.

Model B almost certainly means a **server component** (bots run in the cloud, not on the phone).
That changes the privacy story (audio transits your server) — must be opt-in and clearly disclosed.

### 3.3 Model C — In-person fallback (cheap, reuse existing diarization plan)

Single device mic in a room → one mixed stream → **on-device speaker diarization** to recover
"who spoke when". This is the same diarization component proposed for the call app. Lowest
quality separation but zero infrastructure. Good for solo dictation and small in-person meetings.

---

## 4. Speaker separation summary

| Capture model | How speakers are separated | Quality |
|---|---|---|
| A. Native VoIP | Each participant = one network track = one channel | ★★★★★ perfect, pre-labelled |
| B. Bot joiner (official APIs) | Per-participant raw streams from the platform | ★★★★★ perfect, pre-labelled |
| B. Bot joiner (browser tab) | One mixed tab stream → diarization | ★★★☆☆ |
| C. In-person mic | One mixed stream → diarization | ★★★☆☆ |

For A and B-official, "speaker labels" come from the platform identity (display name), not from
acoustic guessing — so they're *correct*, not estimated. This is the headline feature.

---

## 5. Transcription engine

Reuse the proven on-device path, add an optional cloud tier:

- **On-device (default, privacy-first):** the existing `whisperlib` (whisper.cpp ARM64,
  `ggml-base.en` / `small`). Each participant stream transcribed independently, then merged by
  timestamp. This is the differentiator vs Otter (they're cloud-only).
- **Cloud (optional, opt-in):** for long meetings, low-end phones, or higher accuracy — stream
  to a server running faster-whisper / whisper-large-v3 / a diarization+ASR pipeline
  (e.g. `whisperX`, `pyannote` + whisper). Must be explicit opt-in with clear data handling.

**Merging:** each stream yields `[t0,t1] text` segments tagged with a speaker. Interleave all
participants' segments by `t0` to produce the chronological transcript:

```
[00:00] Alice: Thanks everyone for joining.
[00:04] Bob:   No worries — did you see the draft?
[00:07] Alice: Yes, a couple of comments...
```

---

## 6. Output / integrations

- **qalarc-notes** (primary, reuse `QalarcNotesWriter`, extended for multi-speaker bodies and a
  `type: meeting` or `type: log`, `source: voice`, `tags: [meeting, transcript, <slug>]`).
- **Standard exports:** `.md`, `.txt`, `.srt`, `.vtt`, `.json` (segments+speakers).
- **AI summary** (post-meeting): action items, decisions, attendees, TL;DR — generated
  on-device (small LLM) or via the user's chosen API. Fits the RFAI theme.
- **Share sheet / cloud:** optional, opt-in.

---

## 7. UX flow (Model A, v1)

1. **Start or join a meeting** — create a room (share a link) or paste a room link.
2. **Consent gate** — before recording, an explicit screen: *"Recording & transcribing. All
   participants are notified."* Every participant sees a recording indicator (legal + ethical).
3. **Live view** — participant tiles, live partial transcript scrolling, who's-speaking
   highlight. A "still listening" heartbeat.
4. **End meeting** — on-device transcription finalises (progress shown), summary generated.
5. **Result** — speaker-labelled transcript + summary, saved to qalarc-notes and exportable.
6. **Library** — searchable list of past meetings, full-text search across transcripts.

---

## 8. Legal, consent & store-policy posture (do this right or it dies in review)

- **Consent-first by design.** All participants are notified recording is happening (in-app
  banner + audible/visible indicator). This is both an ethics requirement and what keeps it
  store-approvable. (Recall NSW one-party-consent note from the call app, but the product is
  global, so design for the strictest common denominator: **all-party notification**.)
- **No wiretapping of other apps.** The product never touches cellular/WhatsApp/Signal audio.
  It only records conversations it hosts or is an invited participant in. This is the entire
  reason it's publishable.
- **Data handling transparency.** On-device by default; any cloud transcription is opt-in with
  a clear privacy disclosure and data-deletion controls. Required for Play "Data safety" form
  and App Store privacy nutrition labels.
- **Microphone foreground-service** with the correct `microphone` FGS type (Android 14) and a
  persistent notification.
- **Avoid the call-log/default-dialer permissions entirely** — not needed for this product,
  and they're a Play-policy minefield. Big advantage over the call recorder.

---

## 9. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Client | Android (Kotlin), later iOS (Swift) or KMP/Flutter for cross-platform | Reuse Android learnings first |
| RTC | **LiveKit** (open-source SFU + client SDK) | Self-hostable; Egress for server-side per-track recording |
| On-device ASR | **whisper.cpp** (reuse `whisperlib`) | Already building & verified on ARM64 |
| Cloud ASR (opt) | faster-whisper / whisperX + pyannote | Diarization + ASR for the bot/in-person paths |
| Diarization (Model C) | `sherpa-onnx` diarization or pyannote (server) | Only needed for mixed-stream sources |
| Summary | small on-device LLM or user-chosen API | Opt-in |
| Storage | Room/SQLite local; optional encrypted cloud | Local-first |
| Notes integration | qalarc-notes `.md` (reuse `QalarcNotesWriter`) | + generic exporters |

---

## 10. Build roadmap

**Phase 0 — Reuse audit (1 day)**
- Confirm `whisperlib`, `AudioDecoder`, `TranscriptionPipeline`, `QalarcNotesWriter` lift cleanly
  into a new app module. Extend `QalarcNotesWriter` for multi-speaker bodies + `type: meeting`.

**Phase 1 — Model A MVP, 2-party, on-device (the proof)**
- Integrate LiveKit Android SDK; create/join a 2-person room.
- Tap each remote `AudioTrack` → PCM; capture local mic (`VOICE_COMMUNICATION`) separately.
- Per-stream whisper transcription; timestamp-merge into a speaker-labelled transcript.
- Consent gate + recording indicator. Save to qalarc-notes + `.md`/`.srt` export.
- **Definition of done:** two phones in a room → end call → one transcript with correct
  "Speaker A/Speaker B" (display-name) labels, in qalarc-notes.

**Phase 2 — Multi-party + polish**
- N participants, live partial transcript, who's-speaking UI, library + search, AI summary.
- Optional cloud transcription tier (opt-in) for long/large meetings.

**Phase 3 — Model B (bot joiner) — separate, larger effort**
- Server component. Start with **Zoom Meeting SDK + raw audio** (best-documented per-participant
  access) as the first platform. Add Meet/Teams later. Strictly opt-in, cloud-disclosed.

**Phase 4 — Cross-platform + store launch**
- iOS (or KMP/Flutter), Data-safety/privacy labels, store listings, geo-considerations.

---

## 11. Differentiators vs Otter/Fireflies/Fathom

- **On-device / offline transcription option** — true privacy; competitors are cloud-only.
- **Self-hostable RTC (LiveKit)** — your audio never leaves your infra if you don't want it to.
- **Notes-system native** (qalarc-notes / RFAI) — transcripts are first-class knowledge objects,
  not siloed in yet-another-SaaS.
- **One codebase, three capture models** (native room, bot joiner, in-person mic) — meet users
  wherever the meeting happens.

---

## 12. Open questions for the user

1. **Primary capture model for v1?** Native VoIP room (Model A — you host the meeting) vs
   bot-joiner (Model B — joins existing Zoom/Meet). A is simpler and fully self-contained; B has
   bigger reach but needs servers + per-platform work.
2. **Cloud at all, or strictly on-device?** Strictly on-device is the strongest privacy story
   but caps meeting length/quality on weak phones.
3. **Cross-platform from the start (Flutter/KMP) or Android-first** (reusing the existing
   Kotlin/whisper work)?
4. **Self-host LiveKit** (privacy, infra burden) **vs managed** (Daily/Agora/LiveKit Cloud —
   faster, recurring cost)?
5. **Monetisation:** free on-device + paid cloud/summary tier? One-time vs subscription?
6. **Is this a separate app, or a "meeting mode" inside the same app as the call transcriber?**

---

## 13. Component reuse map (from the existing call transcriber)

| Existing component | Reuse in meeting transcriber |
|---|---|
| `whisperlib/` (whisper.cpp JNI, ARM64) | ✅ As-is — the ASR engine |
| `LibWhisper.transcribeSegments()` | ✅ Per-participant transcription |
| `AudioDecoder` (audio→16 kHz mono float) | ✅ For file paths / recorded tracks |
| `TranscriptionPipeline` (chunked transcribe) | ✅ Extend to per-stream + merge |
| `QalarcNotesWriter` | ✅ Extend: multi-speaker body, `type: meeting` |
| `ModelManager` / model on disk | ✅ As-is |
| Foreground-service + FGS-microphone pattern | ✅ Pattern carries over |
| Capture front-end (FileObserver) | ❌ Replaced by WebRTC track taps |

**Bottom line:** ~60–70% of the hard, already-verified work (on-device ASR, PCM handling, notes
output) is reusable. The genuinely new work is the **WebRTC/LiveKit capture layer** and the
**meeting UX**.
