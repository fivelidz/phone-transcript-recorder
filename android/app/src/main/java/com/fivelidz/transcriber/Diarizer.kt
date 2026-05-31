package com.fivelidz.transcriber

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * On-device speaker diarization (who-spoke-when) using sherpa-onnx.
 *
 * Models live in assets/diarization/:
 *   - segmentation.onnx  (pyannote-segmentation-3.0, int8, ~1.5 MB)  — speaker-turn boundaries
 *   - embedding.onnx     (NeMo TitaNet-small, ~39 MB)                — speaker voiceprints
 *
 * Runs fully offline on ARM. Input must be 16 kHz mono float in [-1,1] (the same buffer the
 * whisper pipeline already produces). Output is a list of (start,end,speakerId) turns, which
 * the pipeline then aligns to whisper's text segments (Pattern A: diarize-first, max-overlap).
 *
 * Diarization is best-effort: if the native lib or models are missing, [diarize] returns an
 * empty list and the transcript falls back to no speaker labels (never crashes).
 */
class Diarizer(private val ctx: Context) {

    companion object {
        private const val TAG = "Diarizer"
        private const val SEG_MODEL = "diarization/segmentation.onnx"
        private const val EMB_MODEL = "diarization/embedding.onnx"
    }

    data class Turn(val startSec: Float, val endSec: Float, val speaker: Int)

    @Volatile
    private var engine: OfflineSpeakerDiarization? = null
    private var initFailed = false

    /** True if diarization is available (lib loaded + models present). */
    fun isAvailable(): Boolean = ensureEngine() != null

    @Synchronized
    private fun ensureEngine(): OfflineSpeakerDiarization? {
        engine?.let { return it }
        if (initFailed) return null
        return try {
            // numSpeakers <= 0 means auto-detect via clustering threshold.
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = SEG_MODEL),
                    numThreads = 2,
                    debug = false,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = EMB_MODEL,
                    numThreads = 2,
                    debug = false,
                ),
                clustering = FastClusteringConfig(
                    numClusters = -1,   // auto-detect number of speakers
                    threshold = 0.5f,   // higher → fewer speakers; tuned for phone-mic 2–4 people
                ),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            )
            OfflineSpeakerDiarization(assetManager = ctx.assets, config = config).also {
                engine = it
                Log.i(TAG, "Diarization engine ready (sampleRate=${it.sampleRate()})")
            }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (lib missing) or model-load failure.
            Log.w(TAG, "Diarization unavailable — transcript will have no speaker labels", t)
            initFailed = true
            null
        }
    }

    /**
     * Diarize 16 kHz mono float samples. If [knownSpeakerCount] > 0, forces that many speakers.
     * Returns speaker turns sorted by start time, or empty list if diarization isn't available.
     */
    fun diarize(
        samples16kMono: FloatArray,
        knownSpeakerCount: Int = -1,
        onProgress: ((Float) -> Unit)? = null,
    ): List<Turn> {
        val eng = ensureEngine() ?: return emptyList()
        if (samples16kMono.isEmpty()) return emptyList()
        return try {
            if (knownSpeakerCount > 0) {
                eng.setConfig(
                    eng.config.copy(
                        clustering = FastClusteringConfig(numClusters = knownSpeakerCount)
                    )
                )
            }
            val segments = if (onProgress != null) {
                eng.processWithCallback(
                    samples = samples16kMono,
                    callback = { processed: Int, total: Int, _: Long ->
                        if (total > 0) onProgress(processed.toFloat() / total)
                        0 // continue
                    },
                )
            } else {
                eng.process(samples16kMono)
            }
            segments.map { Turn(it.start, it.end, it.speaker) }.sortedBy { it.startSec }
        } catch (t: Throwable) {
            Log.w(TAG, "diarize() failed", t)
            emptyList()
        }
    }

    fun release() {
        engine?.release()
        engine = null
    }
}
