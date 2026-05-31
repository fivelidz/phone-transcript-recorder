package com.fivelidz.transcriber

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import com.whispercpp.whisper.WhisperSegment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a list of transcript segments (optionally with per-segment speaker ids) into a
 * qalarc-notes-compatible Markdown note and delivers it two ways:
 *   1. Fires the com.qalarc.notes.CREATE_NOTE intent so the note lands in the running app.
 *   2. Writes a durable .md mirror to Documents/qalarc-notes/notes/ (always succeeds).
 *
 * Format matches qalarc-notes STORAGE.md §5: YAML frontmatter + body with [mm:ss] timestamps
 * and, when diarization succeeded, **Speaker N:** labels.
 */
object QalarcNotesWriter {
    private const val TAG = "QalarcNotesWriter"

    private const val QALARC_PKG = "com.qalarc.notes.debug"
    private const val QALARC_ACTIVITY = "com.qalarc.notes.MainActivity"
    private const val CREATE_NOTE_ACTION = "com.qalarc.notes.CREATE_NOTE"

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val titleFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    data class Result(
        val title: String,
        val markdown: String,
        val plainText: String,
        val mirrorPath: String?,
        val txtPath: String?,
        val intentSent: Boolean,
    )

    /**
     * Build + deliver a transcript note.
     *
     * @param kind CALL or MEETING — drives title/type/emoji/tags
     * @param meetingTitle for meetings, an optional user title
     * @param segments whisper output
     * @param speakerBySegment optional IntArray parallel to [segments]; speaker id per segment
     *        (-1 = unknown). Null = diarization not run / unavailable.
     * @param speakerCount number of distinct speakers detected (0 if none)
     */
    fun writeTranscriptNote(
        ctx: Context,
        kind: TranscriptionPipeline.Kind,
        contact: String?,
        number: String?,
        direction: String?,
        meetingTitle: String?,
        durationSeconds: Long,
        segments: List<WhisperSegment>,
        speakerBySegment: IntArray?,
        speakerCount: Int,
        startedAt: Date = Date(),
    ): Result {
        val createdIso = isoFmt.format(startedAt)
        val updatedIso = isoFmt.format(Date())
        val titleStamp = titleFmt.format(startedAt)

        val (title, slug, emoji, typeTag) = when (kind) {
            TranscriptionPipeline.Kind.CALL -> {
                val label = contact?.takeIf { it.isNotBlank() } ?: (number ?: "Unknown")
                Quad("Call with $label \u2014 $titleStamp", slugify(label), "\uD83D\uDCDE", "call")
            }
            TranscriptionPipeline.Kind.MEETING -> {
                val label = meetingTitle?.takeIf { it.isNotBlank() } ?: "Meeting"
                Quad("$label \u2014 $titleStamp", slugify(label), "\uD83C\uDF99\uFE0F", "meeting")
            }
        }

        val effectiveDuration =
            if (durationSeconds > 0) durationSeconds
            else segments.lastOrNull()?.endSeconds ?: 0L

        val body = buildBody(
            title, effectiveDuration, direction, number, segments,
            speakerBySegment, speakerCount
        )
        val frontmatter = buildFrontmatter(title, createdIso, updatedIso, slug, emoji, typeTag, speakerCount)
        val markdown = frontmatter + "\n" + body

        // Plain-text version (no frontmatter, no markdown bold) for a clean .txt export.
        val plainText = buildPlainText(
            title, effectiveDuration, direction, number, segments,
            speakerBySegment, speakerCount
        )

        val mirrorPath = writeMirror(typeTag, slug, startedAt, markdown)
        val txtPath = writeTxt(typeTag, slug, startedAt, plainText)
        val intentSent = sendCreateNoteIntent(ctx, title, body, slug, typeTag)

        return Result(title, markdown, plainText, mirrorPath, txtPath, intentSent)
    }

    /** Plain-text transcript: human-readable, no markdown/frontmatter. */
    private fun buildPlainText(
        title: String,
        durationSeconds: Long,
        direction: String?,
        number: String?,
        segments: List<WhisperSegment>,
        speakerBySegment: IntArray?,
        speakerCount: Int,
    ): String = buildString {
        append(title).append("\n")
        append("Duration: ").append(formatDuration(durationSeconds)).append("\n")
        if (!direction.isNullOrBlank()) append("Direction: ").append(direction).append("\n")
        if (!number.isNullOrBlank()) append("Number: ").append(number).append("\n")
        if (speakerCount > 0) append("Speakers detected: ").append(speakerCount).append("\n")
        append("\n")
        if (segments.isEmpty()) {
            append("(No speech detected.)\n")
        } else {
            var lastSpeaker = Int.MIN_VALUE
            for (i in segments.indices) {
                val seg = segments[i]
                if (seg.text.isBlank()) continue
                val speaker = speakerBySegment?.getOrNull(i) ?: -1
                append("[").append(formatMmSs(seg.startSeconds)).append("] ")
                if (speaker >= 0 && speaker != lastSpeaker) {
                    append("Speaker ").append(speaker + 1).append(": ")
                    lastSpeaker = speaker
                } else if (speaker >= 0) {
                    append("Speaker ").append(speaker + 1).append(": ")
                }
                append(seg.text).append("\n")
            }
        }
    }

