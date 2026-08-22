package com.monitorcheck.core

import java.io.File
import java.io.RandomAccessFile

object SysFs {

    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun isReadable(path: String): Boolean = try {
        val f = File(path)
        f.exists() && f.canRead()
    } catch (_: Throwable) {
        false
    }

    fun readText(path: String, useFailureCache: Boolean = true): String? {
        if (useFailureCache && failed.contains(path)) return null
        return try {
            val f = File(path)
            if (!f.exists() || !f.canRead()) {
                if (useFailureCache) failed.add(path)
                return null
            }

            f.bufferedReader().use { it.readText() }.trim().ifEmpty { null }
        } catch (_: Throwable) {
            if (useFailureCache) failed.add(path)
            null
        }
    }

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

    fun resetFailureCache() = failed.clear()
}
