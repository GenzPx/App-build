package com.monitorcheck.logs

import android.content.Context
import android.os.Build
import com.monitorcheck.core.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

data class CrashReport(
    val fileName: String,
    val timestamp: Long,
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
    val threadName: String,
    val appVersion: String,
    val androidVersion: String,
    val device: String
)

/**
 * Local crash capture for Monitored Check itself.
 *
 * Installs an UncaughtExceptionHandler that writes a report to app-private storage
 * and then delegates to the previous handler so the system still records the crash
 * normally. Reports NEVER leave the device — there is no upload path in this class,
 * by design. Sharing is a manual, user-initiated action from the UI.
 */
class CrashReporter(private val context: Context) {

    private val dir: File by lazy { File(context.filesDir, "crash").apply { mkdirs() } }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(thread, throwable) }
            // Always let the platform handler run so behaviour stays standard.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        val content = buildString {
            appendLine("Monitored Check — crash report")
            appendLine("=".repeat(60))
            appendLine("Timestamp:       ${Fmt.timestamp(now)}")
            appendLine("Thread:          ${thread.name}")
            appendLine("Exception:       ${throwable.javaClass.name}")
            appendLine("Message:         ${throwable.message ?: "(none)"}")
            appendLine()
            appendLine("App version:     $versionName")
            appendLine("Package:         ${context.packageName}")
            appendLine("Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device:          ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Product:         ${Build.PRODUCT}")
            appendLine("Board:           ${Build.BOARD}")
            appendLine("Fingerprint:     ${Build.FINGERPRINT}")
            appendLine("ABIs:            ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine("Stack trace")
            appendLine("-".repeat(60))
            appendLine(sw.toString())
        }
        File(dir, "crash_$now.txt").writeText(content)
        pruneOldReports()
    }

    /** Keeps the newest 20 reports so crash logs can never fill up storage. */
    private fun pruneOldReports() {
        runCatching {
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)
                ?.forEach { it.delete() }
        }
    }

    suspend fun listReports(): List<CrashReport> = withContext(Dispatchers.IO) {
        try {
            dir.listFiles { f -> f.name.startsWith("crash_") && f.extension == "txt" }
                ?.sortedByDescending { it.lastModified() }
                ?.mapNotNull { parse(it) }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parse(file: File): CrashReport? = try {
        val text = file.readText()
        fun field(name: String): String =
            Regex("^$name:\\s*(.*)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
                ?: "Unknown"
        CrashReport(
            fileName = file.name,
            timestamp = file.name.removePrefix("crash_").removeSuffix(".txt").toLongOrNull()
                ?: file.lastModified(),
            exceptionType = field("Exception"),
            message = field("Message"),
            stackTrace = text.substringAfter("-".repeat(60)).trim(),
            threadName = field("Thread"),
            appVersion = field("App version"),
            androidVersion = field("Android version"),
            device = field("Device")
        )
    } catch (_: Throwable) {
        null
    }

    suspend fun readRaw(fileName: String): String? = withContext(Dispatchers.IO) {
        try { File(dir, fileName).takeIf { it.exists() }?.readText() } catch (_: Throwable) { null }
    }

    suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try { File(dir, fileName).delete() } catch (_: Throwable) { false }
    }

    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        try { dir.listFiles()?.count { it.delete() } ?: 0 } catch (_: Throwable) { 0 }
    }

    fun fileFor(fileName: String): File = File(dir, fileName)

    /** Deliberately throws, so the user can verify crash capture actually works. */
    fun triggerTestCrash() {
        throw IllegalStateException(
            "Monitored Check test crash — triggered intentionally from Settings to verify " +
                "that local crash reporting works."
        )
    }
}
