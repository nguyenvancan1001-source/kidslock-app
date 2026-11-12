package vn.vicomart.kidslock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import vn.vicomart.kidslock.KidsLockApp
import vn.vicomart.kidslock.Prefs
import vn.vicomart.kidslock.R

/**
 * Android does not let a camera service start by itself after a reboot (Android 14+ requires
 * the app to be open). We remind the parent to open Kids Lock, which restarts protection.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs(context).protectionEnabled) return
        KidsLockApp.notifyAlert(context, R.string.alert_boot_title, R.string.alert_boot_text)
    }
}
