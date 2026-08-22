package com.monitorcheck.storage

import android.content.Context
import android.os.Environment
import com.monitorcheck.core.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class FileEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val childCount: Int = 0
)

data class TypeBucket(
    val label: String,
    val extensions: Set<String>,
    var totalBytes: Long = 0,
    var fileCount: Int = 0
)

data class DuplicateGroup(
    val hash: String,
    val sizeBytes: Long,
    val files: List<String>
) {
    val wastedBytes: Long get() = sizeBytes * (files.size - 1)
}

data class AnalysisResult(
    val rootPath: String,
    val totalBytes: Long,
    val fileCount: Int,
    val dirCount: Int,
    val largestFiles: List<FileEntry>,
    val largestFolders: List<FileEntry>,
    val typeDistribution: List<TypeBucket>,
    val extensionStats: List<Pair<String, Long>>,
    val cacheCandidates: List<FileEntry>,
    val tempCandidates: List<FileEntry>,
    val scanTimeMs: Long,
    val truncated: Boolean,
    val unreadableDirs: Int
)

class StorageAnalyzer(private val context: Context) {

    companion object {
        private const val MAX_DEPTH = 12
        private const val MAX_ENTRIES = 120_000
        private const val TOP_N = 50
        private const val DUP_MIN_SIZE = 1024L * 512
        private const val DUP_MAX_FILES = 4_000

        val TYPE_BUCKETS: List<TypeBucket> get() = listOf(
            TypeBucket("Images", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "raw", "dng")),
            TypeBucket("Video", setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "m4v", "ts", "wmv")),
            TypeBucket("Audio", setOf("mp3", "aac", "wav", "flac", "ogg", "m4a", "opus", "amr", "wma", "mid")),
            TypeBucket("Documents", setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "epub", "rtf", "csv")),
            TypeBucket("Archives", setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "apk", "obb")),
            TypeBucket("Applications", setOf("apk", "apks", "xapk", "aab", "dex", "so", "jar")),
            TypeBucket("Databases", setOf("db", "sqlite", "sqlite3", "realm")),
            TypeBucket("Cache / temp", setOf("cache", "tmp", "temp", "log", "bak", "old", "part", "crdownload"))
        )
    }

    fun accessibleRoots(): List<File> {
        val roots = LinkedHashSet<File>()

        context.filesDir.parentFile?.let { roots.add(it) }
        context.externalCacheDir?.parentFile?.let { roots.add(it) }
        if (Permissions.canReadStorage(context)) {
            runCatching {
                @Suppress("DEPRECATION")
                Environment.getExternalStorageDirectory()?.let { if (it.canRead()) roots.add(it) }
            }
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->

                var f: File? = dir
                repeat(4) { f = f?.parentFile }
                f?.let { if (it.canRead()) roots.add(it) }
            }
        }
        return roots.filter { it.exists() && it.canRead() }.distinctBy { it.absolutePath }
    }

    fun canDeepScan(): Boolean = Permissions.canReadStorage(context)

    suspend fun listDirectory(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.isDirectory || !dir.canRead()) return@withContext emptyList()
        dir.listFiles()?.map { f ->
            FileEntry(
                name = f.name,
                path = f.absolutePath,
                sizeBytes = if (f.isDirectory) 0 else f.length(),
                lastModified = f.lastModified(),
                isDirectory = f.isDirectory,
                childCount = if (f.isDirectory) runCatching { f.list()?.size ?: 0 }.getOrDefault(0) else 0
            )
        }?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.sizeBytes })
            ?: emptyList()
    }

    suspend fun analyze(
        rootPath: String,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val root = File(rootPath)

        var totalBytes = 0L
        var fileCount = 0
        var dirCount = 0
        var unreadable = 0
        var truncated = false

        val buckets = TYPE_BUCKETS
        val extTotals = HashMap<String, Long>()
        val largestFiles = java.util.PriorityQueue<FileEntry>(compareBy { it.sizeBytes })
        val folderSizes = HashMap<String, Long>()
        val cacheCandidates = ArrayList<FileEntry>()
        val tempCandidates = ArrayList<FileEntry>()

        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(root to 0)

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) { truncated = true; continue }
            if (fileCount + dirCount > MAX_ENTRIES) { truncated = true; break }

            val children = try { dir.listFiles() } catch (_: Throwable) { null }
            if (children == null) { unreadable++; continue }

            dirCount++
            if (dirCount % 200 == 0) onProgress(dir.absolutePath, fileCount)

            var dirBytes = 0L
            for (child in children) {
                currentCoroutineContext().ensureActive()
                try {
                    if (child.isDirectory) {
                        val name = child.name.lowercase()
                        if (name == "cache" || name == ".cache" || name == "code_cache") {
                            val size = directorySize(child, depth + 1)
                            cacheCandidates.add(FileEntry(child.name, child.absolutePath, size,
                                child.lastModified(), true))
                        }
                        stack.add(child to depth + 1)
                    } else {
                        val len = child.length()
                        fileCount++
                        totalBytes += len
                        dirBytes += len

                        val ext = child.extension.lowercase()
                        if (ext.isNotEmpty() && ext.length <= 6) {
                            extTotals[ext] = (extTotals[ext] ?: 0) + len
                            buckets.firstOrNull { ext in it.extensions }?.let {
                                it.totalBytes += len; it.fileCount++
                            }
                        }

                        val entry = FileEntry(child.name, child.absolutePath, len, child.lastModified(), false)
                        largestFiles.add(entry)
                        if (largestFiles.size > TOP_N) largestFiles.poll()

                        val lower = child.name.lowercase()
                        if (ext in setOf("tmp", "temp", "part", "crdownload", "bak", "old") ||
                            lower.startsWith("~") || lower.endsWith(".log")) {
                            if (tempCandidates.size < 200) {
                                tempCandidates.add(entry)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    unreadable++
                }
            }
            if (dirBytes > 0) folderSizes[dir.absolutePath] = dirBytes
        }

        val recursive = HashMap<String, Long>()
        for ((path, size) in folderSizes) {
            var p: String? = path
            while (p != null && p.startsWith(rootPath)) {
                recursive[p] = (recursive[p] ?: 0) + size
                p = File(p).parent
            }
        }

        AnalysisResult(
            rootPath = rootPath,
            totalBytes = totalBytes,
            fileCount = fileCount,
            dirCount = dirCount,
            largestFiles = largestFiles.sortedByDescending { it.sizeBytes },
            largestFolders = recursive.entries.sortedByDescending { it.value }.take(TOP_N).map {
                FileEntry(File(it.key).name.ifEmpty { it.key }, it.key, it.value,
                    File(it.key).lastModified(), true)
            },
            typeDistribution = buckets.filter { it.fileCount > 0 }.sortedByDescending { it.totalBytes },
            extensionStats = extTotals.entries.sortedByDescending { it.value }.take(30)
                .map { it.key to it.value },
            cacheCandidates = cacheCandidates.sortedByDescending { it.sizeBytes },
            tempCandidates = tempCandidates.sortedByDescending { it.sizeBytes },
            scanTimeMs = System.currentTimeMillis() - start,
            truncated = truncated,
            unreadableDirs = unreadable
        )
    }

    private fun directorySize(dir: File, depth: Int): Long {
        if (depth > MAX_DEPTH) return 0
        var sum = 0L
        val children = try { dir.listFiles() } catch (_: Throwable) { null } ?: return 0
        for (c in children) {
            sum += try { if (c.isDirectory) directorySize(c, depth + 1) else c.length() }
            catch (_: Throwable) { 0L }
        }
        return sum
    }

    suspend fun findDuplicates(
        rootPath: String,
        onProgress: (Int) -> Unit = {}
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val bySize = HashMap<Long, MutableList<File>>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(File(rootPath) to 0)
        var seen = 0

        while (stack.isNotEmpty() && seen < DUP_MAX_FILES) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue
            val children = try { dir.listFiles() } catch (_: Throwable) { null } ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c to depth + 1)
                else {
                    val len = try { c.length() } catch (_: Throwable) { 0L }
                    if (len >= DUP_MIN_SIZE) {
                        bySize.getOrPut(len) { ArrayList() }.add(c)
                        seen++
                        if (seen % 100 == 0) onProgress(seen)
                    }
                }
            }
        }

        val groups = ArrayList<DuplicateGroup>()
        for ((size, files) in bySize) {
            if (files.size < 2) continue
            currentCoroutineContext().ensureActive()
            val byHash = HashMap<String, MutableList<String>>()
            for (f in files) {
                val h = partialHash(f) ?: continue
                byHash.getOrPut(h) { ArrayList() }.add(f.absolutePath)
            }
            byHash.filter { it.value.size > 1 }.forEach { (h, paths) ->
                groups.add(DuplicateGroup(h, size, paths))
            }
        }
        groups.sortedByDescending { it.wastedBytes }.take(100)
    }

    private fun partialHash(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            val read = input.read(chunk)
            if (read > 0) md.update(chunk, 0, read)
        }
        if (file.length() > 128 * 1024) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - 64 * 1024)
                val read = raf.read(chunk)
                if (read > 0) md.update(chunk, 0, read)
            }
        }
        md.update(file.length().toString().toByteArray())
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }

    suspend fun deletePath(path: String): Long = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.exists()) return@withContext 0L
        val size = if (f.isDirectory) directorySize(f, 0) else f.length()
        val ok = try { f.deleteRecursively() } catch (_: Throwable) { false }
        if (ok) size else 0L
    }
}
