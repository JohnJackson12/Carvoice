package com.carvoice.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires once when the device finishes booting. Only actually does
 * anything if "Auto-open when the car turns on" is enabled in Settings -
 * otherwise this receiver still exists (it's declared in the manifest
 * unconditionally, since a receiver can't be registered/unregistered
 * based on a preference) but is a no-op.
 *
 * Launches MainActivity itself (not just the VoiceService directly) so
 * the exact same startup path runs either way - permission checks,
 * loading the cached library instantly, then resuming last playback -
 * rather than duplicating that logic here. The screen may still be
 * locked/off at this point; that's fine, the foreground service starts
 * and audio begins regardless of whether the screen is visible yet. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.autoStartOnBoot(context)) return
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launchIntent)
    }
}
