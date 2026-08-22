package com.monitorcheck.hardware.display

import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FpsStats(
    val currentFps: Double,
    val averageFps: Double,
    val minFps: Double,
    val maxFps: Double,
    val lastFrameTimeMs: Double,
    val averageFrameTimeMs: Double,
    val droppedFrames: Int,
    val totalFrames: Long,
    val displayRefreshHz: Float,
    val measuring: Boolean
)

class FpsMonitor(private val displayRefreshHz: Float) {

    private val _stats = MutableStateFlow(
        FpsStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, displayRefreshHz, false)
    )
    val stats: StateFlow<FpsStats> = _stats

    private var lastFrameNanos = 0L
    private var frameCount = 0L
    private var dropped = 0
    private var windowStartNanos = 0L
    private var windowFrames = 0
    private var minFps = Double.MAX_VALUE
    private var maxFps = 0.0
    private var sumFrameTimeMs = 0.0
    private var running = false

    private val nominalFrameMs: Double =
        if (displayRefreshHz > 0) 1000.0 / displayRefreshHz else 16.667

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
                if (deltaMs > 0) {
                    frameCount++
                    windowFrames++
                    sumFrameTimeMs += deltaMs

                    if (deltaMs > nominalFrameMs * 1.5) {
                        dropped += ((deltaMs / nominalFrameMs) - 1).toInt().coerceAtLeast(1)
                    }
                }
            }
            lastFrameNanos = frameTimeNanos
            if (windowStartNanos == 0L) windowStartNanos = frameTimeNanos

            val windowMs = (frameTimeNanos - windowStartNanos) / 1_000_000.0
            if (windowMs >= 500.0 && windowFrames > 0) {
                val fps = windowFrames * 1000.0 / windowMs
                if (fps < minFps) minFps = fps
                if (fps > maxFps) maxFps = fps
                _stats.value = FpsStats(
                    currentFps = fps,
                    averageFps = if (sumFrameTimeMs > 0) 1000.0 / (sumFrameTimeMs / frameCount) else 0.0,
                    minFps = if (minFps == Double.MAX_VALUE) 0.0 else minFps,
                    maxFps = maxFps,
                    lastFrameTimeMs = if (frameCount > 0) sumFrameTimeMs / frameCount else 0.0,
                    averageFrameTimeMs = if (frameCount > 0) sumFrameTimeMs / frameCount else 0.0,
                    droppedFrames = dropped,
                    totalFrames = frameCount,
                    displayRefreshHz = displayRefreshHz,
                    measuring = true
                )
                windowStartNanos = frameTimeNanos
                windowFrames = 0
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        reset()
        try {
            Choreographer.getInstance().postFrameCallback(callback)
            _stats.value = _stats.value.copy(measuring = true)
        } catch (_: Throwable) {
            running = false
            _stats.value = _stats.value.copy(measuring = false)
        }
    }

    fun stop() {
        running = false
        try { Choreographer.getInstance().removeFrameCallback(callback) } catch (_: Throwable) { }
        _stats.value = _stats.value.copy(measuring = false)
    }

    fun reset() {
        lastFrameNanos = 0; frameCount = 0; dropped = 0
        windowStartNanos = 0; windowFrames = 0
        minFps = Double.MAX_VALUE; maxFps = 0.0; sumFrameTimeMs = 0.0
    }

    val methodDescription: String = """
        Measured with Choreographer vsync callbacks on this app's own render loop.

        Android does not expose the frame rate of other applications or of SurfaceFlinger
        to third-party apps, so this figure describes how quickly the display pipeline is
        currently presenting Monitored Check's own frames. It is a real measurement of
        this process, not a system-wide or per-game FPS reading.
    """.trimIndent()
}
