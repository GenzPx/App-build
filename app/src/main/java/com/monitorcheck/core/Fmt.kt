package com.monitorcheck.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Fmt {

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun timestamp(millis: Long): String = tsFormat.format(Date(millis))
    fun time(millis: Long): String = timeFormat.format(Date(millis))
    fun date(millis: Long): String = dateFormat.format(Date(millis))

    fun bytes(value: Long): String {
        if (value < 0) return "Unavailable"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return if (i == 0) "${value} B" else String.format(Locale.US, "%.2f %s", v, units[i])
    }

    fun bytesPerSecond(value: Double): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = abs(value)
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun bitsPerSecond(bits: Double): String {
        val units = arrayOf("bps", "Kbps", "Mbps", "Gbps")
        var v = abs(bits)
        var i = 0
        while (v >= 1000.0 && i < units.lastIndex) {
            v /= 1000.0
            i++
        }
        return String.format(Locale.US, "%.2f %s", v, units[i])
    }

    fun freqKHz(khz: Long): String = when {
        khz <= 0 -> "Unavailable"
        khz >= 1_000_000 -> String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
        else -> String.format(Locale.US, "%d MHz", khz / 1000)
    }

    fun mhz(mhz: Long): String = freqKHz(mhz * 1000)

    fun percent(value: Double, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f%%", value.coerceIn(0.0, 100.0))

    fun temperature(celsius: Double): String = String.format(Locale.US, "%.1f °C", celsius)

    fun number(value: Double, unit: String): String =
        String.format(Locale.US, "%.1f%s", value, unit)

    fun voltage(millivolt: Int): String = String.format(Locale.US, "%.3f V", millivolt / 1000.0)

    fun currentMa(microAmp: Int): String = String.format(Locale.US, "%d mA", microAmp / 1000)

    fun duration(millis: Long): String {
        if (millis < 0) return "Unavailable"
        var s = millis / 1000
        val d = s / 86400; s %= 86400
        val h = s / 3600; s %= 3600
        val m = s / 60; s %= 60
        return buildString {
            if (d > 0) append("${d}d ")
            if (d > 0 || h > 0) append("${h}h ")
            if (d > 0 || h > 0 || m > 0) append("${m}m ")
            append("${s}s")
        }
    }

    fun yesNo(value: Boolean) = if (value) "Yes" else "No"
}
