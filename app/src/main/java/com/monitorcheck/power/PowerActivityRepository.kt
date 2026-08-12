package com.monitorcheck.power

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.monitorcheck.core.Permissions
import com.monitorcheck.core.Reading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppActivityUsage(val packageName:String,val label:String,val foregroundMs:Long,val lastUsedTime:Long)
data class PowerActivitySnapshot(val deviceIdle:Reading<String>,val powerSave:Reading<String>,val ownBatteryOptimization:Reading<String>,val ownStandbyBucket:Reading<String>,val apps:List<AppActivityUsage>,val note:String)
class PowerActivityRepository(private val context:Context){
 suspend fun snapshot():PowerActivitySnapshot=withContext(Dispatchers.IO){
  val p=context.getSystemService(Context.POWER_SERVICE) as? PowerManager
  val idle=if(p==null) Reading.unavailable("PowerManager unavailable") else runCatching { if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) Reading.available(if(p.isDeviceIdleMode) "Doze idle" else "Not idle","PowerManager") else Reading.unsupported("Doze requires Android 6.0+") }.getOrElse { Reading.error(it.message) }
  val save=if(p==null) Reading.unavailable("PowerManager unavailable") else runCatching { Reading.available(if(p.isPowerSaveMode) "Power saver on" else "Power saver off","PowerManager") }.getOrElse { Reading.error(it.message) }
  val opt=if(p==null||Build.VERSION.SDK_INT<Build.VERSION_CODES.M) Reading.unsupported("Battery optimization requires Android 6.0+") else runCatching { Reading.available(if(p.isIgnoringBatteryOptimizations(context.packageName)) "Excluded from Doze" else "Optimized by Doze","PowerManager") }.getOrElse { Reading.restricted("The system refused the optimization query") }
  val usm=context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
  if(usm==null||!Permissions.hasUsageStats(context)) return@withContext PowerActivitySnapshot(idle,save,opt,Reading.permission("Grant Usage Access to inspect app activity"),emptyList(),"Usage Access is required for app activity and standby information.")
  val end=System.currentTimeMillis(); val stats=runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,end-24*60*60_000L,end).orEmpty().filter { it.totalTimeInForeground>0 }.sortedByDescending { it.totalTimeInForeground }.take(50) }.getOrDefault(emptyList())
  val apps=stats.map { s -> val label=runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(s.packageName,0)).toString() }.getOrDefault(s.packageName); AppActivityUsage(s.packageName,label,s.totalTimeInForeground,s.lastTimeUsed) }
  val bucket=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P) runCatching { Reading.available(when(usm.appStandbyBucket){UsageStatsManager.STANDBY_BUCKET_ACTIVE->"Active";UsageStatsManager.STANDBY_BUCKET_WORKING_SET->"Working set";UsageStatsManager.STANDBY_BUCKET_FREQUENT->"Frequent";UsageStatsManager.STANDBY_BUCKET_RARE->"Rare";UsageStatsManager.STANDBY_BUCKET_RESTRICTED->"Restricted";else->"Bucket ${usm.appStandbyBucket}"},"UsageStatsManager") }.getOrElse { Reading.error(it.message) } else Reading.unsupported("App standby buckets require Android 9+")
  PowerActivitySnapshot(idle,save,opt,bucket,apps,"Android does not expose a complete per-app wakelock history to normal third-party apps.")
 }
}
