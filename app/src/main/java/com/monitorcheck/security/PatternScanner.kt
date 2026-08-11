package com.monitorcheck.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.monitorcheck.core.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class RiskLevel(val label: String, val rank: Int) {
    SAFE("Safe", 0),
    LOW("Low Risk", 1),
    SUSPICIOUS("Suspicious", 2),
    HIGH("High Risk", 3),
    UNKNOWN("Unknown", -1)
}

/** A single reason contributing to the risk score, always shown to the user. */
data class Finding(
    val title: String,
    val detail: String,
    val weight: Int
)

data class ScanResult(
    val target: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val md5: String?,
    val riskLevel: RiskLevel,
    val score: Int,
    val findings: List<Finding>,
    val metadata: List<Pair<String, String>>,
    val scanTimeMs: Long,
    val error: String? = null
)

/**
 * Pattern Scanner — a local, transparent, heuristic inspector.
 *
 * THIS IS NOT AN ANTIVIRUS. It does not detect malware by name, has no cloud
 * reputation service, and cannot guarantee a file is safe or unsafe. What it does:
 *
 *  - computes real cryptographic hashes (SHA-256, MD5) of the chosen file
 *  - parses real APK metadata through PackageManager
 *  - flags genuinely dangerous permission combinations
 *  - flags real structural indicators (native libs, dynamic code loading, hidden
 *    executables inside archives, test-key signing, debuggable flags)
 *  - explains every single point of the score
 *
 * Everything runs on-device. Nothing is uploaded, ever.
 */
class PatternScanner(private val context: Context) {

    companion object {
        /**
         * Permissions that meaningfully raise risk when an app requests them.
         * Weights reflect how much abuse potential each one carries in combination.
         */
        private val DANGEROUS_PERMISSIONS = mapOf(
            "android.permission.SEND_SMS" to 12,
            "android.permission.RECEIVE_SMS" to 10,
            "android.permission.READ_SMS" to 12,
            "android.permission.CALL_PHONE" to 8,
            "android.permission.PROCESS_OUTGOING_CALLS" to 10,
            "android.permission.READ_CALL_LOG" to 10,
            "android.permission.WRITE_CALL_LOG" to 10,
            "android.permission.RECORD_AUDIO" to 9,
            "android.permission.CAMERA" to 7,
            "android.permission.ACCESS_FINE_LOCATION" to 6,
            "android.permission.READ_CONTACTS" to 7,
            "android.permission.WRITE_CONTACTS" to 7,
            "android.permission.SYSTEM_ALERT_WINDOW" to 11,
            "android.permission.REQUEST_INSTALL_PACKAGES" to 13,
            "android.permission.BIND_ACCESSIBILITY_SERVICE" to 15,
            "android.permission.BIND_DEVICE_ADMIN" to 15,
            "android.permission.MANAGE_EXTERNAL_STORAGE" to 9,
            "android.permission.READ_PHONE_STATE" to 4,
            "android.permission.RECEIVE_BOOT_COMPLETED" to 3,
            "android.permission.DISABLE_KEYGUARD" to 6,
            "android.permission.QUERY_ALL_PACKAGES" to 4
        )

        /** Byte/string patterns that indicate risky runtime behaviour. */
        private val CODE_PATTERNS = listOf(
            Triple("DexClassLoader", "Dynamic code loading (DexClassLoader)",
                "The app can load and execute code that is not present in the APK at install time."),
            Triple("PathClassLoader", "Runtime class loading (PathClassLoader)",
                "Custom class loading detected. Common in packers and plugin frameworks."),
            Triple("Runtime.getRuntime().exec", "Shell command execution",
                "The app can spawn shell commands."),
            Triple("su -c", "Root shell invocation string",
                "A root shell command pattern is present in the binary."),
            Triple("/system/bin/su", "Root binary reference",
                "The app references the su binary path."),
            Triple("android.permission.BIND_ACCESSIBILITY_SERVICE", "Accessibility service",
                "Accessibility can read screen content and perform actions on the user's behalf."),
            Triple("javax.crypto.Cipher", "Runtime cryptography",
                "Encryption APIs present. Normal for many apps, but also used to hide payloads."),
            Triple("Base64.decode", "Base64 decoding of embedded data",
                "Often used to conceal strings, URLs or payloads.")
        )

        private val ARCHIVE_RED_FLAGS = listOf(
            "classes2.dex", "classes3.dex", "assets/payload", "lib/armeabi/libsu",
            "assets/dex", "res/raw/payload", ".dex.jar"
        )
    }

