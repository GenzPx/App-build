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
 * Runs after a reboot:
 *  - restarts background monitoring, but only if the user had explicitly enabled it;
 *  - reschedules the widget refresh alarm when home-screen widgets exist
 *    (alarms do not survive a reboot on their own).
 * If neither applies, this receiver does nothing at all.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (com.monitorcheck.widget.WidgetRefreshScheduler.hasWidgets(appContext)) {
                    com.monitorcheck.widget.WidgetRefreshScheduler.schedule(appContext)
                }
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
