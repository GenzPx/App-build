package com.monitorcheck.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.monitorcheck.MainActivity
import com.monitorcheck.R
import com.monitorcheck.core.AppSettings
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.NotificationStyle
import com.monitorcheck.core.SettingsRepository
import com.monitorcheck.data.battery.BatteryHistoryStore
import com.monitorcheck.data.battery.BatteryRepository
import com.monitorcheck.hardware.cpu.CpuRepository
import com.monitorcheck.hardware.memory.MemoryRepository
import com.monitorcheck.hardware.thermal.ThermalRepository
import com.monitorcheck.network.NetworkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Optional foreground monitoring service.
 *
 * Disabled by default. Started only when the user explicitly enables background
 * monitoring. It samples at the user's chosen interval (never faster), writes battery
 * history locally, and updates one ongoing notification. Nothing is transmitted.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var cpuRepo: CpuRepository
    private lateinit var memoryRepo: MemoryRepository
    private lateinit var batteryRepo: BatteryRepository
    private lateinit var networkRepo: NetworkRepository
    private lateinit var thermalRepo: ThermalRepository
    private lateinit var historyStore: BatteryHistoryStore

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        cpuRepo = CpuRepository()
        memoryRepo = MemoryRepository(applicationContext)
        batteryRepo = BatteryRepository(applicationContext)
        networkRepo = NetworkRepository(applicationContext)
        thermalRepo = ThermalRepository(applicationContext)
        historyStore = BatteryHistoryStore(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification("Starting monitoring…", null))
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        var tick = 0L
        while (scope.isActive) {
            val settings = try { settingsRepo.settings.first() } catch (_: Throwable) { AppSettings() }
            // Background sampling is deliberately slower than foreground: at least 5s,
            // and 2x the configured interval in low resource mode.
            val interval = maxOf(
                5_000L,
                if (settings.lowResourceMode) settings.refreshIntervalMs * 2 else settings.refreshIntervalMs
            )

            val text = try { collect(settings) } catch (_: Throwable) { null }
            if (text != null) {
                notificationManager().notify(NOTIFICATION_ID, buildNotification(text.first, text.second))
            }

            // Prune the history DB roughly once an hour, not every tick.
            tick++
            if (tick % 720 == 0L) runCatching { historyStore.prune() }

            delay(interval)
        }
    }

    /** Collects one sample and formats the notification text. Returns title/body. */
    private suspend fun collect(settings: AppSettings): Pair<String, String?> {
        val parts = ArrayList<String>()
        val detail = StringBuilder()

        if (settings.notifyCpu) {
            val usage = cpuRepo.sampleUsage()
            val cpuText = usage.totalPercent.display { Fmt.percent(it, 0) }
            parts.add("CPU $cpuText")
            if (settings.notificationStyle == NotificationStyle.DETAILED) {
                val maxFreq = usage.cores.mapNotNull { it.currentKHz }.maxOrNull()
                detail.append("CPU: $cpuText")
                maxFreq?.let { detail.append(" @ ${Fmt.freqKHz(it)}") }
                usage.loadAverage.value?.let {
                    detail.append("  load ${it.first}/${it.second}/${it.third}")
                }
                detail.append('\n')
            }
        }

        if (settings.notifyRam) {
            val mem = memoryRepo.snapshot()
            val ramText = mem.display { Fmt.percent(it.usedPercent, 0) }
            parts.add("RAM $ramText")
            if (settings.notificationStyle == NotificationStyle.DETAILED) {
                mem.value?.let {
                    detail.append("RAM: ${Fmt.bytes(it.usedBytes)} / ${Fmt.bytes(it.totalBytes)}\n")
                }
            }
        }

        if (settings.notifyBattery) {
            val bat = batteryRepo.snapshot()
            parts.add("BAT ${bat.display { "${it.levelPercent}%" }}")
            bat.value?.let { snap ->
                if (settings.batteryHistoryEnabled) runCatching { historyStore.record(snap) }
                if (settings.notificationStyle == NotificationStyle.DETAILED) {
                    detail.append("Battery: ${snap.levelPercent}% ${snap.status}")
                    snap.temperatureCelsius?.let { detail.append("  ${Fmt.temperature(it)}") }
                    snap.currentNowUa?.let { detail.append("  ${Fmt.currentMa(it)}") }
                    detail.append('\n')
                }
            }
        }

        if (settings.notifyNetwork) {
            val net = networkRepo.sampleThroughput()
            if (net != null && net.elapsedMs > 0) {
                parts.add("NET ↓${Fmt.bytesPerSecond(net.rxRateBps)}")
                if (settings.notificationStyle == NotificationStyle.DETAILED) {
                    detail.append("Network: ↓${Fmt.bytesPerSecond(net.rxRateBps)} " +
                        "↑${Fmt.bytesPerSecond(net.txRateBps)}\n")
                }
            }
        }

        if (settings.notifyTemperature) {
            val hottest = thermalRepo.hottestZone()
            hottest.value?.let {
                parts.add("TMP ${Fmt.temperature(it.celsius)}")
                if (settings.notificationStyle == NotificationStyle.DETAILED) {
                    detail.append("Hottest: ${it.type} ${Fmt.temperature(it.celsius)}\n")
                }
            }
        }

        val title = when (settings.notificationStyle) {
            NotificationStyle.MINIMAL -> parts.take(2).joinToString("  ")
            else -> parts.joinToString("  |  ")
        }.ifBlank { "Monitoring active" }

        return title to detail.toString().trim().ifBlank { null }
    }

    private fun buildNotification(text: String, detail: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MonitoringService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stopIntent)

        if (!detail.isNullOrBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            // If the OS refuses (e.g. missing notification permission), stop cleanly
            // rather than crashing.
            stopSelf()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitoring_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.monitoring_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "monitored_check_monitoring"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.monitorcheck.action.STOP_MONITORING"

        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) { /* start may be blocked from background */ }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, MonitoringService::class.java)) }
            catch (_: Throwable) { }
        }
    }
}
