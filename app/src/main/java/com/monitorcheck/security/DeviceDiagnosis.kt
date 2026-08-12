package com.monitorcheck.security

import android.content.Context
import com.monitorcheck.apps.AppRepository
import com.monitorcheck.core.DataStatus
import com.monitorcheck.data.battery.BatteryRepository
import com.monitorcheck.hardware.memory.MemoryRepository
import com.monitorcheck.hardware.thermal.ThermalRepository
import com.monitorcheck.storage.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class DiagnosisSeverity(val label:String){INFO("Info"),WARNING("Warning"),CRITICAL("Critical")}
data class DiagnosisIssue(val severity:DiagnosisSeverity,val title:String,val detail:String,val route:String?=null)
data class DeviceDiagnosisResult(val issues:List<DiagnosisIssue>,val scannedApps:Int,val totalApps:Int,val durationMs:Long,val limitations:List<String>)
class DeviceDiagnosisEngine(private val context:Context){
 suspend fun run(onProgress:(String,Int,Int)->Unit={_,_,_->}):DeviceDiagnosisResult=withContext(Dispatchers.IO){
  val start=System.currentTimeMillis(); val issues=ArrayList<DiagnosisIssue>(); val limits=ArrayList<String>()
  val memory=MemoryRepository(context).snapshot(); val battery=BatteryRepository(context).snapshot(); val thermal=ThermalRepository(context).hottestZone(); val storage=StorageRepository(context).primaryTotals()
  memory.value?.let { if(it.usedPercent>=90) issues.add(DiagnosisIssue(DiagnosisSeverity.CRITICAL,"RAM pressure","Memory usage is ${it.usedPercent.toInt()}%.","memory")) else if(it.usedPercent>=80) issues.add(DiagnosisIssue(DiagnosisSeverity.WARNING,"High RAM usage","Memory usage is ${it.usedPercent.toInt()}%.","memory")) } ?: limits.add("RAM reading: ${memory.status.label}")
  thermal.value?.let { if(it.celsius>=60) issues.add(DiagnosisIssue(DiagnosisSeverity.CRITICAL,"High device temperature","Hottest zone is ${"%.1f".format(java.util.Locale.US,it.celsius)}°C.","thermal")) else if(it.celsius>=45) issues.add(DiagnosisIssue(DiagnosisSeverity.WARNING,"Elevated device temperature","Hottest zone is ${"%.1f".format(java.util.Locale.US,it.celsius)}°C.","thermal")) } ?: limits.add("Thermal reading: ${thermal.status.label}")
  battery.value?.let { b -> if(!b.isCharging&&b.levelPercent<=15) issues.add(DiagnosisIssue(DiagnosisSeverity.WARNING,"Battery is low","Battery is ${b.levelPercent}% and not charging.","battery")); b.temperatureCelsius?.let { t -> if(t>=45) issues.add(DiagnosisIssue(DiagnosisSeverity.WARNING,"Battery is warm","Battery temperature is ${"%.1f".format(java.util.Locale.US,t)}°C.","battery")) } } ?: limits.add("Battery reading: ${battery.status.label}")
  storage.value?.let { (total,free)-> if(total>0){val pct=free*100.0/total;if(pct<=5)issues.add(DiagnosisIssue(DiagnosisSeverity.CRITICAL,"Storage almost full","Only ${"%.1f".format(java.util.Locale.US,pct)}% is free.","storage")) else if(pct<=10)issues.add(DiagnosisIssue(DiagnosisSeverity.WARNING,"Storage space is low","Only ${"%.1f".format(java.util.Locale.US,pct)}% is free.","storage"))}} ?: limits.add("Storage reading: ${storage.status.label}")
  val apps=AppRepository(context).loadApps(includePermissions=true); val scanner=PatternScanner(context); var scanned=0
  apps.forEach { app -> currentCoroutineContext().ensureActive(); val r=scanner.scanInstalledPackage(app.packageName); scanned++; onProgress(app.label,scanned,apps.size); when(r.riskLevel){RiskLevel.HIGH->issues.add(DiagnosisIssue(DiagnosisSeverity.CRITICAL,"High-risk app indicator: ${app.label}","Heuristic score ${r.score}; inspect Pattern Scanner.","scanner"));RiskLevel.SUSPICIOUS->issues.add(DiagnosisIssue(DiagnosisSeverity.WARNING,"Suspicious app indicator: ${app.label}","Heuristic score ${r.score}; this is not proof of malware.","scanner"));else->Unit} }
  if(apps.isEmpty()) limits.add("Installed application list was empty or restricted."); if(issues.isEmpty()) issues.add(DiagnosisIssue(DiagnosisSeverity.INFO,"No current issue detected","Available readings and heuristics did not cross thresholds.")); if(memory.status!=DataStatus.AVAILABLE&&memory.status!=DataStatus.LIMITED) limits.add("Some metrics were unavailable because Android restricted them.")
  DeviceDiagnosisResult(issues,scanned,apps.size,System.currentTimeMillis()-start,limits.distinct())
 }
}