    /** Scans an arbitrary file. APKs get full package analysis; others get hashing + type checks. */
    suspend fun scanFile(file: File): ScanResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        if (!file.exists() || !file.canRead()) {
            return@withContext ScanResult(
                file.absolutePath, file.name, 0, null, null, RiskLevel.UNKNOWN, 0,
                listOf(Finding("Not readable",
                    "The file does not exist or the app has no permission to read it.", 0)),
                emptyList(), System.currentTimeMillis() - start,
                error = "File not readable"
            )
        }

        val findings = ArrayList<Finding>()
        val metadata = ArrayList<Pair<String, String>>()
        var score = 0

        val sha = hashFile(file, "SHA-256")
        val md5 = hashFile(file, "MD5")

        metadata.add("Path" to file.absolutePath)
        metadata.add("Size" to Fmt.bytes(file.length()))
        metadata.add("Last modified" to Fmt.timestamp(file.lastModified()))
        metadata.add("Extension" to (file.extension.ifBlank { "(none)" }))

        val isApk = file.extension.equals("apk", true) ||
            file.extension.equals("xapk", true) ||
            file.extension.equals("apks", true)

        if (isApk) {
            val apkFindings = analyzeApk(file, metadata)
            findings.addAll(apkFindings)
            score += apkFindings.sumOf { it.weight }
        } else {
            // Non-APK: check for executable content and archive contents.
            if (isZip(file)) {
                metadata.add("Container" to "ZIP-based archive")
                val zipFindings = analyzeArchive(file)
                findings.addAll(zipFindings)
                score += zipFindings.sumOf { it.weight }
            }
            if (isElf(file)) {
                findings.add(Finding("Native executable (ELF)",
                    "The file is a native binary. Executable content from an untrusted source " +
                        "should be treated with caution.", 10))
                score += 10
                metadata.add("Format" to "ELF native binary")
            }
            if (file.extension.lowercase() in setOf("dex", "jar", "so")) {
                findings.add(Finding("Executable code file",
                    "File type '${file.extension}' contains runnable code.", 8))
                score += 8
            }
        }

        if (findings.isEmpty()) {
            findings.add(Finding("No risk indicators found",
                "None of the scanner's structural or permission heuristics matched this file.", 0))
        }