    private data class Quad(val title: String, val slug: String, val emoji: String, val type: String)

    private fun buildFrontmatter(
        title: String, created: String, updated: String, slug: String,
        emoji: String, typeTag: String, speakerCount: Int,
    ): String = buildString {
        append("---\n")
        append("title: \"").append(escapeYaml(title)).append("\"\n")
        append("created: ").append(created).append("\n")
        append("updated: ").append(updated).append("\n")
        append("folder: ai_inbox\n")
        append("type: log\n")
        append("status: open\n")
        append("author: ai\n")
        append("mood: ").append(emoji).append("\n")
        append("tags: [").append(typeTag).append(", transcript, ").append(slug).append("]\n")
        append("source: voice\n")
        if (speakerCount > 0) append("speakers: ").append(speakerCount).append("\n")
        append("---\n")
    }

    private fun buildBody(
        title: String,
        durationSeconds: Long,
        direction: String?,
        number: String?,
        segments: List<WhisperSegment>,
        speakerBySegment: IntArray?,
        speakerCount: Int,
    ): String = buildString {
        append("# ").append(title).append("\n\n")
        append("**Duration:** ").append(formatDuration(durationSeconds)).append("\n")
        if (!direction.isNullOrBlank()) append("**Direction:** ").append(direction).append("\n")
        if (!number.isNullOrBlank()) append("**Number:** ").append(number).append("\n")
        if (speakerCount > 0) append("**Speakers detected:** ").append(speakerCount).append("\n")
        append("\n---\n\n")
        if (segments.isEmpty()) {
            append("_(No speech detected / empty transcript.)_\n")
        } else {
            var lastSpeaker = Int.MIN_VALUE
            for (i in segments.indices) {
                val seg = segments[i]
                if (seg.text.isBlank()) continue
                val speaker = speakerBySegment?.getOrNull(i) ?: -1
                append("[").append(formatMmSs(seg.startSeconds)).append("] ")
                if (speaker >= 0) {
                    // Only repeat the speaker label when it changes, for readability.
                    if (speaker != lastSpeaker) {
                        append("**Speaker ").append(speaker + 1).append(":** ")
                        lastSpeaker = speaker
                    }
                }
                append(seg.text).append("\n")
            }
        }
    }

    private fun sendCreateNoteIntent(
        ctx: Context, title: String, body: String, slug: String, typeTag: String,
    ): Boolean = try {
        val intent = Intent(CREATE_NOTE_ACTION).apply {
            setClassName(QALARC_PKG, QALARC_ACTIVITY)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra("folder", "ai_inbox")
            putExtra("tags", "$typeTag,transcript,$slug")
            putExtra("source", "voice")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        Log.i(TAG, "CREATE_NOTE intent sent to $QALARC_PKG")
        true
    } catch (e: Exception) {
        Log.w(TAG, "CREATE_NOTE intent failed (is qalarc-notes installed?)", e)
        false
    }

    private fun writeMirror(typeTag: String, slug: String, startedAt: Date, markdown: String): String? = try {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docs, "qalarc-notes/notes")
        dir.mkdirs()
        val name = "${fileFmt.format(startedAt)}_$typeTag-$slug.md"
        val file = File(dir, name)
        file.writeText(markdown)
        Log.i(TAG, "Mirror written: ${file.absolutePath}")
        file.absolutePath
    } catch (e: Exception) {
        Log.w(TAG, "Mirror write failed", e)
        null
    }

    /** Write a plain .txt transcript to Documents/Transcripts/ (easy to share/open anywhere). */
    private fun writeTxt(typeTag: String, slug: String, startedAt: Date, plainText: String): String? = try {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docs, "Transcripts")
        dir.mkdirs()
        val name = "${fileFmt.format(startedAt)}_$typeTag-$slug.txt"
        val file = File(dir, name)
        file.writeText(plainText)
        Log.i(TAG, "Txt written: ${file.absolutePath}")
        file.absolutePath
    } catch (e: Exception) {
        Log.w(TAG, "Txt write failed", e)
        null
    }

    // ---- helpers ----

    private fun slugify(s: String): String =
        s.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "unknown" }
            .take(40)

    private fun escapeYaml(s: String): String = s.replace("\"", "\\\"")

    private fun formatMmSs(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds <= 0) return "unknown"
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
