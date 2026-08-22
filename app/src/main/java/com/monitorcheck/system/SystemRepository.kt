package com.monitorcheck.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs
import java.io.File

class SystemRepository(private val context: Context) {

    fun systemProperty(key: String): String? = try {
        @Suppress("PrivateApi")
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    fun deviceSections(): List<InfoSection> {
        val pm = context.packageManager

        val device = listOf(
            InfoItem.of("Manufacturer", Build.MANUFACTURER),
            InfoItem.of("Brand", Build.BRAND),
            InfoItem.of("Model", Build.MODEL),
            InfoItem.of("Device codename", Build.DEVICE),
            InfoItem.of("Product", Build.PRODUCT),
            InfoItem.of("Board", Build.BOARD),
            InfoItem.of("Hardware", Build.HARDWARE),
            InfoItem("SoC", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Build.SOC_MODEL != Build.UNKNOWN)
                Reading.available("${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}", "Build.SOC_MODEL")
                else systemProperty("ro.board.platform")?.let { Reading.available(it, "ro.board.platform") }
                    ?: Reading.unavailable("Requires Android 12+ or a readable platform property")),
            InfoItem.of("Device type", deviceType()),
            InfoItem.of("Build type", Build.TYPE),
            InfoItem.of("Build tags", Build.TAGS),
            InfoItem.of("Bootloader", Build.BOOTLOADER),
            InfoItem.of("Radio / baseband", Build.getRadioVersion())
        )

        val android = listOf(
            InfoItem.of("Android version", Build.VERSION.RELEASE),
            InfoItem.of("API level (SDK)", Build.VERSION.SDK_INT.toString()),
            InfoItem.of("Codename", Build.VERSION.CODENAME),
            InfoItem.of("Incremental", Build.VERSION.INCREMENTAL),
            InfoItem("Security patch", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Reading.available(Build.VERSION.SECURITY_PATCH, "Build.VERSION.SECURITY_PATCH")
                else Reading.unsupported("Requires Android 6.0+")),
            InfoItem.of("Build ID", Build.ID),
            InfoItem.of("Display build", Build.DISPLAY),
            InfoItem.of("Fingerprint", Build.FINGERPRINT),
            InfoItem.of("Host", Build.HOST),
            InfoItem.of("User", Build.USER),
            InfoItem("Build time", Reading.available(Fmt.timestamp(Build.TIME), "Build.TIME")),
            InfoItem.of("Primary ABI", Build.SUPPORTED_ABIS.firstOrNull()),
            InfoItem.of("Supported ABIs", Build.SUPPORTED_ABIS.joinToString(", ")),
            InfoItem("Treble enabled", systemProperty("ro.treble.enabled")
                ?.let { Reading.available(it, "ro.treble.enabled") } ?: Reading.unavailable()),
            InfoItem("Vendor patch", systemProperty("ro.vendor.build.security_patch")
                ?.let { Reading.available(it, "ro.vendor.build.security_patch") } ?: Reading.unavailable()),
            InfoItem("64-bit userspace", Reading.available(
                Fmt.yesNo(Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()), "Build"))
        )

        val features = listOf(
            InfoItem("Bluetooth", featureItem(PackageManager.FEATURE_BLUETOOTH)),
            InfoItem("Bluetooth LE", featureItem(PackageManager.FEATURE_BLUETOOTH_LE)),
            InfoItem("Wi-Fi", featureItem(PackageManager.FEATURE_WIFI)),
            InfoItem("Wi-Fi Direct", featureItem(PackageManager.FEATURE_WIFI_DIRECT)),
            InfoItem("NFC", featureItem(PackageManager.FEATURE_NFC)),
            InfoItem("Telephony", featureItem(PackageManager.FEATURE_TELEPHONY)),
            InfoItem("GPS", featureItem(PackageManager.FEATURE_LOCATION_GPS)),
            InfoItem("Camera (any)", featureItem(PackageManager.FEATURE_CAMERA_ANY)),
            InfoItem("Camera flash", featureItem(PackageManager.FEATURE_CAMERA_FLASH)),
            InfoItem("Fingerprint", featureItem(PackageManager.FEATURE_FINGERPRINT)),
            InfoItem("Face authentication", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                featureItem(PackageManager.FEATURE_FACE) else Reading.unsupported("Requires Android 10+")),
            InfoItem("Iris authentication", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                featureItem(PackageManager.FEATURE_IRIS) else Reading.unsupported("Requires Android 10+")),
            InfoItem("USB host", featureItem(PackageManager.FEATURE_USB_HOST)),
            InfoItem("USB accessory", featureItem(PackageManager.FEATURE_USB_ACCESSORY)),
            InfoItem("Microphone", featureItem(PackageManager.FEATURE_MICROPHONE)),
            InfoItem("Low latency audio", featureItem(PackageManager.FEATURE_AUDIO_LOW_LATENCY)),
            InfoItem("Pro audio", featureItem(PackageManager.FEATURE_AUDIO_PRO)),
            InfoItem("MIDI", featureItem(PackageManager.FEATURE_MIDI)),
            InfoItem("Vulkan hardware", featureItem(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)),
            InfoItem("SIP/VoIP", featureItem(PackageManager.FEATURE_SIP)),
            InfoItem("Touchscreen multitouch", featureItem(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH)),
            InfoItem("Jazzhand multitouch (5+)",
                featureItem(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND))
        )

        return listOf(
            InfoSection("Device", device),
            InfoSection("Android build", android),
            InfoSection("Hardware features", features,
                note = "Reported by PackageManager.hasSystemFeature(). This reflects what the " +
                    "device declares to the framework.")
        )
    }

