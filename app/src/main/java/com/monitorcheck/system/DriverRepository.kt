package com.monitorcheck.system

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs
import java.io.File

class DriverRepository(private val context: Context) {

    private val systemRepo = SystemRepository(context)

    fun sections(): List<InfoSection> = listOf(
        graphicsSection(),
        displaySection(),
        audioSection(),
        cameraSection(),
        wirelessSection(),
        usbSection(),
        storageSection(),
        inputSection(),
        kernelModulesSection()
    )

    private fun graphicsSection(): InfoSection {
        val gpu = com.monitorcheck.hardware.gpu.GpuRepository(context).queryGl()
        return InfoSection(
            "Graphics driver",
            listOf(
                InfoItem("Renderer", gpu.value?.renderer?.let { Reading.available(it, "GL_RENDERER") }
                    ?: Reading.unavailable()),
                InfoItem("Driver version string",
                    gpu.value?.version?.let { Reading.available(it, "GL_VERSION") }
                        ?: Reading.unavailable()),
                InfoItem("Vendor", gpu.value?.vendor?.let { Reading.available(it, "GL_VENDOR") }
                    ?: Reading.unavailable()),
                InfoItem("EGL vendor", systemRepo.systemProperty("ro.hardware.egl")
                    ?.let { Reading.available(it, "ro.hardware.egl") } ?: Reading.unavailable()),
                InfoItem("Vulkan driver", systemRepo.systemProperty("ro.hardware.vulkan")
                    ?.let { Reading.available(it, "ro.hardware.vulkan") } ?: Reading.unavailable()),
                InfoItem("KGSL (Adreno) node", Reading.available(
                    Fmt.yesNo(File("/sys/class/kgsl/kgsl-3d0").exists()), "Filesystem check")),
                InfoItem("Mali node", Reading.available(
                    Fmt.yesNo(File("/sys/class/misc/mali0").exists()), "Filesystem check"))
            )
        )
    }

    private fun displaySection(): InfoSection {
        val panels = SysFs.listDir("/sys/class/graphics") { it.startsWith("fb") }
        return InfoSection(
            "Display driver",
            buildList {
                add(InfoItem("Framebuffer devices", if (panels.isEmpty())
                    Reading.unavailable("No readable framebuffer entries")
                    else Reading.available(panels.joinToString(", ") { it.name }, "/sys/class/graphics")))
                panels.take(2).forEach { fb ->
                    add(InfoItem("${fb.name} name", SysFs.readFirstLine("${fb.absolutePath}/name")
                        ?.let { Reading.available(it, fb.absolutePath) } ?: Reading.unavailable()))
                    add(InfoItem("${fb.name} modes", SysFs.readFirstLine("${fb.absolutePath}/modes")
                        ?.let { Reading.available(it, fb.absolutePath) } ?: Reading.unavailable()))
                }
                val panelType = systemRepo.systemProperty("ro.boot.lcd_type")
                    ?: systemRepo.systemProperty("ro.boot.panel")
                add(InfoItem("Panel type", panelType?.let { Reading.available(it, "system property") }
                    ?: Reading.unavailable("Not exported by this device")))
                add(InfoItem("DRM devices", Reading.available(
                    SysFs.listDir("/sys/class/drm").joinToString(", ") { it.name }
                        .ifBlank { "none readable" }, "/sys/class/drm")))
            }
        )
    }

