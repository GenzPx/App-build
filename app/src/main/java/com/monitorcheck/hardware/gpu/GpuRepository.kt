package com.monitorcheck.hardware.gpu

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs
import java.io.File

data class GpuInfo(
    val vendor: String?,
    val renderer: String?,
    val version: String?,
    val glslVersion: String?,
    val extensions: List<String>
)

class GpuRepository(private val context: Context) {

    @Volatile
    private var cached: GpuInfo? = null

    fun queryGl(): Reading<GpuInfo> {
        cached?.let { return Reading.available(it, "OpenGL ES glGetString") }

        var display: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var surface: EGLSurface? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return Reading.unavailable("No EGL display")

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return Reading.unavailable("eglInitialize failed")
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) {
                return Reading.unavailable("No suitable EGL config")
            }

            eglContext = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) return Reading.unavailable("eglCreateContext failed")

            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                return Reading.unavailable("eglMakeCurrent failed")
            }

            val info = GpuInfo(
                vendor = GLES20.glGetString(GLES20.GL_VENDOR),
                renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                version = GLES20.glGetString(GLES20.GL_VERSION),
                glslVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION),
                extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
                    ?.split(" ")?.filter { it.isNotBlank() }?.sorted() ?: emptyList()
            )
            cached = info
            return Reading.available(info, "EGL14 + GLES20.glGetString")
        } catch (t: Throwable) {
            return Reading.error("GL query failed: ${t.message}")
        } finally {
            try {
                if (display != null) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    surface?.let { EGL14.eglDestroySurface(display, it) }
                    eglContext?.let { EGL14.eglDestroyContext(display, it) }
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {  }
        }
    }

    fun glEsVersion(): Reading<String> = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val v = am.deviceConfigurationInfo.glEsVersion
        if (v.isNullOrBlank()) Reading.unavailable() else Reading.available(v, "ConfigurationInfo")
    } catch (t: Throwable) {
        Reading.error(t.message)
    }

    fun vulkanInfo(): List<InfoItem> {
        val pm = context.packageManager
        val items = ArrayList<InfoItem>()
        val hasLevel = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val hasVersion = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

        items.add(InfoItem("Vulkan supported", Reading.available(
            Fmt.yesNo(hasLevel || hasVersion), "PackageManager.hasSystemFeature")))

        if (hasVersion) {
            val feature = pm.systemAvailableFeatures.firstOrNull {
                it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
            }
            val v = feature?.version
            items.add(InfoItem("Vulkan API version", if (v != null && v > 0) {

                val major = (v shr 22) and 0x7F
                val minor = (v shr 12) and 0x3FF
                val patch = v and 0xFFF
                Reading.available("$major.$minor.$patch", "FEATURE_VULKAN_HARDWARE_VERSION")
            } else Reading.unavailable()))
        } else {
            items.add(InfoItem("Vulkan API version", Reading.unsupported("Device reports no Vulkan version")))
        }

        if (hasLevel) {
            val level = pm.systemAvailableFeatures.firstOrNull {
                it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
            }?.version
            items.add(InfoItem("Vulkan hardware level", level?.let {
                Reading.available("Level $it", "FEATURE_VULKAN_HARDWARE_LEVEL")
            } ?: Reading.unavailable()))
        }

        val compute = pm.systemAvailableFeatures.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE
        }?.version
        items.add(InfoItem("Vulkan compute level", compute?.let {
            Reading.available("Level $it", "FEATURE_VULKAN_HARDWARE_COMPUTE")
        } ?: Reading.unsupported()))

        return items
    }

    fun gpuFrequency(): Reading<Long> {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk" to 1L,
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq" to 1L,
            "/sys/kernel/gpu/gpu_clock" to 1000L,
            "/sys/class/devfreq/gpufreq/cur_freq" to 1L,
            "/sys/class/misc/mali0/device/clock" to 1000L
        )
        for ((path, _) in candidates) {
            val raw = SysFs.readLong(path) ?: continue
            if (raw <= 0) continue

            val khz = when {
                raw > 100_000_000 -> raw / 1000
                raw > 100_000 -> raw
                else -> raw * 1000
            }
            return Reading.available(khz, path)
        }
        return Reading.unsupported(
            "Android provides no GPU frequency API. No readable vendor sysfs node was found."
        )
    }

    fun gpuUtilisation(): Reading<Double> {
        SysFs.readFirstLine("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.let { line ->
            val v = line.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
            if (v != null && v in 0.0..100.0) {
                return Reading.available(v, "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            }
        }

        SysFs.readFirstLine("/sys/class/kgsl/kgsl-3d0/gpubusy")?.let { line ->
            val parts = line.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (parts.size >= 2 && parts[1] > 0) {
                val pct = parts[0] * 100.0 / parts[1]
                if (pct in 0.0..100.0) {
                    return Reading.available(pct, "/sys/class/kgsl/kgsl-3d0/gpubusy")
                }
            }
        }
        SysFs.readFirstLine("/sys/kernel/gpu/gpu_busy")?.let { line ->
            line.filter { it.isDigit() }.toDoubleOrNull()?.let {
                if (it in 0.0..100.0) return Reading.available(it, "/sys/kernel/gpu/gpu_busy")
            }
        }
        SysFs.readFirstLine("/sys/class/misc/mali0/device/utilization")?.let { line ->
            line.toDoubleOrNull()?.let {
                if (it in 0.0..100.0) return Reading.available(it, "/sys/class/misc/mali0/device/utilization")
            }
        }
        return Reading.unsupported(
            "Android has no public GPU utilisation API and this device exposes no readable " +
                "vendor node. Monitored Check will not display an invented value."
        )
    }

    fun gpuTemperature(): Reading<Double> {
        val zones = SysFs.listDir("/sys/class/thermal") { it.startsWith("thermal_zone") }
        for (zone in zones) {
            val type = SysFs.readFirstLine(File(zone, "type").absolutePath)?.lowercase() ?: continue
            if (type.contains("gpu") || type.contains("kgsl") || type.contains("mali")) {
                val raw = SysFs.readLong(File(zone, "temp").absolutePath) ?: continue
                val c = when {
                    raw > 10_000 -> raw / 1000.0
                    raw > 1_000 -> raw / 100.0
                    raw > 200 -> raw / 10.0
                    else -> raw.toDouble()
                }
                if (c in -40.0..150.0) return Reading.available(c, "${zone.absolutePath}/temp")
            }
        }
        return Reading.unavailable("No GPU thermal zone exported")
    }

    fun infoSections(): List<InfoSection> {
        val gl = queryGl()
        val info = gl.value

        val identity = listOf(
            InfoItem("Vendor", info?.vendor?.let { Reading.available(it, "GL_VENDOR") }
                ?: Reading(gl.status, null, gl.note)),
            InfoItem("Renderer / model", info?.renderer?.let { Reading.available(it, "GL_RENDERER") }
                ?: Reading(gl.status, null, gl.note)),
            InfoItem("OpenGL ES version", info?.version?.let { Reading.available(it, "GL_VERSION") }
                ?: glEsVersion()),
            InfoItem("Reported ES version", glEsVersion()),
            InfoItem("GLSL version", info?.glslVersion?.let {
                Reading.available(it, "GL_SHADING_LANGUAGE_VERSION")
            } ?: Reading.unavailable()),
            InfoItem("Driver information", info?.version?.let {
                Reading.limited(it, "OpenGL version string is the only driver identifier exposed to apps",
                    "GL_VERSION")
            } ?: Reading.unavailable("No driver string available to apps"))
        )

        val runtime = listOf(
            InfoItem("GPU utilisation", gpuUtilisation().map { Fmt.percent(it) }),
            InfoItem("GPU frequency", gpuFrequency().map { Fmt.freqKHz(it) }),
            InfoItem("GPU temperature", gpuTemperature().map { Fmt.temperature(it) })
        )

        val extensions = info?.extensions.orEmpty()
        val capabilities = listOf(
            InfoItem("Extension count", if (extensions.isNotEmpty())
                Reading.available(extensions.size.toString(), "GL_EXTENSIONS") else Reading.unavailable()),
            InfoItem("Compressed textures (ETC2)", Reading.available(
                Fmt.yesNo(extensions.any { it.contains("ETC", true) || it.contains("etc2", true) }),
                "GL_EXTENSIONS")),
            InfoItem("ASTC support", Reading.available(
                Fmt.yesNo(extensions.any { it.contains("astc", true) }), "GL_EXTENSIONS")),
            InfoItem("Float textures", Reading.available(
                Fmt.yesNo(extensions.any { it.contains("float", true) }), "GL_EXTENSIONS"))
        )

        return listOf(
            InfoSection("GPU identity", identity),
            InfoSection(
                "Runtime metrics", runtime,
                note = "Android exposes no official GPU load or clock API to applications. " +
                    "These values are read from vendor kernel nodes when the device makes them " +
                    "world-readable; otherwise they correctly show Unsupported."
            ),
            InfoSection("Graphics capabilities", capabilities + vulkanInfo()),
            InfoSection(
                "OpenGL extensions",
                if (extensions.isEmpty()) listOf(InfoItem("Extensions", Reading.unavailable()))
                else extensions.map { InfoItem(it, Reading.available("Supported", "GL_EXTENSIONS")) }
            )
        )
    }
}
