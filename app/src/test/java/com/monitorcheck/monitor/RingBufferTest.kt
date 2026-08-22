package com.monitorcheck.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingBufferTest {

    @Test
    fun `starts empty`() {
        val b = RingBuffer(5)
        assertEquals(0, b.size)
        assertNull(b.last())
        assertEquals(emptyList<Float>(), b.toList())
    }

    @Test
    fun `fills up to capacity`() {
        val b = RingBuffer(5)
        repeat(3) { b.add(it.toFloat()) }
        assertEquals(3, b.size)
        assertEquals(listOf(0f, 1f, 2f), b.toList())
    }

    @Test
    fun `never exceeds capacity`() {
        val b = RingBuffer(5)
        repeat(100) { b.add(it.toFloat()) }
        assertEquals(5, b.size)
    }

    @Test
    fun `keeps the most recent values after wrapping`() {
        val b = RingBuffer(3)
        listOf(1f, 2f, 3f, 4f, 5f).forEach { b.add(it) }
        assertEquals(listOf(3f, 4f, 5f), b.toList())
        assertEquals(5f, b.last())
    }

    @Test
    fun `statistics reflect only retained values`() {
        val b = RingBuffer(3)
        listOf(10f, 20f, 30f, 40f).forEach { b.add(it) }

        assertEquals(20f, b.min(), 0.001f)
        assertEquals(40f, b.max(), 0.001f)
        assertEquals(30f, b.average(), 0.001f)
    }

    @Test
    fun `clear resets the buffer`() {
        val b = RingBuffer(4)
        repeat(4) { b.add(it.toFloat()) }
        b.clear()
        assertEquals(0, b.size)
        assertNull(b.last())
    }

    @Test
    fun `indexing follows insertion order after wrap`() {
        val b = RingBuffer(3)
        listOf(1f, 2f, 3f, 4f).forEach { b.add(it) }
        assertEquals(2f, b[0], 0.001f)
        assertEquals(3f, b[1], 0.001f)
        assertEquals(4f, b[2], 0.001f)
    }

    @Test
    fun `series store keeps independent buffers per series`() {
        val store = SeriesStore(capacity = 3)
        store.add(Series.CPU, 10f)
        store.add(Series.RAM, 90f)
        store.add(Series.CPU, 20f)

        assertEquals(listOf(10f, 20f), store.snapshot(Series.CPU))
        assertEquals(listOf(90f), store.snapshot(Series.RAM))
        assertEquals(emptyList<Float>(), store.snapshot(Series.GPU))
    }

    @Test
    fun `series store clear empties every series`() {
        val store = SeriesStore(capacity = 3)
        store.add(Series.CPU, 1f)
        store.add(Series.NET_DOWN, 2f)
        store.clear()
        assertEquals(emptyList<Float>(), store.snapshot(Series.CPU))
        assertEquals(emptyList<Float>(), store.snapshot(Series.NET_DOWN))
    }
}
