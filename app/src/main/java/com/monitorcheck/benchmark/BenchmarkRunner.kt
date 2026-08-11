package com.monitorcheck.benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * One benchmark result. [score] is a derived throughput figure, and [detail] always
 * states the actual unit measured so the number is interpretable rather than magic.
 */
data class BenchResult(
    val name: String,
    val score: Long,
    val unit: String,
    val detail: String,
    val durationMs: Long
)

data class BenchmarkReport(
    val results: List<BenchResult>,
    val totalMs: Long,
    val threadsUsed: Int,
    val note: String
)

/**
 * On-device micro-benchmarks.
 *
 * These measure real work: actual integer/floating-point operations, actual memory
 * copies, actual SHA-256 hashing and actual file I/O. Every score is derived from a
 * measured elapsed time — nothing is scaled against a hidden reference table or
 * invented to look impressive.
 *
 * Results are only meaningful relative to other runs on the same device: thermal
 * state, other running apps and CPU governor all affect them, and the UI says so.
 */
class BenchmarkRunner {

    /** Random data is used as benchmark *input*, never as a reported measurement. */
    private val rng = Random(12345)

    suspend fun runAll(
        cacheDir: File,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        onProgress: (String) -> Unit = {}
    ): BenchmarkReport = withContext(Dispatchers.Default) {
        val results = ArrayList<BenchResult>()
        val start = System.currentTimeMillis()

        onProgress("Integer arithmetic (single core)")
        results.add(integerSingleCore())

        onProgress("Floating point (single core)")
        results.add(floatingPointSingleCore())

        onProgress("Multi-core scaling")
        results.add(multiCore(threads))

        onProgress("Memory bandwidth")
        results.add(memoryBandwidth())

        onProgress("SHA-256 hashing")
        results.add(hashing())

        onProgress("Storage write")
        results.add(storageWrite(cacheDir))

        onProgress("Storage read")
        results.add(storageRead(cacheDir))

        BenchmarkReport(
            results = results,
            totalMs = System.currentTimeMillis() - start,
            threadsUsed = threads,
            note = "All figures are measured on this device right now. They are comparable " +
                "between runs on the same phone, but not against other devices' marketing " +
                "numbers. Thermal throttling, background apps and the CPU governor all " +
                "influence the result."
        )
    }

    /** Integer ops: a dependent chain so the compiler cannot vectorise it away. */
    private suspend fun integerSingleCore(): BenchResult {
        val iterations = 40_000_000L
        var acc = 1L
        val nanos = measureNanoTime {
            var i = 0L
            while (i < iterations) {
                acc = acc * 31 + i
                acc = acc xor (acc shr 7)
                i++
            }
        }
        currentCoroutineContext().ensureActive()
        // Consume acc so JIT cannot eliminate the loop.
        if (acc == Long.MIN_VALUE) throw IllegalStateException()
        val mops = (iterations * 2.0) / (nanos / 1_000_000_000.0) / 1_000_000.0
        return BenchResult(
            "Integer arithmetic", mops.toLong(), "MOPS",
            "${iterations / 1_000_000}M dependent multiply-xor operations, single thread",
            nanos / 1_000_000
        )
    }

    /** Floating point: sqrt-heavy loop, representative of real numeric work. */
    private suspend fun floatingPointSingleCore(): BenchResult {
        val iterations = 15_000_000L
        var acc = 0.0
        val nanos = measureNanoTime {
            var i = 1L
            while (i <= iterations) {
                acc += sqrt(i.toDouble()) / (i.toDouble() + 1.0)
                i++
            }
        }
        currentCoroutineContext().ensureActive()
        if (acc == Double.MAX_VALUE) throw IllegalStateException()
        val mflops = (iterations * 3.0) / (nanos / 1_000_000_000.0) / 1_000_000.0
        return BenchResult(
            "Floating point", mflops.toLong(), "MFLOPS",
            "${iterations / 1_000_000}M sqrt + divide + add, single thread",
            nanos / 1_000_000
        )
    }

