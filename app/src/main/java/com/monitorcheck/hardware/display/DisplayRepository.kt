package com.monitorcheck.hardware.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import java.util.Locale
import kotlin.math.sqrt

/**
 * Display characteristics from DisplayManager / WindowManager.
 * All values are queried from the platform; nothing is assumed from the model name.
 */
class DisplayRepository(private val context: Context) {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private fun defaultDisplay(): Display? = try {
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    } catch (_: Throwable) { null }

    /** Physical resolution in pixels, excluding system decorations where possible. */
    fun realSize(): Reading<Pair<Int, Int>> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.maximumWindowMetrics.bounds
            Reading.available(bounds.width() to bounds.height(), "WindowMetrics")
        } else {
            val d = defaultDisplay() ?: return Reading.unavailable("No display")
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(m)
            Reading.available(m.widthPixels to m.heightPixels, "Display.getRealMetrics")
        }
    } catch (t: Throwable) {
        Reading.error(t.message)
    }

    fun refreshRate(): Reading<Float> {
        val d = defaultDisplay() ?: return Reading.unavailable("No display")
        val r = d.refreshRate
        return if (r > 0) Reading.available(r, "Display.getRefreshRate") else Reading.unavailable()
    }

    fun supportedModes(): Reading<List<String>> {
        val d = defaultDisplay() ?: return Reading.unavailable("No display")
        return try {
            val modes = d.supportedModes
            if (modes.isNullOrEmpty()) Reading.unavailable()
            else Reading.available(modes.map {
                String.format(Locale.US, "%dx%d @ %.1f Hz",
                    it.physicalWidth, it.physicalHeight, it.refreshRate)
            }, "Display.getSupportedModes")
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    fun brightness(): Reading<String> = try {
        val mode = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        val value = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        when {
            value < 0 -> Reading.unavailable("Brightness setting not readable")
            else -> {
                val modeLabel = when (mode) {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "automatic"
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "manual"
                    else -> "unknown mode"
                }
                // The scale is device-dependent (usually 0-255) so we show the raw value.
                Reading.available("$value (raw, $modeLabel)", "Settings.System.SCREEN_BRIGHTNESS")
            }
        }
    } catch (t: Throwable) {
        Reading.error(t.message)
    }

    fun infoSections(): List<InfoSection> {
        val d = defaultDisplay()
        val metrics = context.resources.displayMetrics
        val size = realSize()

        val basic = ArrayList<InfoItem>()
        basic.add(InfoItem("Resolution", size.map { "${it.first} x ${it.second}" }))
        basic.add(InfoItem("Usable resolution", Reading.available(
            "${metrics.widthPixels} x ${metrics.heightPixels}", "DisplayMetrics")))
        basic.add(InfoItem("Density", Reading.available(
            String.format(Locale.US, "%.2fx", metrics.density), "DisplayMetrics.density")))
        basic.add(InfoItem("DPI (bucket)", Reading.available(
            "${metrics.densityDpi} dpi (${densityBucket(metrics.densityDpi)})", "DisplayMetrics")))
        basic.add(InfoItem("Physical DPI", Reading.available(
            String.format(Locale.US, "x %.1f / y %.1f", metrics.xdpi, metrics.ydpi), "DisplayMetrics")))
        basic.add(InfoItem("Scaled density", Reading.available(
            String.format(Locale.US, "%.2f", metrics.scaledDensity), "DisplayMetrics")))

        val diagonal = size.value?.let { (w, h) ->
            if (metrics.xdpi > 0 && metrics.ydpi > 0) {
                val inches = sqrt((w / metrics.xdpi).toDouble().let { it * it } +
                    (h / metrics.ydpi).toDouble().let { it * it })
                if (inches in 1.0..30.0) inches else null
            } else null
        }
        basic.add(InfoItem("Screen size", diagonal?.let {
            Reading.available(String.format(Locale.US, "%.2f inches", it),
                "Computed from resolution and reported physical DPI")
        } ?: Reading.unavailable("Device does not report usable physical DPI")))

        basic.add(InfoItem("Orientation", Reading.available(
            when (context.resources.configuration.orientation) {
                android.content.res.Configuration.ORIENTATION_PORTRAIT -> "Portrait"
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
                else -> "Undefined"
            }, "Configuration")))
        basic.add(InfoItem("Rotation", d?.let {
            Reading.available(when (it.rotation) {
                android.view.Surface.ROTATION_0 -> "0°"
                android.view.Surface.ROTATION_90 -> "90°"
                android.view.Surface.ROTATION_180 -> "180°"
                else -> "270°"
            }, "Display.getRotation")
        } ?: Reading.unavailable()))

        val refresh = ArrayList<InfoItem>()
        refresh.add(InfoItem("Current refresh rate", refreshRate().map {
            String.format(Locale.US, "%.2f Hz", it) }))
        val modes = supportedModes()
        refresh.add(InfoItem("Supported modes", modes.map { it.joinToString("\n") }))
        refresh.add(InfoItem("Mode count", modes.map { it.size.toString() }))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            refresh.add(InfoItem("Active mode", d?.mode?.let {
                Reading.available(String.format(Locale.US, "%dx%d @ %.2f Hz",
                    it.physicalWidth, it.physicalHeight, it.refreshRate), "Display.getMode")
            } ?: Reading.unavailable()))
        }

        val capabilities = ArrayList<InfoItem>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val hdr = d?.hdrCapabilities
            val types = hdr?.supportedHdrTypes
            capabilities.add(InfoItem("HDR support", if (types != null && types.isNotEmpty())
                Reading.available(types.joinToString(", ") { hdrLabel(it) }, "Display.getHdrCapabilities")
                else Reading.unsupported("No HDR types reported")))
            hdr?.let {
                if (it.desiredMaxLuminance > 0) {
                    capabilities.add(InfoItem("Max luminance", Reading.available(
                        String.format(Locale.US, "%.0f cd/m²", it.desiredMaxLuminance),
                        "HdrCapabilities")))
                }
            }
        } else {
            capabilities.add(InfoItem("HDR support", Reading.unsupported("Requires Android 7.0+")))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            capabilities.add(InfoItem("Wide colour gamut", Reading.available(
                Fmt.yesNo(d?.isWideColorGamut == true), "Display.isWideColorGamut")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // isWideColorGamut / the window colour mode are the only public colour
            // surface; Display.getSupportedColorModes() is a hidden API and is not used.
            capabilities.add(InfoItem("Colour mode support", Reading.available(
                if (d?.isWideColorGamut == true) "Wide colour gamut capable" else "Standard (sRGB)",
                "Display.isWideColorGamut")))
        } else {
            capabilities.add(InfoItem("Colour mode support",
                Reading.unsupported("Requires Android 8.0+")))
        }
        capabilities.add(InfoItem("Brightness", brightness()))
        capabilities.add(InfoItem("Display state", d?.let {
            Reading.available(when (it.state) {
                Display.STATE_ON -> "On"
                Display.STATE_OFF -> "Off"
                Display.STATE_DOZE -> "Doze"
                Display.STATE_DOZE_SUSPEND -> "Doze (suspended)"
                else -> "Unknown"
            }, "Display.getState")
        } ?: Reading.unavailable()))

        return listOf(
            InfoSection("Display", basic),
            InfoSection("Refresh rate", refresh),
            InfoSection("Capabilities", capabilities)
        )
    }

    private fun densityBucket(dpi: Int) = when {
        dpi <= 120 -> "ldpi"
        dpi <= 160 -> "mdpi"
        dpi <= 240 -> "hdpi"
        dpi <= 320 -> "xhdpi"
        dpi <= 480 -> "xxhdpi"
        dpi <= 640 -> "xxxhdpi"
        else -> "custom"
    }

    private fun hdrLabel(type: Int) = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "Type $type"
    }
}
