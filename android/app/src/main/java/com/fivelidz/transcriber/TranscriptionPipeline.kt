package com.fivelidz.transcriber

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperSegment
import java.io.File
import java.util.Date

/**
 * The end-to-end on-device pipeline:
 *   audio file -> 16 kHz mono float -> whisper.cpp -> segments
 *              -> sherpa-onnx diarization -> per-segment speaker labels
 *              -> qalarc-notes .md
 *
 * Long recordings are chunked for whisper so a single whisper_full call doesn't run for
 * minutes on a huge buffer (and so we can report progress). Diarization runs once on the
 * whole buffer (it's fast — RTF well under 1), then we align speakers to text segments by
 * maximum time-overlap (Pattern A).
 */
class TranscriptionPipeline(private val ctx: Context) {
    companion object {
        private const val TAG = "TranscriptionPipeline"
        // Process audio in ~120 s chunks (1,920,000 samples @ 16 kHz).
        private const val CHUNK_SECONDS = 120
        private const val SAMPLES_PER_CHUNK = CHUNK_SECONDS * AudioDecoder.TARGET_SAMPLE_RATE
    }

    /** What kind of recording this is — drives note title/type/folder. */
    enum class Kind { CALL, MEETING }

    sealed class Progress {
        data class Decoding(val file: String) : Progress()
        data class Transcribing(val chunk: Int, val totalChunks: Int) : Progress()
        data class Diarizing(val fraction: Float) : Progress()
        data class Done(val noteTitle: String, val mirrorPath: String?) : Progress()
        data class Error(val message: String) : Progress()
    }

    private val diarizer by lazy { Diarizer(ctx) }

    /**
     * Transcribe [audioFile] and write a qalarc note.
     *
     * @param kind CALL or MEETING
     * @param title human title (for meetings); calls derive title from contact/number
     * @param enableDiarization run speaker separation (default true)
     * @param onProgress callback for UI / notification updates.
     */
    suspend fun process(
        audioFile: File,
        kind: Kind = Kind.CALL,
        contact: String? = null,
        number: String? = null,
        direction: String? = null,
        title: String? = null,
        modelName: String = ModelManager.DEFAULT_MODEL,
        enableDiarization: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): Boolean {
        val model = ModelManager.findModel(ctx, modelName)
        if (model == null) {
            val msg = "Model $modelName not found. Push it to Download/ or app files/models/."
            Log.e(TAG, msg)
            onProgress(Progress.Error(msg))
            return false
        }

        onProgress(Progress.Decoding(audioFile.name))
        val samples = AudioDecoder.decodeToMonoFloat16k(audioFile)
        if (samples.isEmpty()) {
            onProgress(Progress.Error("Could not decode ${audioFile.name}"))
            return false
        }

        val durationSeconds = (samples.size / AudioDecoder.TARGET_SAMPLE_RATE).toLong()
        val totalChunks = (samples.size + SAMPLES_PER_CHUNK - 1) / SAMPLES_PER_CHUNK

        var whisperCtx: WhisperContext? = null
        return try {
            whisperCtx = WhisperContext.createContextFromFile(model.absolutePath)
            val allSegments = ArrayList<WhisperSegment>()

            for (chunkIdx in 0 until totalChunks) {
                onProgress(Progress.Transcribing(chunkIdx + 1, totalChunks))
                val start = chunkIdx * SAMPLES_PER_CHUNK
                val end = minOf(start + SAMPLES_PER_CHUNK, samples.size)
                val chunk = samples.copyOfRange(start, end)
                val offsetCentis = (chunkIdx.toLong() * CHUNK_SECONDS) * 100L

                val segs = whisperCtx.transcribeSegments(chunk)
                for (s in segs) {
                    allSegments.add(
                        WhisperSegment(
                            t0Centis = s.t0Centis + offsetCentis,
                            t1Centis = s.t1Centis + offsetCentis,
                            text = s.text
                        )
                    )
                }
            }
            whisperCtx.release()
            whisperCtx = null

            // ---- Speaker diarization (best-effort) ----
            val speakerBySegment: IntArray? =
                if (enableDiarization && diarizer.isAvailable()) {
                    onProgress(Progress.Diarizing(0f))
                    val turns = diarizer.diarize(samples) { f ->
                        onProgress(Progress.Diarizing(f))
                    }
                    if (turns.isNotEmpty()) assignSpeakers(allSegments, turns) else null
                } else null

            val speakerCount = speakerBySegment?.toSet()?.count { it >= 0 } ?: 0

            val result = QalarcNotesWriter.writeTranscriptNote(
                ctx = ctx,
                kind = kind,
                contact = contact,
                number = number,
                direction = direction,
                meetingTitle = title,
                durationSeconds = durationSeconds,
                segments = allSegments,
                speakerBySegment = speakerBySegment,
                speakerCount = speakerCount,
                startedAt = Date(audioFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()),
            )

            saveLocalTranscript(audioFile, result.markdown, result.plainText)

            onProgress(Progress.Done(result.title, result.mirrorPath))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            onProgress(Progress.Error(e.message ?: "transcription error"))
            false
        } finally {
            whisperCtx?.release()
        }
    }

    /**
     * For each whisper segment, find the diarization turn with the most time overlap and use
     * its speaker id. Returns an IntArray parallel to [segments]; -1 means "unknown".
     */
    private fun assignSpeakers(
        segments: List<WhisperSegment>,
        turns: List<Diarizer.Turn>,
    ): IntArray = IntArray(segments.size) { i ->
        val seg = segments[i]
        val segStart = seg.t0Centis / 100f
        val segEnd = seg.t1Centis / 100f
        var bestSpeaker = -1
        var bestOverlap = 0f
        for (t in turns) {
            val overlap = minOf(segEnd, t.endSec) - maxOf(segStart, t.startSec)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestSpeaker = t.speaker
            }
        }
        bestSpeaker
    }

    private fun saveLocalTranscript(audioFile: File, markdown: String, plainText: String) {
        try {
            val dir = File(ctx.filesDir, "transcripts").apply { mkdirs() }
            File(dir, audioFile.nameWithoutExtension + ".md").writeText(markdown)
            File(dir, audioFile.nameWithoutExtension + ".txt").writeText(plainText)
        } catch (e: Exception) {
            Log.w(TAG, "local transcript save failed", e)
        }
    }
}
