package com.monitorcheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmtTest {

    @Test
    fun `bytes uses binary units`() {
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.00 KB", Fmt.bytes(1024))
        assertEquals("1.00 MB", Fmt.bytes(1024L * 1024))
        assertEquals("1.50 GB", Fmt.bytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `negative byte counts are reported as unavailable`() {
        assertEquals("Unavailable", Fmt.bytes(-1))
    }

    @Test
    fun `frequency converts kHz to MHz and GHz`() {
        assertEquals("300 MHz", Fmt.freqKHz(300_000))
        assertEquals("2.40 GHz", Fmt.freqKHz(2_400_000))
        assertEquals("Unavailable", Fmt.freqKHz(0))
        assertEquals("Unavailable", Fmt.freqKHz(-5))
    }

    @Test
    fun `percent clamps to the valid range`() {
        assertEquals("50.0%", Fmt.percent(50.0))
        assertEquals("100.0%", Fmt.percent(150.0))
        assertEquals("0.0%", Fmt.percent(-10.0))
        assertEquals("42%", Fmt.percent(42.0, 0))
    }

    @Test
    fun `voltage converts millivolts to volts`() {
        assertEquals("4.200 V", Fmt.voltage(4200))
    }

    @Test
    fun `current converts microamps to milliamps`() {
        assertEquals("-350 mA", Fmt.currentMa(-350_000))
        assertEquals("1500 mA", Fmt.currentMa(1_500_000))
    }

    @Test
    fun `duration renders days hours minutes seconds`() {
        assertEquals("45s", Fmt.duration(45_000))
        assertEquals("2m 5s", Fmt.duration(125_000))
        assertEquals("1h 0m 0s", Fmt.duration(3_600_000))
        assertEquals("Unavailable", Fmt.duration(-1))
    }

    @Test
    fun `throughput scales through byte units`() {
        assertTrue(Fmt.bytesPerSecond(2048.0).contains("KB/s"))
        assertTrue(Fmt.bytesPerSecond(5_000_000.0).contains("MB/s"))
    }

    @Test
    fun `bitrate uses decimal units as networking convention requires`() {
        assertEquals("1.00 Kbps", Fmt.bitsPerSecond(1000.0))
        assertEquals("1.00 Mbps", Fmt.bitsPerSecond(1_000_000.0))
    }

    @Test
    fun `temperature has one decimal place`() {
        assertEquals("36.5 °C", Fmt.temperature(36.54))
    }
}
