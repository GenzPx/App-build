package com.monitorcheck.widget

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.monitorcheck.core.Fmt

object WidgetRefreshScheduler {

    private const val REQUEST_CODE = 4201
    const val ACTION_REFRESH = "com.monitorcheck.action.WIDGET_REFRESH"

    private const val INTERVAL_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun schedule(context: Context) {
        runCatching {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pendingIntent(context)
            )
        }
    }

    fun cancel(context: Context) {
        runCatching {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarm.cancel(pendingIntent(context))
        }
    }

    fun hasWidgets(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, MonitorWidgetProvider::class.java))
            .isNotEmpty()
    }.getOrDefault(false)
}

class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetRefreshScheduler.ACTION_REFRESH) return
        if (!WidgetRefreshScheduler.hasWidgets(context)) {
            WidgetRefreshScheduler.cancel(context)
            return
        }
        runCatching { refresh(context.applicationContext) }
    }

    private fun refresh(context: Context) {
        val previous = MonitorWidgetProvider.peek(context)
        val now = System.currentTimeMillis()

        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val battery = batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) "${level * 100 / scale}%" else null
        } ?: previous.battery
        val batteryTemp = batteryIntent?.let {
            val tenths = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != Int.MIN_VALUE) Fmt.temperature(tenths / 10.0) else null
        } ?: previous.temperature

        val ram = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            if (info.totalMem > 0)
                Fmt.percent((info.totalMem - info.availMem) * 100.0 / info.totalMem, 0)
            else null
        }.getOrNull() ?: previous.ram

        val engineFresh = previous.updatedAt > 0 && (now - previous.updatedAt) < 10 * 60_000L
        val cpu = if (engineFresh) previous.cpu else "Monitor off"
        val network = if (engineFresh) previous.network else "Open app for live network speed"

        MonitorWidgetProvider.updateAll(
            context,
            MonitorWidgetProvider.WidgetSnapshot(
                cpu = cpu, ram = ram, battery = battery,
                temperature = batteryTemp, network = network, updatedAt = now
            )
        )
    }
}
