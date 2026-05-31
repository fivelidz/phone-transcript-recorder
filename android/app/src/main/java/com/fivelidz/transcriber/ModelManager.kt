package com.fivelidz.transcriber

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Locates the whisper ggml model on disk. The model is NOT bundled in the APK (it's ~142 MB)
 * — instead it is pushed to one of the known locations below at install time:
 *
 *   1. App files dir:        /data/data/<pkg>/files/models/ggml-base.en.bin   (preferred)
 *   2. App external files:   /sdcard/Android/data/<pkg>/files/models/ggml-base.en.bin
 *   3. Public Download:      /sdcard/Download/ggml-base.en.bin
 *
 * Default model name can be overridden in settings (base/small/tiny).
 */
object ModelManager {
    const val DEFAULT_MODEL = "ggml-base.en.bin"

    fun candidatePaths(ctx: Context, modelName: String = DEFAULT_MODEL): List<File> {
        val list = ArrayList<File>()
        list.add(File(File(ctx.filesDir, "models"), modelName))
        ctx.getExternalFilesDir("models")?.let { list.add(File(it, modelName)) }
        list.add(File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), modelName))
        return list
    }

    /** First existing model file, or null if none found. */
    fun findModel(ctx: Context, modelName: String = DEFAULT_MODEL): File? =
        candidatePaths(ctx, modelName).firstOrNull { it.exists() && it.length() > 1_000_000 }
}
