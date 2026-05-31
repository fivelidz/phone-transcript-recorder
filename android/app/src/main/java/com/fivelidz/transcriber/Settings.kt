package com.fivelidz.transcriber

import android.content.Context

/** Simple SharedPreferences-backed settings. */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("transcriber", Context.MODE_PRIVATE)

    var autoWatch: Boolean
        get() = prefs.getBoolean("auto_watch", true)
        set(v) = prefs.edit().putBoolean("auto_watch", v).apply()

    var modelName: String
        get() = prefs.getString("model_name", ModelManager.DEFAULT_MODEL) ?: ModelManager.DEFAULT_MODEL
        set(v) = prefs.edit().putString("model_name", v).apply()

    /** Run on-device speaker diarization (Speaker A/B labels). */
    var diarizationEnabled: Boolean
        get() = prefs.getBoolean("diarization", true)
        set(v) = prefs.edit().putBoolean("diarization", v).apply()

    /** First launch — show the tutorial automatically. */
    var firstRun: Boolean
        get() = prefs.getBoolean("first_run", true)
        set(v) = prefs.edit().putBoolean("first_run", v).apply()

    /** Track files we've already transcribed so we never double-process. */
    fun isProcessed(path: String): Boolean = prefs.getStringSet("processed", emptySet())!!.contains(path)

    fun markProcessed(path: String) {
        val set = HashSet(prefs.getStringSet("processed", emptySet())!!)
        set.add(path)
        prefs.edit().putStringSet("processed", set).apply()
    }

    companion object {
        // Known HyperOS / MediaTek native call-recording output folders. Watched in order.
        val CALL_REC_DIRS = listOf(
            "/sdcard/MIUI/sound_recorder/call_rec",
            "/storage/emulated/0/MIUI/sound_recorder/call_rec",
            "/sdcard/Recordings/Call",
            "/sdcard/Sounds/CallRecord",
        )
        val AUDIO_EXTENSIONS = listOf("mp3", "m4a", "aac", "amr", "wav", "ogg", "3gp")
    }
}
