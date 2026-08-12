package com.monitorcheck.monitor

import java.util.Locale
data class HistoryFinding(val title: String, val detail: String)
object HistoryAnalyzer {
    fun findings(points: List<HistoryPoint>): List<HistoryFinding> {
        if (points.isEmpty()) return emptyList(); val out=ArrayList<HistoryFinding>()
        val ram=points.count { (it.ramPercent ?: 0.0) >= 80 }; if (ram >= 3) out.add(HistoryFinding("RAM pressure observed","$ram samples were at or above 80% RAM usage."))
        val cpu=points.count { (it.cpuPercent ?: 0.0) >= 90 }; if (cpu >= 3) out.add(HistoryFinding("Sustained CPU load","$cpu samples were at or above 90% CPU usage."))
        val hot=points.count { (it.deviceTempC ?: 0.0) >= 45 }; if (hot >= 2) out.add(HistoryFinding("Elevated thermal readings","$hot samples reached at least 45°C."))
        val hotLight=points.count { (it.deviceTempC ?: 0.0) >= 45 && (it.cpuPercent ?: 100.0) < 50 }; if (hotLight >= 2) out.add(HistoryFinding("Hot while lightly loaded","The history contains hot samples while CPU load was below 50%. Inspect background activity and charging."))
        val net=points.mapNotNull { it.downloadBps }; if (net.isNotEmpty() && net.max() > 10*1024*1024.0) out.add(HistoryFinding("High download burst","A recorded download rate peaked at ${format(net.max())}."))
        return out
    }
    private fun format(v0: Double): String { var v=v0; val u=arrayOf("B/s","KB/s","MB/s","GB/s"); var i=0; while(v>=1024&&i<u.lastIndex){v/=1024;i++}; return String.format(Locale.US,"%.1f %s",v,u[i]) }
}
