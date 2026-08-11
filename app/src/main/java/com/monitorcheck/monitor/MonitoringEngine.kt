package com.monitorcheck.monitor

import com.monitorcheck.core.Reading
import com.monitorcheck.data.battery.BatterySnapshot
import com.monitorcheck.hardware.cpu.CpuUsage
import com.monitorcheck.hardware.memory.MemorySnapshot
import com.monitorcheck.network.NetworkThroughput

/** One tick of the central monitoring engine. Every field is a real measurement. */
data class MonitorSample(
    val timestamp: Long,
    val cpu: CpuUsage?,
    val memory: Reading<MemorySnapshot>,
    val battery: Reading<BatterySnapshot>,
    val throughput: NetworkThroughput?,
    val hottestCelsius: Reading<Double>,
    val batteryCelsius: Reading<Double>,
    val gpuUtilPercent: Reading<Double>,
    val gpuFreqKHz: Reading<Long>
)

/** Fixed-size ring buffer for graph series — no unbounded growth on long sessions. */
class RingBuffer(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var start = 0
    private var count = 0

    val size: Int get() = count

    fun add(value: Float) {
        if (count < capacity) {
            data[(start + count) % capacity] = value
            count++
        } else {
            data[start] = value
            start = (start + 1) % capacity
        }
    }

    operator fun get(index: Int): Float = data[(start + index) % capacity]

    fun toList(): List<Float> = List(count) { get(it) }

    fun last(): Float? = if (count == 0) null else get(count - 1)

    fun max(): Float = if (count == 0) 0f else (0 until count).maxOf { get(it) }

    fun min(): Float = if (count == 0) 0f else (0 until count).minOf { get(it) }

    fun average(): Float = if (count == 0) 0f else (0 until count).sumOf { get(it).toDouble() }.toFloat() / count

    fun clear() { start = 0; count = 0 }
}

/** Named graph series collected by the engine. */
enum class Series { CPU, CPU_FREQ, RAM, BATTERY_LEVEL, BATTERY_TEMP, BATTERY_CURRENT,
    DEVICE_TEMP, NET_DOWN, NET_UP, GPU, FPS }

/** Holds all graph histories with bounded memory. */
class SeriesStore(private val capacity: Int = 120) {
    private val buffers = HashMap<Series, RingBuffer>()

    fun buffer(series: Series): RingBuffer = buffers.getOrPut(series) { RingBuffer(capacity) }

    fun add(series: Series, value: Float) = buffer(series).add(value)

    fun snapshot(series: Series): List<Float> = buffer(series).toList()

    fun clear() = buffers.values.forEach { it.clear() }
}
