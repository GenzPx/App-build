package com.monitorcheck.monitor

import com.monitorcheck.hardware.cpu.CpuRepository
import com.monitorcheck.hardware.thermal.ThermalRepository
import kotlinx.coroutines.*
data class StressPoint(val elapsedMs:Long,val cpuPercent:Double?,val temperatureC:Double?,val peakFrequencyKHz:Long?)
data class StressTestResult(val points:List<StressPoint>,val stoppedBecause:String)
class StressTestEngine(private val cpuRepository:CpuRepository,private val thermalRepository:ThermalRepository){suspend fun run(durationSeconds:Int,stopTemperatureC:Double=60.0,onPoint:suspend(StressPoint)->Unit):StressTestResult=coroutineScope{val points=ArrayList<StressPoint>();val workers=ArrayList<Job>();repeat(Runtime.getRuntime().availableProcessors().coerceIn(1,8)){workers+=launch(Dispatchers.Default){var x=0x12345678;while(isActive){x=x*1664525+1013904223;if((x and 0x3fff)==0)yield()}}};val start=android.os.SystemClock.elapsedRealtime();var reason="Completed selected duration";try{while(isActive){delay(1000);val e=android.os.SystemClock.elapsedRealtime()-start;val u=runCatching{cpuRepository.sampleUsage()}.getOrNull();val t=runCatching{thermalRepository.hottestZone().value?.celsius}.getOrNull();val point=StressPoint(e,u?.totalPercent?.value,t,u?.cores?.mapNotNull{it.currentKHz}?.maxOrNull());points.add(point);onPoint(point);if(t!=null&&t>=stopTemperatureC){reason="Stopped automatically at ${"%.1f".format(java.util.Locale.US,t)}°C";break};if(e>=durationSeconds.coerceIn(1,600)*1000L)break}}finally{workers.forEach{it.cancel();it.join()}};StressTestResult(points,reason)}}
