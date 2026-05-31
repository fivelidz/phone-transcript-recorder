# Meeting Transcriber — Model B (Bot Joiner) Spec

**Status:** Specification (not yet built — requires a server component)
**Created:** 2026-05-30
**Parent:** `PRODUCT_SPEC.md`
**Decision from user:** "definitely model B — this should be a third party app."

This spec covers the **bot-joiner** path: a service that sends a bot into a scheduled
**Zoom / Google Meet / Microsoft Teams** meeting, captures **per-participant audio**, and turns
it into a speaker-labelled transcript that flows into qalarc-notes (and the rest of the product).

It complements the **on-device** capture already built in `../android/` (in-person mic + call
speakerphone + diarization), which handles the cases where there is no VoIP meeting to join.

---

## 1. The hard truth (why this needs a server)

Bot-joining is fundamentally a **server-side** capability — bots run in the cloud, join the
meeting as a visible participant, and stream audio to a transcription pipeline. It cannot run
on the user's phone. The research is unambiguous:

| Platform | Live per-participant audio? | Official path | Catch |
|---|---|---|---|
| **Zoom** | ✅ Yes | **RTMS** (Real-Time Media Streams) — server webhook, per-track audio | Host needs an RTMS-eligible plan; Marketplace app + review |
| **Zoom** | ✅ Yes | Meeting SDK `onOneWayAudioRawDataReceived` (native) | **Zoom now bans SDK for "bots / AI notetakers"** — use RTMS |
| **Google Meet** | ⚠️ Preview only | **Meet Media API** (WebRTC, per-speaker via RTP CSRC) | **Allowlist** — *every participant* must be in Google's Developer Preview. Not usable in production yet. |
| **Google Meet** | ✅ (post-meeting) | Meet REST API artifacts (recording/transcript after call) | Workspace host only; not live |
| **MS Teams** | ✅ Yes | Application-hosted media bot (`Calls.AccessMedia.All`) | **C#/.NET on Windows Server + Azure VM w/ public IP + per-tenant admin consent.** Microsoft explicitly discourages it for AI notetakers. |
| **MS Teams** | ✅ (post-meeting) | Graph API transcripts/recordings | Admin consent; metered; not live |
| **Any (headless browser)** | ❌ mixed only | Chromium joins web client, taps tab audio | Mixed mono (no per-speaker); ToS-violating; bot-detected |

**Conclusion:** building native per-platform bots in 2026 means: Zoom RTMS (doable), Meet Media
API (gated behind an allowlist — blocked), Teams media bot (a Windows/Azure/C# compliance-grade
project). That's months of specialised, fragile work *before* writing a single line of product.

---

## 2. The recommended path: Recall.ai for v1