    private fun featureItem(feature: String): Reading<String> = try {
        Reading.available(Fmt.yesNo(context.packageManager.hasSystemFeature(feature)),
            "PackageManager.hasSystemFeature")
    } catch (t: Throwable) {
        Reading.error(t.message)
    }

    private fun deviceType(): String {
        val pm = context.packageManager
        return when {
            pm.hasSystemFeature(PackageManager.FEATURE_WATCH) -> "Watch"
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> "TV"
            pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) -> "Automotive"
            pm.hasSystemFeature(PackageManager.FEATURE_PC) -> "PC"
            (context.resources.configuration.screenLayout and
                android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >=
                android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE -> "Tablet / large screen"
            else -> "Phone / handheld"
        }
    }

    fun kernelSections(): List<InfoSection> {
        val version = SysFs.readText("/proc/version", useFailureCache = false)
        val uptimeMs = SystemClock.elapsedRealtime()

        val kernel = listOf(
            InfoItem.of("Kernel version", System.getProperty("os.version")),
            InfoItem("Full kernel string", version?.let { Reading.available(it, "/proc/version") }
                ?: Reading.restricted("/proc/version not readable")),
            InfoItem.of("Kernel name", System.getProperty("os.name")),
            InfoItem.of("Architecture", System.getProperty("os.arch")),
            InfoItem("Kernel build", version?.let { v ->
                Regex("#\\d+.*?(?=\\s+(?:SMP|PREEMPT|Mon|Tue|Wed|Thu|Fri|Sat|Sun))")
                    .find(v)?.value?.let { Reading.available(it, "/proc/version") }
            } ?: Reading.unavailable()),
            InfoItem("Compiler", version?.let { v ->
                Regex("\\(([^)]*(?:gcc|clang)[^)]*)\\)", RegexOption.IGNORE_CASE)
                    .find(v)?.groupValues?.get(1)?.let { Reading.available(it, "/proc/version") }
            } ?: Reading.unavailable()),
            InfoItem("Command line", SysFs.readText("/proc/cmdline", useFailureCache = false)
                ?.let { Reading.available(it, "/proc/cmdline") }
                ?: Reading.restricted("/proc/cmdline is not readable by unprivileged apps on this device")),
            InfoItem("Page size", Reading.available(
                "${systemProperty("ro.product.page_size") ?: "4096"} bytes (typical)", "system property"))
        )

        val boot = listOf(
            InfoItem("Uptime", Reading.available(Fmt.duration(uptimeMs), "SystemClock.elapsedRealtime")),
            InfoItem("Uptime (awake)", Reading.available(
                Fmt.duration(SystemClock.uptimeMillis()), "SystemClock.uptimeMillis")),
            InfoItem("Deep sleep", Reading.available(
                Fmt.duration(uptimeMs - SystemClock.uptimeMillis()),
                "Computed: elapsedRealtime - uptimeMillis")),
            InfoItem("Boot time", Reading.available(
                Fmt.timestamp(System.currentTimeMillis() - uptimeMs), "Computed from uptime")),
            InfoItem("Boot reason", systemProperty("sys.boot.reason")
                ?.let { Reading.available(it, "sys.boot.reason") } ?: Reading.unavailable()),
            InfoItem("Bootloader state", systemProperty("ro.boot.verifiedbootstate")
                ?.let { Reading.available(verifiedBootLabel(it), "ro.boot.verifiedbootstate") }
                ?: Reading.unavailable("Not exposed on this device")),
            InfoItem("Verified boot", systemProperty("ro.boot.veritymode")
                ?.let { Reading.available(it, "ro.boot.veritymode") } ?: Reading.unavailable()),
            InfoItem("Device locked", systemProperty("ro.boot.flash.locked")
                ?.let { Reading.available(if (it == "1") "Locked" else "Unlocked", "ro.boot.flash.locked") }
                ?: Reading.unavailable()),
            InfoItem("Slot suffix (A/B)", systemProperty("ro.boot.slot_suffix")
                ?.let { Reading.available(it, "ro.boot.slot_suffix") }
                ?: Reading.unavailable("Device may not use A/B partitions"))
        )

        return listOf(
            InfoSection("Kernel", kernel),
            InfoSection("Boot & uptime", boot,
                note = "Bootloader and verified-boot properties are only present when the vendor " +
                    "exports them as readable system properties.")
        )
    }

