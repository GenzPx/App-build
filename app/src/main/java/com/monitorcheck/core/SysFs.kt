package com.monitorcheck.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Safe reader for kernel-exported pseudo files (procfs / sysfs).
 *
 * All access is plain unprivileged file IO through the standard Java APIs. No root,
 * no shell escalation, no security bypass: if SELinux or file permissions deny the
 * read we simply report the value as unavailable/restricted.
 *
 * Paths that fail are remembered so the polling loop does not keep hammering
 * unreadable nodes every tick (important for low-end devices).
 */
object SysFs {

    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun isReadable(path: String): Boolean = try {
        val f = File(path)
        f.exists() && f.canRead()
    } catch (_: Throwable) {
        false
    }

    /** Reads a whole pseudo file, trimmed. Returns null when not readable. */
    fun readText(path: String, useFailureCache: Boolean = true): String? {
        if (useFailureCache && failed.contains(path)) return null
        return try {
            val f = File(path)
            if (!f.exists() || !f.canRead()) {
                if (useFailureCache) failed.add(path)
                return null
            }
            // Pseudo files report length 0, so read the stream rather than allocating by size.
            f.bufferedReader().use { it.readText() }.trim().ifEmpty { null }
        } catch (_: Throwable) {
            if (useFailureCache) failed.add(path)
            null
        }
    }

    /**
     * Reads a small numeric sysfs node with a reusable RandomAccessFile-style read.
     * Used on hot paths (per-core frequency) to reduce allocation churn.
     */
    fun readFirstLine(path: String): String? {
        if (failed.contains(path)) return null
        return try {
            RandomAccessFile(path, "r").use { raf ->
                raf.readLine()?.trim()?.ifEmpty { null }
            }
        } catch (_: Throwable) {
            failed.add(path)
            null
        }
    }

    fun readLong(path: String): Long? = readFirstLine(path)?.let {
        it.filter { c -> c.isDigit() || c == '-' }.toLongOrNull()
    }

    fun readInt(path: String): Int? = readLong(path)?.toInt()

    fun readLines(path: String): List<String>? = readText(path, useFailureCache = false)?.lines()

    fun listDir(path: String, filter: (String) -> Boolean = { true }): List<File> = try {
        File(path).listFiles()?.filter { filter(it.name) }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** Clears the negative cache; used when the user manually refreshes a page. */
    fun resetFailureCache() = failed.clear()
}
