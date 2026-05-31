package com.fivelidz.transcriber

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit PCM mono samples to a .wav file, writing a correct RIFF header once the
 * total sample count is known (header is back-patched on close). 16 kHz mono — the exact
 * format whisper + sherpa want, so no re-decode is needed afterwards.
 */
class WavWriter(private val file: File, private val sampleRate: Int = 16000) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0
    private val headerSize = 44

    init {
        // Reserve space for the header; fill in on close().
        raf.setLength(0)
        raf.write(ByteArray(headerSize))
    }

    /** Append [count] shorts from [buffer] (little-endian PCM16). */
    fun write(buffer: ShortArray, count: Int) {
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(buffer[i])
        raf.write(bytes.array())
        dataBytes += count * 2
    }

    /** Finalise the WAV header and close. */
    fun close() {
        val totalDataLen = dataBytes + 36
        val byteRate = sampleRate * 2 // mono, 16-bit
        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)               // PCM chunk size
        header.putShort(1)              // PCM format
        header.putShort(1)              // channels = mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)              // block align
        header.putShort(16)             // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }

    val path: String get() = file.absolutePath
}
