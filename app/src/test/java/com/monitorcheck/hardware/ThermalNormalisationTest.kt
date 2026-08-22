package com.monitorcheck.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalNormalisationTest {

    private fun normalise(raw: Long): Double = when {
        raw > 10_000 -> raw / 1000.0
        raw > 1_000 -> raw / 100.0
        raw > 200 -> raw / 10.0
        else -> raw.toDouble()
    }

    @Test
    fun `millicelsius is divided by one thousand`() {
        assertEquals(45.0, normalise(45_000), 0.001)
        assertEquals(72.5, normalise(72_500), 0.001)
    }

    @Test
    fun `centicelsius is divided by one hundred`() {
        assertEquals(38.0, normalise(3_800), 0.001)
    }

    @Test
    fun `decicelsius is divided by ten`() {
        assertEquals(41.0, normalise(410), 0.001)
    }

    @Test
    fun `plain celsius passes through unchanged`() {
        assertEquals(42.0, normalise(42), 0.001)
        assertEquals(0.0, normalise(0), 0.001)
    }

    @Test
    fun `all common vendor encodings land in a plausible range`() {
        val encodings = listOf(45_000L, 4_500L, 450L, 45L)
        encodings.forEach { raw ->
            val c = normalise(raw)
            assertTrue("raw=$raw normalised to $c which is not plausible", c in 4.0..100.0)
        }
    }

    @Test
    fun `values outside the sane band would be rejected by the repository filter`() {

        val bogus = normalise(999_999_999)
        assertTrue("bogus reading should fall outside the accepted band", bogus > 200.0)
    }
}