    private fun audioSection(): InfoSection {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val items = ArrayList<InfoItem>()
        items.add(InfoItem("Audio HAL property", systemRepo.systemProperty("ro.hardware.audio.primary")
            ?.let { Reading.available(it, "ro.hardware.audio.primary") } ?: Reading.unavailable()))
        if (am != null) {
            items.add(InfoItem("Output sample rate", am.getProperty(
                AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.let {
                Reading.available("$it Hz", "AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE")
            } ?: Reading.unavailable()))
            items.add(InfoItem("Output frames per buffer", am.getProperty(
                AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.let {
                Reading.available(it, "AudioManager")
            } ?: Reading.unavailable()))
            items.add(InfoItem("Low latency audio", Reading.available(Fmt.yesNo(
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)),
                "PackageManager")))
            items.add(InfoItem("Pro audio", Reading.available(Fmt.yesNo(
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)),
                "PackageManager")))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = try {
                    am.getDevices(AudioManager.GET_DEVICES_ALL).map { d ->
                        "${audioDeviceLabel(d.type)}${if (d.isSource) " (in)" else " (out)"}"
                    }.distinct()
                } catch (_: Throwable) { emptyList() }
                items.add(InfoItem("Audio devices", if (devices.isEmpty()) Reading.unavailable()
                    else Reading.available(devices.joinToString("\n"), "AudioManager.getDevices")))
            }
        } else {
            items.add(InfoItem("AudioManager", Reading.unavailable()))
        }
        items.add(InfoItem("ALSA sound cards", SysFs.readText("/proc/asound/cards", false)
            ?.let { Reading.available(it, "/proc/asound/cards") }
            ?: Reading.unavailable("Not readable")))
        return InfoSection("Audio", items)
    }

    private fun cameraSection(): InfoSection {
        val items = ArrayList<InfoItem>()
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            items.add(InfoItem("Camera", Reading.noHardware("No CameraManager")))
            return InfoSection("Camera", items)
        }
        try {
            val ids = cm.cameraIdList
            items.add(InfoItem("Camera count", Reading.available(ids.size.toString(), "CameraManager")))
            ids.forEach { id ->
                val ch = cm.getCameraCharacteristics(id)
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    else -> "Unknown"
                }
                val size = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val mp = size?.let { it.width.toLong() * it.height / 1_000_000.0 }
                val hwLevel = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "Unknown"
                }
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.joinToString(", ") { "${it}mm" }
                items.add(InfoItem("Camera $id", Reading.available(buildString {
                    append("$facing facing, HAL $hwLevel")
                    if (mp != null) append(", ${String.format(java.util.Locale.US, "%.1f", mp)} MP")
                    if (size != null) append(" (${size.width}x${size.height})")
                    if (focal != null) append("\nFocal lengths: $focal")
                }, "CameraCharacteristics")))
            }
        } catch (t: Throwable) {
            items.add(InfoItem("Camera enumeration", Reading.error(t.message)))
        }
        return InfoSection("Camera", items)
    }

    private fun wirelessSection(): InfoSection {
        val pm = context.packageManager
        val wifiModule = systemRepo.systemProperty("ro.hardware.wlan")
            ?: systemRepo.systemProperty("wlan.driver.status")
        return InfoSection(
            "Wi-Fi & Bluetooth",
            listOf(
                InfoItem("Wi-Fi driver property", systemRepo.systemProperty("wifi.interface")
                    ?.let { Reading.available(it, "wifi.interface") } ?: Reading.unavailable()),
                InfoItem("Wi-Fi module", wifiModule?.let { Reading.available(it, "system property") }
                    ?: Reading.unavailable()),
                InfoItem("Wi-Fi interfaces", Reading.available(
                    SysFs.listDir("/sys/class/net") { it.startsWith("wlan") || it.startsWith("wifi") }
                        .joinToString(", ") { it.name }.ifBlank { "none visible" }, "/sys/class/net")),
                InfoItem("Bluetooth supported", Reading.available(
                    Fmt.yesNo(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)), "PackageManager")),
                InfoItem("Bluetooth LE", Reading.available(
                    Fmt.yesNo(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)), "PackageManager")),
                InfoItem("Bluetooth stack", systemRepo.systemProperty("ro.bluetooth.hfp.ver")
                    ?.let { Reading.available("HFP $it", "system property") }
                    ?: Reading.unavailable()),
                InfoItem("NFC", Reading.available(
                    Fmt.yesNo(pm.hasSystemFeature(PackageManager.FEATURE_NFC)), "PackageManager"))
            )
        )
    }

    private fun usbSection(): InfoSection {
        val pm = context.packageManager
        return InfoSection(
            "USB",
            listOf(
                InfoItem("USB host mode", Reading.available(
                    Fmt.yesNo(pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)), "PackageManager")),
                InfoItem("USB accessory mode", Reading.available(
                    Fmt.yesNo(pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)), "PackageManager")),
                InfoItem("USB controller", systemRepo.systemProperty("sys.usb.controller")
                    ?.let { Reading.available(it, "sys.usb.controller") } ?: Reading.unavailable()),
                InfoItem("USB state", systemRepo.systemProperty("sys.usb.state")
                    ?.let { Reading.available(it, "sys.usb.state") } ?: Reading.unavailable()),
                InfoItem("USB config", systemRepo.systemProperty("sys.usb.config")
                    ?.let { Reading.available(it, "sys.usb.config") } ?: Reading.unavailable()),
                InfoItem("Connected USB devices", run {
                    val um = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
                    val list = try { um?.deviceList?.values?.toList() } catch (_: Throwable) { null }
                    when {
                        list == null -> Reading.unavailable("UsbManager unavailable")
                        list.isEmpty() -> Reading.available("None connected", "UsbManager")
                        else -> Reading.available(list.joinToString("\n") {
                            "${it.productName ?: "Device"} (vendor ${it.vendorId}, product ${it.productId})"
                        }, "UsbManager.getDeviceList")
                    }
                })
            )
        )
    }

    private fun storageSection(): InfoSection {
        val blocks = SysFs.listDir("/sys/block") {
            it.startsWith("mmcblk") || it.startsWith("sd") || it.startsWith("nvme") ||
                it.startsWith("zram")
        }
        return InfoSection(
            "Storage controller",
            buildList {
                add(InfoItem("Block devices", if (blocks.isEmpty()) Reading.unavailable()
                    else Reading.available(blocks.joinToString(", ") { it.name }, "/sys/block")))
                add(InfoItem("eMMC/UFS type", systemRepo.systemProperty("ro.boot.bootdevice")
                    ?.let { Reading.available(it, "ro.boot.bootdevice") } ?: Reading.unavailable()))
                blocks.filter { !it.name.startsWith("zram") }.take(3).forEach { b ->
                    val size = SysFs.readLong("${b.absolutePath}/size")?.times(512)
                    add(InfoItem("${b.name} size", size?.let {
                        Reading.available(Fmt.bytes(it), "${b.absolutePath}/size")
                    } ?: Reading.unavailable()))
                }
                add(InfoItem("Filesystems supported", SysFs.readText("/proc/filesystems", false)
                    ?.lines()?.mapNotNull { it.trim().substringAfterLast('\t').ifBlank { null } }
                    ?.joinToString(", ")?.let { Reading.available(it, "/proc/filesystems") }
                    ?: Reading.unavailable()))
            }
        )
    }

    private fun inputSection(): InfoSection {
        val devices = SysFs.readText("/proc/bus/input/devices", false)
        val items = ArrayList<InfoItem>()
        if (devices != null) {

            val names = Regex("N: Name=\"([^\"]+)\"").findAll(devices).map { it.groupValues[1] }.toList()
            items.add(InfoItem("Input devices (${names.size})", if (names.isEmpty())
                Reading.unavailable() else Reading.available(names.joinToString("\n"),
                "/proc/bus/input/devices")))
        } else {
            items.add(InfoItem("Input devices",
                Reading.restricted("/proc/bus/input/devices is not readable on this device")))
        }
        val im = context.getSystemService(Context.INPUT_SERVICE) as? android.hardware.input.InputManager
        val ids: IntArray? = try { im?.inputDeviceIds } catch (_: Throwable) { null }
        val frameworkDevices: Reading<String> = if (ids == null) {
            Reading.unavailable("InputManager unavailable")
        } else {
            val names = ids.toList().mapNotNull { id ->
                try { im?.getInputDevice(id)?.name } catch (_: Throwable) { null }
            }
            Reading.available(names.joinToString("\n").ifBlank { "none" }, "InputManager")
        }
        items.add(InfoItem("Framework input devices", frameworkDevices))
        items.add(InfoItem("Touchscreen", Reading.available(
            Fmt.yesNo(context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_TOUCHSCREEN)), "PackageManager")))
        return InfoSection("Input", items)
    }

    private fun kernelModulesSection(): InfoSection {
        val modules = SysFs.readText("/proc/modules", useFailureCache = false)
        return InfoSection(
            "Kernel modules",
            if (modules == null) {
                listOf(InfoItem("Loaded modules", Reading.restricted(
                    "/proc/modules is not readable by unprivileged apps on this device. " +
                        "A full driver list requires root, which Monitored Check does not use.")))
            } else {
                val lines = modules.lines().filter { it.isNotBlank() }
                listOf(
                    InfoItem("Module count", Reading.available(lines.size.toString(), "/proc/modules")),
                    InfoItem("Loaded modules", Reading.available(
                        lines.take(60).joinToString("\n") { it.substringBefore(' ') }, "/proc/modules"))
                )
            },
            note = "Android does not provide a driver enumeration API to applications. This page " +
                "shows the subsystem capabilities each framework API reports plus any kernel " +
                "nodes that are world-readable on this device."
        )
    }

    private fun audioDeviceLabel(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        else -> "Type $type"
    }
}
