package com.fivelidz.transcriber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the folder-watcher service after a reboot, if auto-watch is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Settings(context).autoWatch) {
                TranscriberService.startWatching(context)
            }
        }
    }
}