        ScanResult(
            target = file.absolutePath,
            displayName = file.name,
            sizeBytes = file.length(),
            sha256 = sha,
            md5 = md5,
            riskLevel = levelFor(score),
            score = score,
            findings = findings.sortedByDescending { it.weight },
            metadata = metadata,
            scanTimeMs = System.currentTimeMillis() - start
        )
    }

    /** Inspects an installed package using PackageManager metadata. */
    suspend fun scanInstalledPackage(packageName: String): ScanResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val pm = context.packageManager
        val findings = ArrayList<Finding>()
        val metadata = ArrayList<Pair<String, String>>()
        var score = 0

        try {
            val flags = PackageManager.GET_PERMISSIONS
            val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(packageName, flags)
            }
            val ai = info.applicationInfo
                ?: return@withContext errorResult(packageName, "No application info", start)
            val apk = File(ai.sourceDir)

            metadata.add("Package" to packageName)
            metadata.add("Label" to pm.getApplicationLabel(ai).toString())
            metadata.add("Version" to "${info.versionName} (${
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                else @Suppress("DEPRECATION") info.versionCode
            })")
            metadata.add("Target SDK" to ai.targetSdkVersion.toString())
            metadata.add("UID" to ai.uid.toString())
            metadata.add("APK path" to ai.sourceDir)
            metadata.add("Installed" to Fmt.timestamp(info.firstInstallTime))
            metadata.add("Updated" to Fmt.timestamp(info.lastUpdateTime))
            metadata.add("Installer" to (installerOf(packageName) ?: "Unknown / sideloaded"))

            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            // Permission analysis on the genuinely requested + granted permissions.
            val requested = info.requestedPermissions?.toList().orEmpty()
            val grantedFlags = info.requestedPermissionsFlags
            val granted = requested.filterIndexed { i, _ ->
                grantedFlags != null && i < grantedFlags.size &&
                    (grantedFlags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            }

            val risky = requested.filter { it in DANGEROUS_PERMISSIONS }
            for (p in risky) {
                val weight = DANGEROUS_PERMISSIONS[p] ?: 0
                val isGranted = p in granted
                // Only granted permissions carry full weight; requested-but-denied is milder.
                val effective = if (isGranted) weight else weight / 3
                score += effective
                findings.add(Finding(
                    "Sensitive permission: ${p.substringAfterLast('.')}",
                    "${if (isGranted) "GRANTED" else "Requested but not granted"} — $p",
                    effective
                ))
            }

            if (risky.size >= 6) {
                score += 10
                findings.add(Finding("Broad permission surface",
                    "This app requests ${risky.size} sensitive permissions. Large combinations " +
                        "of high-privilege permissions increase the potential impact if the app " +
                        "is malicious or compromised.", 10))
            }

            if ((ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                score += 8
                findings.add(Finding("Debuggable build",
                    "The app is marked debuggable. Release apps should not ship this flag: " +
                        "it allows attaching a debugger and reading app internals.", 8))
            }

            if (ai.targetSdkVersion < 23) {
                score += 12
                findings.add(Finding("Very old target SDK (${ai.targetSdkVersion})",
                    "Apps targeting below API 23 bypass the runtime permission model — all " +
                        "permissions are granted at install time.", 12))
            } else if (ai.targetSdkVersion < 29) {
                score += 5
                findings.add(Finding("Outdated target SDK (${ai.targetSdkVersion})",
                    "The app opts out of several modern privacy protections such as scoped storage.", 5))
            }

            if (!isSystem && installerOf(packageName) == null) {
                score += 6
                findings.add(Finding("Sideloaded (no installer recorded)",
                    "This app was not installed by a recognised app store. Verify you trust its source.", 6))
            }

            if (isSystem) {
                findings.add(Finding("System application",
                    "Installed as part of the system image. System apps are signed by the device " +
                        "vendor and are not user-removable.", 0))
            }

            // Structural analysis of the actual APK file on disk.
            if (apk.canRead()) {
                val archiveFindings = analyzeArchive(apk)
                findings.addAll(archiveFindings)
                score += archiveFindings.sumOf { it.weight }
                metadata.add("APK size" to Fmt.bytes(apk.length()))
                metadata.add("APK SHA-256" to (hashFile(apk, "SHA-256") ?: "Unavailable"))
            } else {
                metadata.add("APK access" to "Not readable (protected by Android)")
            }

            if (findings.none { it.weight > 0 }) {
                findings.add(Finding("No risk indicators found",
                    "No sensitive permission combinations or structural red flags matched.", 0))
            }

            ScanResult(
                target = packageName,
                displayName = pm.getApplicationLabel(ai).toString(),
                sizeBytes = if (apk.canRead()) apk.length() else 0,
                sha256 = if (apk.canRead()) hashFile(apk, "SHA-256") else null,
                md5 = null,
                riskLevel = levelFor(score),
                score = score,
                findings = findings.sortedByDescending { it.weight },
                metadata = metadata,
                scanTimeMs = System.currentTimeMillis() - start
            )
        } catch (t: Throwable) {
            errorResult(packageName, t.message ?: t.javaClass.simpleName, start)
        }
    }

    private fun analyzeApk(file: File, metadata: MutableList<Pair<String, String>>): List<Finding> {
        val findings = ArrayList<Finding>()
        val pm = context.packageManager
        val info = try {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_PERMISSIONS)
        } catch (_: Throwable) { null }

        if (info == null) {
            findings.add(Finding("APK could not be parsed",
                "PackageManager could not read this APK. It may be corrupt, encrypted, or not " +
                    "a valid package.", 10))
            return findings
        }

        metadata.add("Package" to info.packageName)
        metadata.add("Version" to (info.versionName ?: "Unknown"))
        info.applicationInfo?.let { ai ->
            metadata.add("Target SDK" to ai.targetSdkVersion.toString())
            if ((ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                findings.add(Finding("Debuggable APK",
                    "This package is marked debuggable, which is unusual for a distributed release.", 8))
            }
            if (ai.targetSdkVersion < 23) {
                findings.add(Finding("Pre-runtime-permission target SDK (${ai.targetSdkVersion})",
                    "All requested permissions would be granted at install time.", 12))
            }
        }

        val perms = info.requestedPermissions?.toList().orEmpty()
        metadata.add("Permissions requested" to perms.size.toString())
        perms.filter { it in DANGEROUS_PERMISSIONS }.forEach { p ->
            val w = DANGEROUS_PERMISSIONS[p] ?: 0
            findings.add(Finding("Sensitive permission: ${p.substringAfterLast('.')}",
                "Declared in the manifest: $p", w))
        }

        findings.addAll(analyzeArchive(file))
        return findings
    }

    /** Looks inside a ZIP/APK for structural indicators. Read-only. */
    private fun analyzeArchive(file: File): List<Finding> {
        val findings = ArrayList<Finding>()
        try {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()

                val nativeLibs = names.filter { it.startsWith("lib/") && it.endsWith(".so") }
                if (nativeLibs.isNotEmpty()) {
                    val abis = nativeLibs.mapNotNull {
                        it.removePrefix("lib/").substringBefore('/').takeIf { a -> a.isNotBlank() }
                    }.distinct()
                    findings.add(Finding("Native libraries present (${nativeLibs.size})",
                        "Contains compiled native code for: ${abis.joinToString(", ")}. Native code " +
                            "cannot be inspected by this scanner and is common in both legitimate " +
                            "and malicious apps.", 3))
                }

                val extraDex = names.count { it.matches(Regex("classes\\d*\\.dex")) }
                if (extraDex > 3) {
                    findings.add(Finding("Many DEX files ($extraDex)",
                        "A high number of DEX files can indicate a packed or obfuscated app.", 5))
                }

                ARCHIVE_RED_FLAGS.forEach { flag ->
                    if (names.any { it.contains(flag, ignoreCase = true) }) {
                        findings.add(Finding("Suspicious archive entry",
                            "Found an entry matching '$flag', a pattern associated with payload " +
                                "staging or dynamic loading.", 9))
                    }
                }

                val hiddenExec = names.filter {
                    (it.startsWith("assets/") || it.startsWith("res/raw/")) &&
                        (it.endsWith(".dex") || it.endsWith(".jar") || it.endsWith(".apk") ||
                            it.endsWith(".so"))
                }
                if (hiddenExec.isNotEmpty()) {
                    findings.add(Finding("Executable code in assets (${hiddenExec.size})",
                        "Code files stored under assets/ or res/raw/ are loaded at runtime and " +
                            "escape static review: ${hiddenExec.take(3).joinToString(", ")}", 11))
                }

                // Scan the primary DEX for behavioural strings.
                zip.getEntry("classes.dex")?.let { entry ->
                    if (entry.size in 1..(40 * 1024 * 1024)) {
                        val content = zip.getInputStream(entry).use { input ->
                            String(input.readBytes(), Charsets.ISO_8859_1)
                        }
                        CODE_PATTERNS.forEach { (needle, title, detail) ->
                            if (content.contains(needle)) {
                                val w = when (needle) {
                                    "su -c", "/system/bin/su" -> 14
                                    "DexClassLoader" -> 10
                                    "Runtime.getRuntime().exec" -> 8
                                    "android.permission.BIND_ACCESSIBILITY_SERVICE" -> 12
                                    else -> 2
                                }
                                findings.add(Finding(title, detail, w))
                            }
                        }
                    }
                }

                val signed = names.any { it.startsWith("META-INF/") &&
                    (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }
                val v2Signed = names.isNotEmpty() && !signed
                if (!signed && file.extension.equals("apk", true)) {
                    findings.add(Finding("No v1 signature block",
                        if (v2Signed) "The APK has no META-INF v1 signature. It may use v2/v3 " +
                            "signing only, which this scanner cannot verify without installing it."
                        else "No signature files found.", 4))
                }
            }
        } catch (t: Throwable) {
            findings.add(Finding("Archive could not be fully read",
                "Error while inspecting the archive: ${t.javaClass.simpleName}. " +
                    "Partial results only.", 2))
        }
        return findings
    }

    /** Real SHA-256 / MD5 of the file contents, streamed so large files are safe. */
    fun hashFile(file: File, algorithm: String): String? = try {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }

    /** Scans every readable file in a directory tree. Cancellable. */
    suspend fun scanFolder(
        dir: File,
        maxFiles: Int = 300,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val results = ArrayList<ScanResult>()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        var count = 0
        while (stack.isNotEmpty() && count < maxFiles) {
            currentCoroutineContext().ensureActive()
            val current = stack.removeLast()
            val children = try { current.listFiles() } catch (_: Throwable) { null } ?: continue
            for (child in children) {
                if (count >= maxFiles) break
                currentCoroutineContext().ensureActive()
                if (child.isDirectory) {
                    stack.add(child)
                } else {
                    // Only scan file types where the heuristics are meaningful.
                    val ext = child.extension.lowercase()
                    if (ext in setOf("apk", "xapk", "apks", "dex", "jar", "so", "zip", "bin", "sh")) {
                        count++
                        onProgress(child.name, count)
                        results.add(scanFile(child))
                    }
                }
            }
        }
        results.sortedByDescending { it.score }
    }

    private fun installerOf(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(packageName)
        }
    } catch (_: Throwable) { null }

    private fun isZip(file: File): Boolean = try {
        file.inputStream().use { it.readNBytes(4) }
            .let { it.size == 4 && it[0] == 0x50.toByte() && it[1] == 0x4B.toByte() }
    } catch (_: Throwable) { false }

    private fun isElf(file: File): Boolean = try {
        file.inputStream().use { it.readNBytes(4) }
            .let { it.size == 4 && it[0] == 0x7F.toByte() && it[1] == 'E'.code.toByte() &&
                it[2] == 'L'.code.toByte() && it[3] == 'F'.code.toByte() }
    } catch (_: Throwable) { false }

    private fun errorResult(target: String, message: String, start: Long) = ScanResult(
        target, target, 0, null, null, RiskLevel.UNKNOWN, 0,
        listOf(Finding("Scan failed", message, 0)), emptyList(),
        System.currentTimeMillis() - start, error = message
    )

    /** Maps the accumulated weight to a level. Thresholds are documented in the UI. */
    fun levelFor(score: Int): RiskLevel = when {
        score <= 0 -> RiskLevel.SAFE
        score < 15 -> RiskLevel.LOW
        score < 35 -> RiskLevel.SUSPICIOUS
        else -> RiskLevel.HIGH
    }

    val disclaimer: String = """
        Pattern Scanner is a transparent local heuristic tool, not an antivirus.

        It does not use malware signatures, cloud reputation, or behavioural sandboxing,
        and it cannot certify that a file is safe. It computes real hashes, reads real
        package metadata, and flags permission and structural patterns that are known to
        carry risk — showing you every reason behind the score.

        A "High Risk" result does not prove an app is malicious, and "Safe" does not prove
        it is harmless. Use it as one input alongside the source you obtained the file from.

        All analysis happens on this device. Nothing is uploaded.
    """.trimIndent()
}
