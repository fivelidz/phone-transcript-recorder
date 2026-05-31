package com.fivelidz.transcriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Live microphone recorder for two modes:
 *
 *   MODE_MEETING — in-person meeting / dictation. Records the mic until stopped.
 *   MODE_CALL    — record an ongoing phone call acoustically. Requires SPEAKERPHONE to be ON
 *                  (we can't tap the call audio stream — see README). We detect the audio route
 *                  and warn the user if the speaker is off, and watch for silence.
 *
 * Records 16 kHz mono PCM straight to a .wav (no re-decode needed), then hands the file to the
 * TranscriptionPipeline (whisper + diarization → qalarc note) when recording stops.
 *
 * Controls: start via startRecording(); stop via ACTION_STOP (from the notification button or UI).
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "RecorderService"
        private const val CHANNEL_ID = "recorder"
        private const val NOTIF_ID = 43
        private const val SAMPLE_RATE = 16000

        const val ACTION_START = "com.fivelidz.transcriber.REC_START"
        const val ACTION_STOP = "com.fivelidz.transcriber.REC_STOP"
        const val EXTRA_MODE = "mode"          // "meeting" | "call"
        const val EXTRA_TITLE = "title"        // meeting title (optional)
        const val MODE_MEETING = "meeting"
        const val MODE_CALL = "call"

        @Volatile var isRecording = false
            private set

        fun startRecording(ctx: Context, mode: String, title: String? = null) {
            val i = Intent(ctx, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stopRecording(ctx: Context) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var audioManager: AudioManager
    private lateinit var settings: Settings

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var wav: WavWriter? = null
    private var wavFile: File? = null
    private var mode = MODE_MEETING
    private var meetingTitle: String? = null
    private var startedAt = Date()

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        settings = Settings(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MEETING
                meetingTitle = intent.getStringExtra(EXTRA_TITLE)
                startRecordingInternal()
            }
            ACTION_STOP -> {
                stopRecordingInternal(transcribe = true)
            }
        }
        return START_STICKY
    }

    private fun startRecordingInternal() {
        if (recording) return
        startedAt = Date()
        startForegroundCompat(buildNotification(statusLine(), ongoing = true))

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // ~0.5s buffer

        // MIC source for acoustic capture. VOICE_COMMUNICATION applies AEC which actively
        // SUPPRESSES the far-end (speaker) audio we WANT to record on a call — so use MIC.
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: SecurityException) {
            updateNotification("Microphone permission missing")
            stopSelf(); return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            updateNotification("Could not open microphone")
            stopSelf(); return
        }

        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(startedAt)
        val f = File(dir, "${mode}_$stamp.wav")
        wavFile = f
        wav = WavWriter(f, SAMPLE_RATE)

        audioRecord = ar
        ar.startRecording()
        recording = true
        isRecording = true

        scope.launch {
            val buffer = ShortArray(bufSize)
            var silentChunks = 0
            var loops = 0
            while (isActive && recording) {
                val read = ar.read(buffer, 0, buffer.size)
                if (read > 0) {
                    wav?.write(buffer, read)
                    // Silence / speakerphone monitoring
                    var maxAmp = 0
                    for (i in 0 until read) {
                        val a = abs(buffer[i].toInt())
                        if (a > maxAmp) maxAmp = a
                    }
                    if (maxAmp < 60) silentChunks++ else silentChunks = 0

                    // Refresh notification ~ every 2s with route/silence state
                    if (loops % 4 == 0) updateNotification(statusLine(silentChunks))
                    loops++
                }
            }
        }
    }

    private fun stopRecordingInternal(transcribe: Boolean) {
        if (!recording) { stopSelf(); return }
        recording = false
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        val f = wavFile
        wav?.close()
        wav = null

        if (transcribe && f != null && f.length() > 1024) {
            updateNotification("Transcribing recording\u2026")
            scope.launch {
                val pipeline = TranscriptionPipeline(this@RecorderService)
                val kind = if (mode == MODE_CALL) TranscriptionPipeline.Kind.CALL
                           else TranscriptionPipeline.Kind.MEETING
                pipeline.process(
                    audioFile = f,
                    kind = kind,
                    title = meetingTitle,
                    modelName = settings.modelName,
                    enableDiarization = settings.diarizationEnabled,
                ) { p ->
                    when (p) {
                        is TranscriptionPipeline.Progress.Transcribing ->
                            updateNotification("Transcribing (${p.chunk}/${p.totalChunks})\u2026")
                        is TranscriptionPipeline.Progress.Diarizing ->
                            updateNotification("Identifying speakers ${(p.fraction * 100).toInt()}%\u2026")
                        is TranscriptionPipeline.Progress.Done ->
                            updateNotification("Saved: ${p.noteTitle}")
                        is TranscriptionPipeline.Progress.Error ->
                            updateNotification("Error: ${p.message}")
                        else -> {}
                    }
                }
                stopForegroundCompat()
                stopSelf()
            }
        } else {
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ---- Audio route / speakerphone detection ----

    /** True if the current communication route is the built-in loudspeaker. */
    private fun isSpeakerphoneOn(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = audioManager.communicationDevice
            if (dev != null) dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            else @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn
        } else {
            @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn
        }
    } catch (_: Exception) { false }

    private fun isCallActive(): Boolean {
        val m = audioManager.mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun statusLine(silentChunks: Int = 0): String {
        if (!recording) return "Idle"
        val elapsed = ((System.currentTimeMillis() - startedAt.time) / 1000)
        val mm = elapsed / 60; val ss = elapsed % 60
        val time = String.format(Locale.US, "%02d:%02d", mm, ss)
        return if (mode == MODE_CALL) {
            when {
                !isSpeakerphoneOn() ->
                    "● $time — TURN ON SPEAKERPHONE so the call can be heard"
                silentChunks > 8 ->
                    "● $time — no audio? check speaker volume"
                else -> "● Recording call $time (speakerphone on)"
            }
        } else {
            if (silentChunks > 12) "● Recording $time — very quiet, move closer"
            else "● Recording meeting $time"
        }
    }

    // ---- foreground / notification plumbing ----

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION") stopForeground(false)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Live recording status" }
            )
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (mode == MODE_CALL) "Call transcriber" else "Meeting transcriber")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(ongoing)
            .setContentIntent(openIntent)
            .apply {
                if (recording) addAction(
                    android.R.drawable.ic_media_pause, "Stop & transcribe", stopIntent
                )
            }
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, ongoing = recording))
    }

    override fun onDestroy() {
        if (recording) stopRecordingInternal(transcribe = false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
