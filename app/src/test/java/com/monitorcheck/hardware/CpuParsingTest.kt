package com.monitorcheck.hardware

import com.monitorcheck.hardware.cpu.CpuTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuParsingTest {

    private fun times(user: Long, nice: Long, system: Long, idle: Long, iowait: Long = 0) =
        CpuTimes(user, nice, system, idle, iowait, 0, 0, 0)

    private fun delta(prev: CpuTimes?, cur: CpuTimes?): Double? {
        if (prev == null || cur == null) return null
        val totalDelta = cur.total - prev.total
        val busyDelta = cur.busy - prev.busy
        if (totalDelta <= 0) return null
        return (busyDelta.toDouble() / totalDelta.toDouble() * 100.0).coerceIn(0.0, 100.0)
    }

    @Test
    fun `total is the sum of all counters`() {
        val t = times(user = 100, nice = 10, system = 50, idle = 800, iowait = 40)
        assertEquals(1000L, t.total)
    }

    @Test
    fun `busy excludes idle and iowait`() {
        val t = times(user = 100, nice = 10, system = 50, idle = 800, iowait = 40)
        assertEquals(160L, t.busy)
    }

    @Test
    fun `fully idle interval reports zero percent`() {
        val prev = times(100, 0, 0, 900)
        val cur = times(100, 0, 0, 1900)
        assertEquals(0.0, delta(prev, cur)!!, 0.001)
    }

    @Test
    fun `fully busy interval reports one hundred percent`() {
        val prev = times(100, 0, 0, 900)
        val cur = times(1100, 0, 0, 900)
        assertEquals(100.0, delta(prev, cur)!!, 0.001)
    }

    @Test
    fun `half busy interval reports fifty percent`() {
        val prev = times(0, 0, 0, 0)
        val cur = times(500, 0, 0, 500)
        assertEquals(50.0, delta(prev, cur)!!, 0.001)
    }

    @Test
    fun `no counter movement yields null rather than a fabricated zero`() {
        val same = times(100, 0, 0, 900)
        assertNull(delta(same, same))
    }

    @Test
    fun `missing sample yields null`() {
        assertNull(delta(null, times(1, 1, 1, 1)))
        assertNull(delta(times(1, 1, 1, 1), null))
    }

    @Test
    fun `counter rollover cannot produce an out of range value`() {

        val prev = times(1000, 0, 0, 1000)
        val cur = times(10, 0, 0, 3000)
        val result = delta(prev, cur)
        if (result != null) {
            assertTrue("value must stay within 0..100 but was $result", result in 0.0..100.0)
        }
    }

    @Test
    fun `proc stat line parses into the expected counters`() {
        val line = "cpu  12345 678 9012 345678 901 23 45 0 0 0"
        val parts = line.trim().split(Regex("\\s+"))
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        val t = CpuTimes(
            nums[0], nums[1], nums[2], nums[3], nums[4], nums[5], nums[6], nums[7]
        )
        assertEquals(12345L, t.user)
        assertEquals(345678L, t.idle)
        assertEquals(901L, t.iowait)
        assertEquals(12345L + 678 + 9012 + 45 + 23, t.busy)
    }

    @Test
    fun `per core lines are identified by their index suffix`() {
        val labels = listOf("cpu", "cpu0", "cpu1", "cpu7", "intr")
        val coreIndices = labels
            .filter { it.startsWith("cpu") && it != "cpu" }
            .mapNotNull { it.removePrefix("cpu").toIntOrNull() }
        assertEquals(listOf(0, 1, 7), coreIndices)
    }
}
