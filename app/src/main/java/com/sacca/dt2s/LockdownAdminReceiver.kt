package com.sacca.dt2s

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit

/** Holds the force-lock device admin policy used by four-tap lockdown. */
class LockdownAdminReceiver : DeviceAdminReceiver() {

    // Revoking admin (in-app or in system settings) turns the gesture off, so granting it
    // again later does not silently re-enable it.
    override fun onDisabled(context: Context, intent: Intent) {
        context.getSharedPreferences(LockTapService.PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(LockTapService.PREF_LOCKDOWN_TAPS, false)
        }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, LockdownAdminReceiver::class.java)
    }
}
