package com.monitorcheck.apps

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import com.monitorcheck.core.Permissions
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ProcessEntry(
    val pid: Int,
    val uid: Int?,
    val name: String,
    val packageName: String?,
    val importance: String,
    val importanceValue: Int,
    val memoryPssBytes: Long?,
    val threadCount: Int?,
    val state: String?,
    val isOwnProcess: Boolean
)

data class ServiceEntry(
    val name: String,
    val packageName: String,
    val pid: Int,
    val foreground: Boolean,
    val activeSince: Long,
    val clientCount: Int
)

class ProcessRepository(private val context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    val isRestricted: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    val restrictionNote: String = if (isRestricted) {
        "Android 8.0 and later restrict getRunningAppProcesses() to the calling app's own " +
            "processes, and /proc entries for other UIDs are hidden by the kernel (hidepid). " +
            "Monitored Check shows every process it is genuinely allowed to see, plus app " +
            "activity derived from Usage Access when you grant it. It does not bypass these " +
            "protections."
    } else {
        "This Android version still reports the full running-process list to apps."
    }

    suspend fun runningProcesses(): List<ProcessEntry> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<Int, ProcessEntry>()

        try {
            activityManager?.runningAppProcesses?.forEach { info ->
                val pss = try {
                    activityManager.getProcessMemoryInfo(intArrayOf(info.pid))
                        ?.firstOrNull()?.totalPss?.toLong()?.times(1024)
                } catch (_: Throwable) { null }
                out[info.pid] = ProcessEntry(
                    pid = info.pid,
                    uid = info.uid,
                    name = info.processName,
                    packageName = info.pkgList?.firstOrNull(),
                    importance = importanceLabel(info.importance),
                    importanceValue = info.importance,
                    memoryPssBytes = pss,
                    threadCount = readThreadCount(info.pid),
                    state = readProcState(info.pid),
                    isOwnProcess = info.uid == Process.myUid()
                )
            }
        } catch (_: Throwable) {  }

        try {
            File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
                ?.forEach { dir ->
                    val pid = dir.name.toIntOrNull() ?: return@forEach
                    if (out.containsKey(pid)) return@forEach
                    val cmdline = SysFs.readText("${dir.absolutePath}/cmdline", false)
                        ?.replace('\u0000', ' ')?.trim()
                    val comm = SysFs.readFirstLine("${dir.absolutePath}/comm")
                    val name = cmdline?.takeIf { it.isNotBlank() } ?: comm ?: return@forEach
                    out[pid] = ProcessEntry(
                        pid = pid,
                        uid = readUid(pid),
                        name = name,
                        packageName = null,
                        importance = "Visible via /proc",
                        importanceValue = Int.MAX_VALUE,
                        memoryPssBytes = readRssBytes(pid),
                        threadCount = readThreadCount(pid),
                        state = readProcState(pid),
                        isOwnProcess = pid == Process.myPid()
                    )
                }
        } catch (_: Throwable) {  }

        out.values.sortedWith(
            compareBy<ProcessEntry> { it.importanceValue }.thenByDescending { it.memoryPssBytes ?: 0 }
        )
    }

    @Suppress("DEPRECATION")
    suspend fun runningServices(): Reading<List<ServiceEntry>> = withContext(Dispatchers.IO) {
        try {
            val services = activityManager?.getRunningServices(200)
            if (services.isNullOrEmpty()) {
                return@withContext if (isRestricted) Reading.limited(
                    emptyList(),
                    "Android 8+ returns only this app's own services from getRunningServices()",
                    "ActivityManager"
                ) else Reading.unavailable("No services reported")
            }
            val list = services.map {
                ServiceEntry(
                    name = it.service.className,
                    packageName = it.service.packageName,
                    pid = it.pid,
                    foreground = it.foreground,
                    activeSince = it.activeSince,
                    clientCount = it.clientCount
                )
            }
            if (isRestricted) Reading.limited(list,
                "Restricted by Android 8+ to this app's own services", "ActivityManager")
            else Reading.available(list, "ActivityManager.getRunningServices")
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    suspend fun recentAppActivity(): Reading<List<Pair<String, Long>>> = withContext(Dispatchers.IO) {
        if (!Permissions.hasUsageStats(context)) {
            return@withContext Reading.permission(
                "Grant Usage Access to see which apps have been active recently")
        }
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 86_400_000L, end)
            if (stats.isNullOrEmpty()) return@withContext Reading.unavailable("No usage data available")
            Reading.available(
                stats.filter { it.lastTimeUsed > 0 }
                    .sortedByDescending { it.lastTimeUsed }
                    .take(100)
                    .map { it.packageName to it.lastTimeUsed },
                "UsageStatsManager"
            )
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    suspend fun foregroundApp(): Reading<String> = withContext(Dispatchers.IO) {
        if (!Permissions.hasUsageStats(context)) {
            return@withContext Reading.permission("Requires Usage Access")
        }
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - 60_000, end)
            val top = stats?.maxByOrNull { it.lastTimeUsed }
            if (top == null) Reading.unavailable("No foreground app reported")
            else Reading.available(top.packageName, "UsageStatsManager")
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    fun forceStopCapability(packageName: String): Reading<String> = when {
        packageName == context.packageName ->
            Reading.available("Monitored Check can stop its own background service", "Self")
        else -> Reading.restricted(
            "Force stopping another app requires the system-only FORCE_STOP_PACKAGES permission. " +
                "Open App Info to stop it through the system UI."
        )
    }

    fun ownMemoryInfo(): Reading<Debug.MemoryInfo> = try {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        Reading.available(mi, "Debug.getMemoryInfo")
    } catch (t: Throwable) {
        Reading.error(t.message)
    }

    private fun readThreadCount(pid: Int): Int? =
        SysFs.readLines("/proc/$pid/status")
            ?.firstOrNull { it.startsWith("Threads:") }
            ?.substringAfter(":")?.trim()?.toIntOrNull()
            ?: runCatching { File("/proc/$pid/task").list()?.size }.getOrNull()

    private fun readUid(pid: Int): Int? =
        SysFs.readLines("/proc/$pid/status")
            ?.firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()

    private fun readProcState(pid: Int): String? =
        SysFs.readLines("/proc/$pid/status")
            ?.firstOrNull { it.startsWith("State:") }
            ?.substringAfter(":")?.trim()

    private fun readRssBytes(pid: Int): Long? =
        SysFs.readLines("/proc/$pid/status")
            ?.firstOrNull { it.startsWith("VmRSS:") }
            ?.replace(Regex("[^0-9]"), "")?.toLongOrNull()?.times(1024)

    private fun importanceLabel(v: Int) = when (v) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "Foreground service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "Perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "Cached / background"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "Gone"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "Top (sleeping)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "Can't save state"
        else -> "Importance $v"
    }
}