    fun selinuxSection(): InfoSection {
        val enforce = SysFs.readFirstLine("/sys/fs/selinux/enforce")
        val state = when (enforce) {
            "1" -> Reading.available("Enforcing", "/sys/fs/selinux/enforce")
            "0" -> Reading.available("Permissive", "/sys/fs/selinux/enforce")
            else -> {
                val prop = systemProperty("ro.boot.selinux")
                if (prop != null) Reading.available(prop.replaceFirstChar { it.uppercase() }, "ro.boot.selinux")
                else if (File("/sys/fs/selinux").exists())
                    Reading.limited("Present but state not readable",
                        "The enforce node exists but is not readable by unprivileged apps",
                        "/sys/fs/selinux")
                else Reading.unavailable("SELinux filesystem not present")
            }
        }

        val context = SysFs.readText("/proc/self/attr/current", useFailureCache = false)
            ?.trim { it <= ' ' || it == '\u0000' }

        return InfoSection(
            "SELinux",
            listOf(
                InfoItem("State", state),
                InfoItem("Filesystem present", Reading.available(
                    Fmt.yesNo(File("/sys/fs/selinux").exists()), "File check")),
                InfoItem("This app's security context", context?.let {
                    Reading.available(it, "/proc/self/attr/current")
                } ?: Reading.restricted("Process security context not readable")),
                InfoItem("Policy version", SysFs.readFirstLine("/sys/fs/selinux/policyvers")
                    ?.let { Reading.available(it, "/sys/fs/selinux/policyvers") }
                    ?: Reading.unavailable()),
                InfoItem("Boot SELinux mode", systemProperty("ro.boot.selinux")
                    ?.let { Reading.available(it, "ro.boot.selinux") } ?: Reading.unavailable())
            ),
            note = "Monitored Check only reads SELinux state. It never attempts to change the " +
                "policy or switch to permissive mode."
        )
    }

