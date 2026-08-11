package com.monitorcheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Reading type is the mechanism that enforces the "never fake data" rule, so it
 * gets thorough coverage: a non-available Reading must never expose a value.
 */
class ReadingTest {

    @Test
    fun `available reading exposes its value`() {
        val r = Reading.available(42, source = "/proc/test")
        assertTrue(r.isAvailable)
        assertEquals(42, r.value)
        assertEquals("/proc/test", r.source)
        assertEquals(DataStatus.AVAILABLE, r.status)
    }

    @Test
    fun `unavailable reading has no value and reports its label`() {
        val r = Reading.unavailable<Int>("sysfs node missing")
        assertFalse(r.isAvailable)
        assertNull(r.value)
        assertEquals("Unavailable", r.display())
        assertEquals("sysfs node missing", r.note)
    }

    @Test
    fun `restricted reading displays the Android restriction label`() {
        val r = Reading.restricted<String>()
        assertEquals(DataStatus.RESTRICTED_BY_ANDROID, r.status)
        assertEquals("Restricted by Android", r.display())
        assertFalse(r.isAvailable)
    }

    @Test
    fun `requiresRoot never yields a value`() {
        val r = Reading.requiresRoot<Long>()
        assertNull(r.value)
        assertEquals("Requires Root", r.display())
    }

    @Test
    fun `limited reading is usable but carries an explanation`() {
        val r = Reading.limited(listOf(1, 2), "own processes only", "ActivityManager")
        assertTrue(r.isAvailable)
        assertEquals(2, r.value?.size)
        assertEquals("own processes only", r.note)
    }

    @Test
    fun `map transforms only when a value is present`() {
        val available = Reading.available(50.0).map { "${it}%" }
        assertEquals("50.0%", available.value)

        val missing = Reading.unavailable<Double>().map { "${it}%" }
        assertNull(missing.value)
        assertEquals(DataStatus.UNAVAILABLE, missing.status)
    }

    @Test
    fun `display uses the formatter only for real values`() {
        assertEquals("1.50 GHz", Reading.available(1_500_000L).display { Fmt.freqKHz(it) })
        assertEquals("Unsupported", Reading.unsupported<Long>().display { Fmt.freqKHz(it) })
    }

    @Test
    fun `InfoItem of maps blank input to unavailable`() {
        assertEquals("Unavailable", InfoItem.of("Label", null).text)
        assertEquals("Unavailable", InfoItem.of("Label", "").text)
        assertEquals("Unavailable", InfoItem.of("Label", "   ").text)
        assertEquals("value", InfoItem.of("Label", "value").text)
    }

    @Test
    fun `every non-available status has a human readable label`() {
        DataStatus.entries.forEach { status ->
            assertTrue("$status must have a label", status.label.isNotBlank())
        }
    }
}
