package com.fivelidz.transcriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Foreground service that:
 *   - watches the HyperOS native call-recording folder(s) for new audio files, and
 *   - transcribes each new file on-device with whisper, then writes a qalarc note.
 *
 * It can also be asked to transcribe a specific file via ACTION_TRANSCRIBE_FILE
 * (used by the UI "Transcribe a file" button and for testing).
 */
class TranscriberService : Service() {

    companion object {
        private const val TAG = "TranscriberService"
        private const val CHANNEL_ID = "transcriber"
        private const val NOTIF_ID = 42

        const val ACTION_START_WATCH = "com.fivelidz.transcriber.START_WATCH"
        const val ACTION_TRANSCRIBE_FILE = "com.fivelidz.transcriber.TRANSCRIBE_FILE"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_CONTACT = "contact"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_DIRECTION = "direction"

        fun startWatching(ctx: Context) {
            val i = Intent(ctx, TranscriberService::class.java).setAction(ACTION_START_WATCH)
            ContextStartFg(ctx, i)
        }

        fun transcribeFile(ctx: Context, path: String, contact: String? = null,
                           number: String? = null, direction: String? = null) {
            val i = Intent(ctx, TranscriberService::class.java)
                .setAction(ACTION_TRANSCRIBE_FILE)
                .putExtra(EXTRA_FILE_PATH, path)
                .putExtra(EXTRA_CONTACT, contact)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_DIRECTION, direction)
            ContextStartFg(ctx, i)
        }

        @Suppress("FunctionName")
        private fun ContextStartFg(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processMutex = Mutex()
    private val observers = mutableListOf<FileObserver>()
    private lateinit var settings: Settings
    private lateinit var pipeline: TranscriptionPipeline

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        pipeline = TranscriptionPipeline(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Idle \u2014 watching for call recordings"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRANSCRIBE_FILE -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH)
                if (path != null) {
                    handleFile(
                        File(path),
                        intent.getStringExtra(EXTRA_CONTACT),
                        intent.getStringExtra(EXTRA_NUMBER),
                        intent.getStringExtra(EXTRA_DIRECTION),
                        forced = true,
                    )
                }
            }
            else -> startWatchers()
        }
        return START_STICKY
    }

    private fun startWatchers() {
        if (observers.isNotEmpty()) return
        var watching = 0
        for (path in Settings.CALL_REC_DIRS) {
            val dir = File(path)
            if (!dir.exists()) continue
            val obs = makeObserver(dir)
            obs.startWatching()
            observers.add(obs)
            watching++
            Log.i(TAG, "Watching $path")
            // Catch up on any files that appeared before the service started.
            dir.listFiles()?.forEach { f -> maybeHandle(f) }
        }
        updateNotification(
            if (watching > 0) "Watching $watching call-rec folder(s)"
            else "No call-rec folder found \u2014 use manual transcription"
        )
    }

    @Suppress("DEPRECATION")
    private fun makeObserver(dir: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    path ?: return
                    maybeHandle(File(dir, path))
                }
            }
        } else {
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    path ?: return
                    maybeHandle(File(dir, path))
                }
            }
        }
    }

    private fun maybeHandle(f: File) {
        if (!f.isFile) return
        val ext = f.extension.lowercase()
        if (ext !in Settings.AUDIO_EXTENSIONS) return
        if (settings.isProcessed(f.absolutePath)) return
        // Try to glean a phone number from the filename. Prefer an international "+..." form;
        // otherwise take a standalone 7-15 digit run that isn't an 8-digit date (yyyymmdd) or
        // a 6-digit time. HyperOS call recordings are typically named
        // "<contact_or_number>_yyyymmdd_hhmmss.<ext>".
        val number = Regex("\\+\\d{6,15}").find(f.name)?.value
            ?: Regex("(?<!\\d)\\d{7,15}(?!\\d)").findAll(f.name)
                .map { it.value }
                .filterNot { it.length == 8 && it.startsWith("20") } // skip yyyymmdd date
                .firstOrNull()
        handleFile(f, contact = null, number = number, direction = null, forced = false)
    }

    private fun handleFile(f: File, contact: String?, number: String?,
                           direction: String?, forced: Boolean) {
        scope.launch {
            processMutex.withLock {
                if (!forced && settings.isProcessed(f.absolutePath)) return@withLock
                // Wait briefly for the recorder to finish writing the file.
                waitForStableSize(f)
                updateNotification("Transcribing ${f.name}\u2026")
                val ok = pipeline.process(
                    audioFile = f,
                    kind = TranscriptionPipeline.Kind.CALL,
                    contact = contact,
                    number = number,
                    direction = direction,
                    modelName = settings.modelName,
                    enableDiarization = settings.diarizationEnabled,
                ) { p ->
                    when (p) {
                        is TranscriptionPipeline.Progress.Transcribing ->
                            updateNotification("Transcribing ${f.name} (${p.chunk}/${p.totalChunks})")
                        is TranscriptionPipeline.Progress.Diarizing ->
                            updateNotification("Identifying speakers ${(p.fraction * 100).toInt()}%\u2026")
                        is TranscriptionPipeline.Progress.Done ->
                            updateNotification("Saved: ${p.noteTitle}")
                        is TranscriptionPipeline.Progress.Error ->
                            updateNotification("Error: ${p.message}")
                        else -> {}
                    }
                }
                if (ok) settings.markProcessed(f.absolutePath)
            }
        }
    }

    /** Wait until the file size stops changing (recorder finished writing). */
    private suspend fun waitForStableSize(f: File) {
        var last = -1L
        repeat(30) {
            val size = f.length()
            if (size > 0 && size == last) return
            last = size
            kotlinx.coroutines.delay(1000)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, "Transcriber", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Call-recording transcription status" }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Transcript Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
