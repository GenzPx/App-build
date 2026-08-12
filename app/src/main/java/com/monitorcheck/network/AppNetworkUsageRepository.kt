package com.monitorcheck.network

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.monitorcheck.core.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppNetworkUsage(val uid: Int, val packageName: String, val label: String, val wifiBytes: Long, val mobileBytes: Long) { val totalBytes: Long get() = wifiBytes + mobileBytes }
enum class UsageRange(val label: String, val durationMs: Long) { TODAY("Today",24*60*60_000L), WEEK("7 days",7*24*60*60_000L), MONTH("30 days",30*24*60*60_000L) }
data class AppNetworkUsageResult(val entries: List<AppNetworkUsage>, val note: String? = null, val supported: Boolean = true)
class AppNetworkUsageRepository(private val context: Context) {
    private val pm=context.packageManager; private val manager=context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
    suspend fun query(range: UsageRange): AppNetworkUsageResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@withContext AppNetworkUsageResult(emptyList(),"Per-app network stats require Android 6.0+.",false)
        if (!Permissions.hasUsageStats(context)) return@withContext AppNetworkUsageResult(emptyList(),"Grant Usage Access to read per-app network totals.",false)
        if (manager == null) return@withContext AppNetworkUsageResult(emptyList(),"NetworkStatsManager is unavailable.",false)
        val end=System.currentTimeMillis(); val start=end-range.durationMs
        val wifi=runCatching { queryType(ConnectivityManager.TYPE_WIFI,start,end) }; val mobile=runCatching { queryType(ConnectivityManager.TYPE_MOBILE,start,end) }
        val note=buildString { if(wifi.isFailure) append("Wi-Fi totals unavailable. "); if(mobile.isFailure) append("Mobile totals unavailable. ") }.trim().ifBlank { null }
        val installed=runCatching { pm.getInstalledApplications(0).associateBy { it.uid } }.getOrDefault(emptyMap()); val uids=wifi.getOrDefault(emptyMap()).keys+mobile.getOrDefault(emptyMap()).keys
        val entries=uids.map { uid -> val app=installed[uid]; AppNetworkUsage(uid,app?.packageName ?: "uid:$uid",app?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: "UID $uid",wifi.getOrDefault(emptyMap())[uid] ?: 0L,mobile.getOrDefault(emptyMap())[uid] ?: 0L) }.filter { it.totalBytes>0 }.sortedByDescending { it.totalBytes }
        AppNetworkUsageResult(entries,note,true)
    }
    private fun queryType(type:Int,start:Long,end:Long):Map<Int,Long>{ val out=HashMap<Int,Long>(); val stats=manager!!.querySummary(type,null,start,end); val b=NetworkStats.Bucket(); try { while(stats.hasNextBucket()){stats.getNextBucket(b); if(b.uid>=0) out[b.uid]=(out[b.uid] ?: 0L)+b.rxBytes+b.txBytes} } finally { stats.close() }; return out }
}
