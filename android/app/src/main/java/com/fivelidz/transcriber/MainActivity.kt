package com.fivelidz.transcriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Control panel with three capture modes:
 *   1. Record meeting (in-person) — mic capture + diarization
 *   2. Record call (speakerphone)  — acoustic call capture (requires speaker on)
 *   3. Auto-transcribe call recordings — watch the OEM call-rec folder
 * Plus manual file transcription, permission flow, and status.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var settings: Settings
    private lateinit var recordMeetingBtn: Button
    private lateinit var recordCallBtn: Button

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { transcribePickedFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Phone Transcript Recorder"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "On-device transcription + speaker labels"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        // Prominent help button
        root.addView(button("\u2753  How to use this app") { showTutorial() })

        status = TextView(this).apply { textSize = 13f; setPadding(0, 24, 0, 24) }

        // Show the tutorial automatically on first launch.
        if (settings.firstRun) {
            settings.firstRun = false
            root.post { showTutorial() }
        }

        // --- Live recording ---
        root.addView(sectionLabel("Record now"))
        recordMeetingBtn = button("\uD83C\uDF99  Record in-person meeting") { toggleMeeting() }
        recordCallBtn = button("\uD83D\uDCDE  Record call (speakerphone)") { toggleCall() }
        root.addView(recordMeetingBtn)
        root.addView(recordCallBtn)

        // --- Automatic call recordings ---
        root.addView(sectionLabel("Automatic call recordings (OEM recorder)"))
        root.addView(button("Start watching call-rec folder") {
            settings.autoWatch = true
            TranscriberService.startWatching(this)
            toast("Watching for new call recordings")
            refresh()
        })

        // --- Settings & utilities ---
        root.addView(sectionLabel("Settings & tools"))
        root.addView(button("Toggle speaker labels (diarization)") {
            settings.diarizationEnabled = !settings.diarizationEnabled
            toast("Speaker labels: ${if (settings.diarizationEnabled) "ON" else "OFF"}")
            refresh()
        })
        root.addView(button("Transcribe an audio file\u2026") {
            pickAudio.launch(arrayOf("audio/*"))
        })
        root.addView(button("Grant permissions") { requestRuntimePerms() })
        root.addView(button("Grant all-files access (for call-rec folder)") { requestAllFiles() })

        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---- recording toggles ----

    private fun toggleMeeting() {
        if (!ensureMic()) return
        if (RecorderService.isRecording) {
            RecorderService.stopRecording(this); toast("Stopping & transcribing\u2026")
        } else {
            promptTitle("Meeting title", "Meeting") { title ->
                RecorderService.startRecording(this, RecorderService.MODE_MEETING, title)
                toast("Recording meeting — tap again to stop")
                refresh()
            }
        }
        refresh()
    }

    private fun toggleCall() {
        if (!ensureMic()) return
        if (RecorderService.isRecording) {
            RecorderService.stopRecording(this); toast("Stopping & transcribing\u2026")
        } else {
            AlertDialog.Builder(this)
                .setTitle("Record call")
                .setMessage(
                    "To transcribe a call, put the call on SPEAKERPHONE so the microphone can " +
                    "hear both sides.\n\nAndroid does not allow apps to tap call audio directly, " +
                    "so speakerphone is required. Start the call, enable speaker, then record."
                )
                .setPositiveButton("Start recording") { _, _ ->
                    RecorderService.startRecording(this, RecorderService.MODE_CALL, null)
                    toast("Recording — make sure speakerphone is ON")
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refresh()
    }

    private fun promptTitle(hint: String, default: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(default)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(hint)
            .setView(input)
            .setPositiveButton("Record") { _, _ ->
                onOk(input.text.toString().ifBlank { default })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensureMic(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePerms()
            toast("Grant microphone permission, then try again")
            return false
        }
        return true
    }

    // ---- permissions ----

    private fun requestRuntimePerms() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPerms.launch(perms.toTypedArray())
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(
                        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } catch (e: Exception) {
                    startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else toast("Already granted")
        } else toast("Not needed on this Android version")
    }

    private fun transcribePickedFile(uri: Uri) {
        try {
            val name = (uri.lastPathSegment ?: "audio").substringAfterLast('/')
            val safe = name.ifBlank { "audio_${System.currentTimeMillis()}.m4a" }
            val dest = File(cacheDir, safe)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            TranscriberService.transcribeFile(this, dest.absolutePath)
            toast("Transcribing ${dest.name}\u2026 check the notification & qalarc-notes")
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    // ---- tutorial ----

    private fun showTutorial() {
        val text = """
            WHAT THIS APP DOES
            Records calls and meetings, transcribes them on your phone (offline — no internet, no cloud), labels who spoke, and saves the result into qalarc-notes (and as a .txt file).

            ───────────────
            FIRST-TIME SETUP (do once)
            1. Tap "Grant permissions" → allow Microphone, Phone, Notifications.
            2. Tap "Grant all-files access" → enable it (needed to read call recordings).
            3. Make sure the speech model is present (see STATUS at the bottom — it should say ggml-base.en.bin found).

            ───────────────
            THREE WAYS TO RECORD

            🎙  IN-PERSON MEETING
            • Tap "Record in-person meeting", give it a title, and place the phone near the speakers.
            • Tap "Stop & transcribe" when done.
            • Best results: quiet room, phone on the table between people.

            📞  PHONE CALL (live)
            • Start your phone call FIRST, then turn ON SPEAKERPHONE.
            • Tap "Record call (speakerphone)" → "Start recording".
            • Why speakerphone? Android does NOT let apps tap call audio directly. The only legal way is to let the microphone hear the call out loud. The notification will WARN you if the speaker is off or it can't hear anything.
            • Tap "Stop & transcribe" (or the Stop button in the notification) to finish.

            📂  AUTOMATIC (call recorder)
            • If your phone's built-in Call Recording is turned on (Phone app → Settings → Call recording), tap "Start watching call-rec folder".
            • Every recorded call is then transcribed automatically — highest quality, no speakerphone needed.

            ───────────────
            SPEAKER LABELS
            • "Speaker labels (diarization)" automatically figures out who spoke when (Speaker 1, Speaker 2…). Leave it ON. It runs on the phone, offline.

            ───────────────
            WHERE YOUR TRANSCRIPTS GO
            • qalarc-notes app → "ai_inbox" folder (with speaker labels & timestamps).
            • A plain-text copy in: Documents/Transcripts/*.txt
            • A markdown copy in: Documents/qalarc-notes/notes/*.md

            ───────────────
            TIPS
            • The first transcription after opening the app is slowest (the model loads once).
            • Use "Transcribe an audio file…" to transcribe any existing recording.
            • Everything stays on your phone. Nothing is uploaded.
        """.trimIndent()

        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(56, 40, 56, 24)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("How to use Transcriber")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Got it", null)
            .show()
    }

    // ---- UI helpers ----

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        setPadding(0, 28, 0, 8)
        alpha = 0.6f
    }

    private fun refresh() {
        // Update record button labels to reflect live state
        if (RecorderService.isRecording) {
            recordMeetingBtn.text = "\u23F9  Stop & transcribe"
            recordCallBtn.text = "\u23F9  Stop & transcribe"
        } else {
            recordMeetingBtn.text = "\uD83C\uDF99  Record in-person meeting"
            recordCallBtn.text = "\uD83D\uDCDE  Record call (speakerphone)"
        }

        val sb = StringBuilder()
        sb.append("STATUS\n")
        sb.append("Recording: ").append(if (RecorderService.isRecording) "YES (live)" else "no").append('\n')
        sb.append("Speaker labels: ").append(if (settings.diarizationEnabled) "ON" else "OFF").append('\n')
        sb.append("Auto-watch call folder: ").append(if (settings.autoWatch) "ON" else "OFF").append('\n')

        sb.append("\nPERMISSIONS\n")
        sb.append("  Microphone: ").append(granted(Manifest.permission.RECORD_AUDIO)).append('\n')
        val mediaPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        sb.append("  Audio read: ").append(granted(mediaPerm)).append('\n')
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else true
        sb.append("  All-files access: ").append(if (allFiles) "YES" else "NO").append('\n')

        sb.append("\nMODEL\n")
        val model = ModelManager.findModel(this, settings.modelName)
        if (model != null) sb.append("  ").append(model.name).append(" (")
            .append(model.length() / (1024 * 1024)).append(" MB)\n")
        else sb.append("  NOT FOUND — push to /sdcard/Download/${settings.modelName}\n")

        status.text = sb.toString()
    }

    private fun granted(p: String) =
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED)
            "YES" else "NO"

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