**Every major competitor — Otter, Fireflies, Fathom, Apollo, HubSpot's notetaker — is built on
[Recall.ai](https://recall.ai)** (or a clone like Meeting BaaS / Nylas Notetaker / Attendee.ai).
It is a "meeting-bot-as-a-service": one API sends a bot into Zoom/Meet/Teams/Webex/Slack and
returns **per-participant audio + real-time transcript + recording**, handling all the ugly parts
(container-per-meeting orchestration, per-platform capture, bot-detection evasion, consent banner,
SOC2/HIPAA).

**Why this is the right v1:**
- Integration in days, not months. Multi-platform on day one.
- They own the per-participant audio extraction (the genuinely hard IP) and the ToS risk.
- **Our differentiation is not the bot** — it's the on-device privacy story, the qalarc-notes /
  RFAI knowledge integration, and the UX. Recall.ai lets us focus there.
- Cost is reasonable at v1 scale: **~$0.50/hr recording + ~$0.15/hr transcription** (we can BYO
  transcription and point it at our own whisper, see §5). 1,000 hrs/mo ≈ $500–650/mo.

**Go native later (v2+):** build **Zoom RTMS** directly once customers are on eligible plans
(ToS-safe, Marketplace-listed, best quality). Watch **Meet Media API** for GA. Treat **Teams
native** as enterprise-compliance-only.

---

## 3. v1 architecture (Recall.ai + our stack)

```
 User's phone app  ──┐
 (schedule / paste   │  "transcribe this meeting" (link or calendar event)
  meeting link)      │
                     ▼
        ┌─────────────────────────────┐
        │  Our backend (orchestrator)  │   (small: Node/Python + DB + webhook receiver)
        │   POST api.recall.ai/bot     │
        └───────────────┬─────────────┘
                        │ Recall spins up a bot that JOINS the meeting (visible, announces
                        │ "Recording for transcription"), captures PER-PARTICIPANT audio
                        ▼
        ┌─────────────────────────────┐
        │   Recall.ai                  │
        │   - per-participant audio    │──── real-time WebSocket: {participant, pcm, ts}
        │   - speaker timeline         │──── async: per-speaker mp3/mp4 + metadata
        └───────────────┬─────────────┘
                        ▼
        ┌─────────────────────────────┐
        │  Transcription (our choice): │
        │   (a) Recall built-in ASR    │   ← fastest to ship
        │   (b) BYO whisper on server  │   ← privacy story, reuse whisperlib model
        └───────────────┬─────────────┘
                        ▼
        ┌─────────────────────────────┐
        │  Our value layer:            │
        │   - merge speaker turns      │
        │   - LLM summary/action items │
        │   - qalarc-notes .md  (reuse QalarcNotesWriter format)
        │   - push back to phone app   │
        └─────────────────────────────┘
```

**What we build for v1 (small):**
1. **Orchestrator API** — accept a meeting link / calendar event, call Recall to dispatch a bot.
2. **Webhook + WebSocket receiver** — consume Recall's real-time transcript + per-speaker audio.
3. **Notes formatter** — reuse the exact qalarc-notes Markdown format from
   `../android/.../QalarcNotesWriter.kt` (speaker-labelled body, `type: meeting`). Port to the
   server language (Node/Python) — it's ~50 lines.
4. **Calendar integration** (optional v1.1) — Recall's Calendar API auto-schedules bots from the
   user's Google/Microsoft calendar.
5. **Phone app hook** — a new "Online meeting (Zoom/Meet/Teams)" entry in the app that posts the
   link to our backend and later pulls the finished transcript into qalarc-notes (same note path
   as the on-device modes).

**What we do NOT build for v1:** native Zoom/Meet/Teams SDKs, Windows/Azure media bots, WebRTC
clients, container-per-meeting infra. Recall handles all of it.

---

## 4. Consent & legal (non-negotiable, and what keeps it App-Store/Marketplace-safe)

- The bot **joins visibly** and is named (e.g. "<User>'s Notetaker"), and **announces recording**
  (chat message + the platform's own recording indicator). All three platforms mandate a visible
  recording notice; ~12 US states + GDPR require all-party notice. Recall supports this out of the box.
- **Opt-in, disclosed data flow.** Unlike the on-device modes (audio never leaves the phone), the
  bot path routes meeting audio through Recall + our server. This must be a clearly separate,
  explicitly-consented feature in the app with its own privacy disclosure (Play Data-safety / App
  Store privacy labels).
- **Region/data-residency:** Recall offers US/EU/JP residency; expose this in settings for orgs.

---

## 5. Privacy tier: BYO transcription with our own whisper

To preserve the product's "privacy-first" identity even on the bot path, use Recall **only for
capture** (per-participant audio) and run **our own whisper** for transcription on our server
(or even stream the per-participant audio back to a trusted box). Recall supports BYO
transcription / raw audio output. This keeps the *content* (the words) inside our pipeline rather
than a third-party ASR, and reuses the same whisper models the phone app uses. Diarization is
already solved by Recall's per-participant separation (no sherpa needed on this path).

---

## 6. Build roadmap (Model B)

**Phase B0 — Spike (1 week):** Recall.ai trial account → dispatch a bot into a test Zoom + Meet +
Teams meeting → receive real-time transcript via webhook → print it. Validates the whole path.

**Phase B1 — MVP:** Orchestrator API + webhook receiver + qalarc-notes formatter + a phone-app
"online meeting" entry. Use Recall built-in ASR first. Definition of done: paste a Zoom link in
the app → bot joins → after the meeting, a speaker-labelled note lands in qalarc-notes.

**Phase B2 — Privacy tier + calendar:** BYO whisper transcription on our server; Recall Calendar
integration for auto-scheduling; LLM summary/action-items.

**Phase B3 — Native Zoom RTMS:** first-party, Marketplace-listed Zoom integration (ToS-safe, best
quality, removes Recall cost for Zoom). Keep Recall for Meet/Teams.

**Phase B4 — Evaluate native Meet (when Media API GAs) and Teams (only if targeting enterprise
compliance).**

---

## 7. Cost model (rough, for planning)

| Monthly meeting hours | Recall capture (~$0.50/hr) | + ASR if using Recall (~$0.15/hr) | Notes |
|---|---|---|---|
| 1,000 | $500 | +$150 | First 5 hrs free; BYO whisper removes the +$150 |
| 10,000 | $5,000 | +$1,500 | Volume discounts kick in; consider native Zoom RTMS |
| 100,000 | enterprise (neg.) | — | Native bots become economical here |

At low scale Recall is cheap and lets us validate. As volume grows, native Zoom RTMS (Phase B3)
cuts the per-hour cost for the biggest platform.

---

## 8. How this fits the whole product

| Capture mode | Where it runs | Separation | Privacy | Status |
|---|---|---|---|---|
| In-person meeting (mic) | **On device** | sherpa diarization | audio never leaves phone | ✅ **built** |
| Phone call (speakerphone) | **On device** | sherpa diarization | on phone | ✅ **built** |
| Phone call (OEM recorder file) | **On device** | sherpa diarization | on phone | ✅ **built** |
| Online meeting (Zoom/Meet/Teams) | **Server (Recall.ai)** | per-participant (perfect) | opt-in, disclosed | 🔜 **this spec** |

The phone app is the unified front-end and notes destination for all four. Model B adds the one
capability that genuinely cannot live on the phone — and does it the way the whole industry does.

---

## 9. Key references

- Recall.ai docs: https://docs.recall.ai/docs/getting-started · pricing: https://recall.ai/pricing
- Per-participant realtime audio: https://docs.recall.ai/docs/how-to-get-separate-audio-per-participant-realtime
- Zoom RTMS: https://developers.zoom.us/docs/rtms/
- Google Meet Media API (preview/allowlist): https://developers.google.com/workspace/meet/media-api/guides/overview
- Teams real-time media bots: https://learn.microsoft.com/en-us/microsoftteams/platform/bots/calls-and-meetings/real-time-media-concepts