    /** Runs the same integer kernel on every core to measure real parallel scaling. */
    private suspend fun multiCore(threads: Int): BenchResult = coroutineScope {
        val perThread = 20_000_000L
        val nanos = measureNanoTime {
            (0 until threads).map { t ->
                async(Dispatchers.Default) {
                    var acc = t.toLong() + 1
                    var i = 0L
                    while (i < perThread) {
                        acc = acc * 31 + i
                        acc = acc xor (acc shr 7)
                        i++
                    }
                    acc
                }
            }.awaitAll()
        }
        val totalOps = perThread * threads * 2.0
        val mops = totalOps / (nanos / 1_000_000_000.0) / 1_000_000.0
        BenchResult(
            "Multi-core", mops.toLong(), "MOPS",
            "$threads threads x ${perThread / 1_000_000}M operations in parallel",
            nanos / 1_000_000
        )
    }

    /** Memory bandwidth via large array copies. */
    private suspend fun memoryBandwidth(): BenchResult {
        val sizeMb = 32
        val elements = sizeMb * 1024 * 1024 / 8
        val src = LongArray(elements) { it.toLong() }
        val dst = LongArray(elements)
        val rounds = 5
        val nanos = measureNanoTime {
            repeat(rounds) { System.arraycopy(src, 0, dst, 0, elements) }
        }
        currentCoroutineContext().ensureActive()
        if (dst[elements - 1] != src[elements - 1]) throw IllegalStateException("copy failed")
        val totalBytes = sizeMb.toDouble() * rounds
        val mbPerSec = totalBytes / (nanos / 1_000_000_000.0)
        return BenchResult(
            "Memory bandwidth", mbPerSec.toLong(), "MB/s",
            "$rounds x ${sizeMb}MB array copy (read + write)",
            nanos / 1_000_000
        )
    }

    /** SHA-256 throughput — exercises crypto acceleration where the SoC has it. */
    private suspend fun hashing(): BenchResult {
        val block = ByteArray(1024 * 1024) { (it and 0xFF).toByte() }
        val rounds = 64
        val md = MessageDigest.getInstance("SHA-256")
        val nanos = measureNanoTime {
            repeat(rounds) { md.update(block) }
            md.digest()
        }
        currentCoroutineContext().ensureActive()
        val mbPerSec = rounds.toDouble() / (nanos / 1_000_000_000.0)
        return BenchResult(
            "SHA-256 hashing", mbPerSec.toLong(), "MB/s",
            "$rounds MB hashed with the platform SHA-256 implementation",
            nanos / 1_000_000
        )
    }

    /** Sequential write to app cache, with an fsync so the number reflects real I/O. */
    private suspend fun storageWrite(cacheDir: File): BenchResult = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "bench_write.tmp")
        val chunk = ByteArray(1024 * 1024) { rng.nextInt(256).toByte() }
        val sizeMb = 48
        val nanos = try {
            measureNanoTime {
                java.io.FileOutputStream(file).use { out ->
                    repeat(sizeMb) { out.write(chunk) }
                    out.flush()
                    // Force data to the storage device, not just the page cache.
                    out.fd.sync()
                }
            }
        } catch (t: Throwable) {
            return@withContext BenchResult("Storage write", 0, "MB/s",
                "Failed: ${t.javaClass.simpleName}", 0)
        }
        val mbPerSec = sizeMb.toDouble() / (nanos / 1_000_000_000.0)
        BenchResult(
            "Storage write", mbPerSec.toLong(), "MB/s",
            "${sizeMb}MB sequential write to app cache, fsync included",
            nanos / 1_000_000
        )
    }

    /** Sequential read of the file just written. */
    private suspend fun storageRead(cacheDir: File): BenchResult = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "bench_write.tmp")
        if (!file.exists()) {
            return@withContext BenchResult("Storage read", 0, "MB/s",
                "Skipped: write phase produced no file", 0)
        }
        val buf = ByteArray(1024 * 1024)
        var total = 0L
        val nanos = try {
            measureNanoTime {
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                }
            }
        } catch (t: Throwable) {
            return@withContext BenchResult("Storage read", 0, "MB/s",
                "Failed: ${t.javaClass.simpleName}", 0)
        } finally {
            runCatching { file.delete() }
        }
        val mb = total / 1_048_576.0
        val mbPerSec = mb / (nanos / 1_000_000_000.0)
        BenchResult(
            "Storage read", mbPerSec.toLong(), "MB/s",
            String.format(Locale.US, "%.0fMB sequential read (may be served partly from cache)", mb),
            nanos / 1_000_000
        )
    }
}
