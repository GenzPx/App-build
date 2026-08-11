package com.monitorcheck.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.monitorcheck.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts background monitoring after a reboot, but only if the user had explicitly
 * enabled it. If the setting is off, this receiver does nothing at all.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).settings.first()
                if (settings.backgroundMonitoring) {
                    MonitoringService.start(appContext)
                }
            } catch (_: Throwable) {
                // Never crash on boot.
            } finally {
                pending.finish()
            }
        }
    }
}
