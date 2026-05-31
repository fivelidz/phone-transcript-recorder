package com.fivelidz.transcriber

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an arbitrary audio file (mp3 / m4a / aac / wav / amr — anything the platform
 * codecs support) into a normalised 16 kHz mono FloatArray ready for whisper.cpp.
 *
 * Whisper expects: 16000 Hz, mono, samples in [-1.0, 1.0].
 *
 * Pipeline: MediaExtractor -> MediaCodec (decode to PCM 16-bit) -> downmix to mono ->
 * linear resample to 16 kHz -> normalise short/32768 -> float.
 */
object AudioDecoder {
    private const val TAG = "AudioDecoder"
    const val TARGET_SAMPLE_RATE = 16000

    /** Decode [file] to 16 kHz mono float samples. Returns empty array on failure. */
    fun decodeToMonoFloat16k(file: File): FloatArray {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "File missing/empty: ${file.absolutePath}")
            return FloatArray(0)
        }
        return try {
            val (samples, srcRate, channels) = decodePcm(file)
            if (samples.isEmpty()) return FloatArray(0)
            val mono = downmixToMono(samples, channels)
            val resampled = resampleLinear(mono, srcRate, TARGET_SAMPLE_RATE)
            normalise(resampled)
        } catch (e: Exception) {
            Log.e(TAG, "decode failed for ${file.name}", e)
            FloatArray(0)
        }
    }

    /** Decode the whole file to interleaved 16-bit PCM shorts. Returns (shorts, sampleRate, channels). */
    private fun decodePcm(file: File): Triple<ShortArray, Int, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("No audio track in ${file.name}")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Short>(1 shl 20)
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize,
                            extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val tmp = ShortArray(sb.remaining())
                    sb.get(tmp)
                    for (s in tmp) out.add(s)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEOS = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return Triple(out.toShortArray(), srcRate, channels)
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += interleaved[i * channels + c]
            mono[i] = (acc / channels).toShort()
        }
        return mono
    }

    /** Simple linear-interpolation resampler. Good enough for speech transcription. */
    private fun resampleLinear(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLen = (input.size * ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val a = input[idx].toInt()
            val b = if (idx + 1 < input.size) input[idx + 1].toInt() else a
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }

    private fun normalise(shorts: ShortArray): FloatArray =
        FloatArray(shorts.size) { i ->
            (shorts[i] / 32768.0f).coerceIn(-1f, 1f)
        }
}