    fun integritySection(): InfoSection {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/local/xbin/su", "/data/local/bin/su"
        )
        val found = suPaths.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        val testKeys = Build.TAGS?.contains("test-keys") == true

        return InfoSection(
            "System integrity indicators",
            listOf(
                InfoItem("Root binaries detected", if (found.isEmpty())
                    Reading.available("None found in common locations", "Filesystem check")
                    else Reading.available(found.joinToString("\n"), "Filesystem check")),
                InfoItem("Build signed with test-keys", Reading.available(
                    Fmt.yesNo(testKeys), "Build.TAGS")),
                InfoItem("Debuggable build", Reading.available(
                    systemProperty("ro.debuggable") ?: "0", "ro.debuggable")),
                InfoItem("ADB secure", Reading.available(
                    systemProperty("ro.secure") ?: "unknown", "ro.secure")),
                InfoItem("App uses root", Reading.available(
                    "No — Monitored Check never requests root", "Application policy"))
            ),
            note = "These are heuristic observations of publicly readable paths, not a tamper " +
                "attestation. Monitored Check works fully without root and does not attempt to " +
                "gain elevated privileges."
        )
    }

    fun binderSection(): InfoSection {
        val items = ArrayList<InfoItem>()
        val binderPaths = listOf(
            "/sys/kernel/debug/binder/stats",
            "/sys/kernel/debug/binder/state",
            "/sys/kernel/debug/binder/transactions",
            "/dev/binder"
        )
        var anyReadable = false
        for (path in binderPaths) {
            val f = File(path)
            val exists = runCatching { f.exists() }.getOrDefault(false)
            val readable = exists && runCatching { f.canRead() }.getOrDefault(false)
            if (readable && path.startsWith("/sys")) {
                val content = SysFs.readText(path, useFailureCache = false)
                if (content != null) {
                    anyReadable = true
                    items.add(InfoItem(path, Reading.available(
                        content.lines().take(40).joinToString("\n"), path)))
                    continue
                }
            }
            items.add(InfoItem(path, when {
                !exists -> Reading.unavailable("Node not present")
                else -> Reading.restricted(
                    "Binder debug nodes live under debugfs, which is unmounted or SELinux-protected " +
                        "for applications.")
            }))
        }

        items.add(InfoItem("Binder devices", Reading.available(
            listOf("/dev/binder", "/dev/hwbinder", "/dev/vndbinder")
                .filter { runCatching { File(it).exists() }.getOrDefault(false) }
                .ifEmpty { listOf("none visible") }.joinToString(", "),
            "Filesystem check")))

        return InfoSection(
            "Binder IPC", items,
            note = if (anyReadable) "Partial Binder statistics are readable on this device."
            else "Binder information is restricted on this Android version. Detailed Binder " +
                "statistics require debugfs access, which Android reserves for privileged " +
                "processes. Monitored Check does not attempt to bypass this restriction."
        )
    }

    fun processCount(): Reading<Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Reading.unavailable("ActivityManager unavailable")
        return try {
            val procs = am.runningAppProcesses
            if (procs.isNullOrEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Reading.limited(1, "Android 8+ only reports this app's own processes",
                        "ActivityManager.getRunningAppProcesses")
                } else Reading.unavailable()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Reading.limited(procs.size,
                        "Android 8+ restricts this list to the caller's own processes",
                        "ActivityManager.getRunningAppProcesses")
                } else {
                    Reading.available(procs.size, "ActivityManager.getRunningAppProcesses")
                }
            }
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    fun rootDataFree(): Long = try {
        val stat = android.os.StatFs(Environment.getDataDirectory().absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Throwable) { -1L }

    private fun verifiedBootLabel(v: String) = when (v.lowercase()) {
        "green" -> "Green — verified boot, locked, OEM keys"
        "yellow" -> "Yellow — verified with user-supplied key"
        "orange" -> "Orange — bootloader unlocked, verification off"
        "red" -> "Red — verification failed"
        else -> v
    }
}
