package com.monitorcheck.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Permissions
import com.monitorcheck.core.Reading
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/** Byte counters + computed rates. Rates are only produced from two real samples. */
data class NetworkThroughput(
    val rxBytes: Long,
    val txBytes: Long,
    val rxRateBps: Double,
    val txRateBps: Double,
    val elapsedMs: Long
)

data class InterfaceStats(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val mtu: Int,
    val addresses: List<String>,
    val hardwareAddress: String?,
    val rxBytes: Long?,
    val txBytes: Long?
)

/**
 * Network information and throughput.
 *
 * Throughput uses TrafficStats total byte counters, which are always available to
 * apps. Per-interface counters come from /sys/class/net/<iface>/statistics when the
 * kernel exports them. MAC addresses are hard-blocked by Android 6+ for third-party
 * apps — we report that restriction rather than a fake or constant value.
 */
class NetworkRepository(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastSampleTime = 0L

    /** Samples global byte counters and derives rates from the delta. */
    fun sampleThroughput(): NetworkThroughput? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return null
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val prevRx = lastRx
        val prevTx = lastTx
        val prevTime = lastSampleTime
        lastRx = rx; lastTx = tx; lastSampleTime = now

        if (prevRx < 0 || prevTime == 0L) {
            // First sample: counters are real, rates are not computable yet.
            return NetworkThroughput(rx, tx, 0.0, 0.0, 0L)
        }
        val elapsed = now - prevTime
        if (elapsed <= 0) return NetworkThroughput(rx, tx, 0.0, 0.0, 0L)
        val seconds = elapsed / 1000.0
        return NetworkThroughput(
            rxBytes = rx,
            txBytes = tx,
            rxRateBps = ((rx - prevRx).coerceAtLeast(0)) / seconds,
            txRateBps = ((tx - prevTx).coerceAtLeast(0)) / seconds,
            elapsedMs = elapsed
        )
    }

    fun resetThroughputBaseline() { lastRx = -1; lastTx = -1; lastSampleTime = 0 }

    /** Active transport type as a readable label. */
    fun activeConnectionType(): Reading<String> {
        val cm = connectivityManager ?: return Reading.unavailable("ConnectivityManager unavailable")
        return try {
            val network = cm.activeNetwork ?: return Reading.available("Disconnected", "ConnectivityManager")
            val caps = cm.getNetworkCapabilities(network)
                ?: return Reading.available("Unknown", "ConnectivityManager")
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Other"
            }
            Reading.available(type, "NetworkCapabilities")
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    fun capabilitiesSection(): InfoSection {
        val cm = connectivityManager
        val items = ArrayList<InfoItem>()
        if (cm == null) {
            items.add(InfoItem("Connectivity", Reading.unavailable("ConnectivityManager unavailable")))
            return InfoSection("Connection", items)
        }
        try {
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            items.add(InfoItem("Connection type", activeConnectionType()))
            items.add(InfoItem("State", Reading.available(
                if (network == null) "Disconnected" else "Connected", "ConnectivityManager")))
            if (caps != null) {
                items.add(InfoItem("Internet capability", Reading.available(
                    Fmt.yesNo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)),
                    "NetworkCapabilities")))
                items.add(InfoItem("Validated", Reading.available(
                    Fmt.yesNo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)),
                    "NetworkCapabilities")))
                items.add(InfoItem("Not metered", Reading.available(
                    Fmt.yesNo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)),
                    "NetworkCapabilities")))
                items.add(InfoItem("Not roaming", Reading.available(
                    Fmt.yesNo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)),
                    "NetworkCapabilities")))
                items.add(InfoItem("VPN active", Reading.available(
                    Fmt.yesNo(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)),
                    "NetworkCapabilities")))
                val down = caps.linkDownstreamBandwidthKbps
                val up = caps.linkUpstreamBandwidthKbps
                items.add(InfoItem("Reported downstream", if (down > 0)
                    Reading.limited(Fmt.bitsPerSecond(down * 1000.0),
                        "Estimate reported by the system, not a measurement", "NetworkCapabilities")
                    else Reading.unavailable()))
                items.add(InfoItem("Reported upstream", if (up > 0)
                    Reading.limited(Fmt.bitsPerSecond(up * 1000.0),
                        "Estimate reported by the system, not a measurement", "NetworkCapabilities")
                    else Reading.unavailable()))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    items.add(InfoItem("Signal strength (dBm)",
                        if (caps.signalStrength != Int.MIN_VALUE)
                            Reading.available("${caps.signalStrength} dBm", "NetworkCapabilities")
                        else Reading.unavailable()))
                }
            }
        } catch (t: Throwable) {
            items.add(InfoItem("Connectivity", Reading.error(t.message)))
        }
        return InfoSection("Connection", items)
    }

    fun linkPropertiesSection(): InfoSection {
        val cm = connectivityManager
        val items = ArrayList<InfoItem>()
        val lp: LinkProperties? = try {
            cm?.activeNetwork?.let { cm.getLinkProperties(it) }
        } catch (_: Throwable) { null }

        if (lp == null) {
            items.add(InfoItem("Link properties", Reading.unavailable("No active network")))
            return InfoSection("IP configuration", items)
        }

        val v4 = lp.linkAddresses.filter { it.address is Inet4Address }
        val v6 = lp.linkAddresses.filter { it.address is Inet6Address }

        items.add(InfoItem("Interface", Reading.available(lp.interfaceName ?: "Unknown", "LinkProperties")))
        items.add(InfoItem.of("IPv4 address", v4.joinToString(", ") {
            "${it.address.hostAddress}/${it.prefixLength}" }.ifBlank { null }))
        items.add(InfoItem.of("IPv6 address", v6.joinToString(", ") {
            "${it.address.hostAddress}/${it.prefixLength}" }.ifBlank { null }))
        items.add(InfoItem.of("Subnet prefix", v4.firstOrNull()?.let { "/${it.prefixLength}" }))
        items.add(InfoItem.of("DNS servers", lp.dnsServers.joinToString(", ") {
            it.hostAddress ?: "" }.ifBlank { null }))
        items.add(InfoItem.of("Domains", lp.domains))
        items.add(InfoItem.of("Gateway / routes", lp.routes.mapNotNull { r ->
            r.gateway?.hostAddress?.takeIf { !r.isDefaultRoute || it.isNotBlank() }
                ?.let { gw -> "${r.destination} via $gw" }
        }.joinToString("\n").ifBlank { null }))
        items.add(InfoItem("MTU", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && lp.mtu > 0)
            Reading.available(lp.mtu.toString(), "LinkProperties") else Reading.unavailable()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            items.add(InfoItem("Private DNS", Reading.available(
                if (lp.isPrivateDnsActive) "Active (${lp.privateDnsServerName ?: "automatic"})" else "Inactive",
                "LinkProperties")))
        }
        return InfoSection("IP configuration", items)
    }

    fun wifiSection(): InfoSection {
        val items = ArrayList<InfoItem>()
        val wm = wifiManager
        if (wm == null) {
            items.add(InfoItem("Wi-Fi", Reading.noHardware("No WifiManager on this device")))
            return InfoSection("Wi-Fi", items)
        }
        items.add(InfoItem("Wi-Fi enabled", Reading.available(Fmt.yesNo(wm.isWifiEnabled), "WifiManager")))

        @Suppress("DEPRECATION")
        val info = try { wm.connectionInfo } catch (_: Throwable) { null }

        if (info == null || info.networkId == -1 && info.bssid == null) {
            items.add(InfoItem("Connection", Reading.unavailable("Not connected to Wi-Fi")))
            return InfoSection("Wi-Fi", items)
        }

        val hasLocation = Permissions.hasLocation(context)
        // Android 8.1+ requires location permission for SSID/BSSID. Without it the
        // platform returns "<unknown ssid>" / 02:00:00:00:00:00 — we must not show those.
        if (hasLocation) {
            val ssid = info.ssid?.trim('"')
            items.add(InfoItem("SSID", if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>")
                Reading.available(ssid, "WifiInfo") else Reading.unavailable()))
            items.add(InfoItem("BSSID", info.bssid?.takeIf { it != "02:00:00:00:00:00" }
                ?.let { Reading.available(it, "WifiInfo") }
                ?: Reading.restricted("BSSID hidden by the platform")))
        } else {
            items.add(InfoItem("SSID", Reading.permission(
                "Android requires location permission to read the connected SSID")))
            items.add(InfoItem("BSSID", Reading.permission(
                "Android requires location permission to read the BSSID")))
        }

        items.add(InfoItem("Signal strength", Reading.available("${info.rssi} dBm", "WifiInfo.rssi")))
        items.add(InfoItem("Signal level", Reading.available(
            "${WifiManager.calculateSignalLevel(info.rssi, 5)}/4", "WifiManager.calculateSignalLevel")))
        items.add(InfoItem("Link speed", if (info.linkSpeed > 0)
            Reading.available("${info.linkSpeed} Mbps", "WifiInfo.linkSpeed") else Reading.unavailable()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            items.add(InfoItem("RX link speed", if (info.rxLinkSpeedMbps > 0)
                Reading.available("${info.rxLinkSpeedMbps} Mbps", "WifiInfo") else Reading.unavailable()))
            items.add(InfoItem("TX link speed", if (info.txLinkSpeedMbps > 0)
                Reading.available("${info.txLinkSpeedMbps} Mbps", "WifiInfo") else Reading.unavailable()))
        }
        items.add(InfoItem("Frequency", if (info.frequency > 0)
            Reading.available("${info.frequency} MHz (${bandOf(info.frequency)})", "WifiInfo.frequency")
            else Reading.unavailable()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            items.add(InfoItem("Wi-Fi standard", Reading.available(
                wifiStandardLabel(info.wifiStandard), "WifiInfo.wifiStandard")))
        }
        items.add(InfoItem("Hidden SSID", Reading.available(Fmt.yesNo(info.hiddenSSID), "WifiInfo")))
        items.add(InfoItem("MAC address", Reading.restricted(
            "Android 6+ returns a per-network randomised MAC to apps. The real hardware " +
                "address is not readable without privileged permissions.")))

        return InfoSection("Wi-Fi", items)
    }

    fun mobileSection(): InfoSection {
        val items = ArrayList<InfoItem>()
        val tm = telephonyManager
        if (tm == null || !context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_TELEPHONY)) {
            items.add(InfoItem("Telephony", Reading.noHardware("No telephony hardware")))
            return InfoSection("Mobile network", items)
        }
        try {
            items.add(InfoItem.of("Network operator", tm.networkOperatorName?.ifBlank { null }))
            items.add(InfoItem.of("Operator code (MCC/MNC)", tm.networkOperator?.ifBlank { null }))
            items.add(InfoItem.of("SIM operator", tm.simOperatorName?.ifBlank { null }))
            items.add(InfoItem.of("SIM country", tm.simCountryIso?.uppercase()?.ifBlank { null }))
            items.add(InfoItem.of("Network country", tm.networkCountryIso?.uppercase()?.ifBlank { null }))
            items.add(InfoItem("SIM state", Reading.available(simStateLabel(tm.simState), "TelephonyManager")))
            items.add(InfoItem("Phone type", Reading.available(phoneTypeLabel(tm.phoneType), "TelephonyManager")))
            items.add(InfoItem("Roaming", Reading.available(
                Fmt.yesNo(tm.isNetworkRoaming), "TelephonyManager")))

            // getDataNetworkType needs READ_PHONE_STATE from API 30 onward.
            val netType = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Permissions.hasPhoneState(context)) {
                try { Reading.available(networkTypeLabel(tm.dataNetworkType), "TelephonyManager") }
                catch (_: SecurityException) { Reading.permission("Requires READ_PHONE_STATE") }
            } else Reading.permission("Requires READ_PHONE_STATE on Android 11+")
            items.add(InfoItem("Data network type", netType))

            items.add(InfoItem("Subscriber ID / IMEI", Reading.restricted(
                "Android 10+ blocks non-system apps from reading device identifiers")))
        } catch (t: Throwable) {
            items.add(InfoItem("Telephony", Reading.error(t.message)))
        }
        return InfoSection("Mobile network", items)
    }

    /** Enumerates interfaces via the standard NetworkInterface API + kernel counters. */
    fun interfaces(): Reading<List<InterfaceStats>> = try {
        val list = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            InterfaceStats(
                name = ni.name,
                isUp = runCatching { ni.isUp }.getOrDefault(false),
                isLoopback = runCatching { ni.isLoopback }.getOrDefault(false),
                mtu = runCatching { ni.mtu }.getOrDefault(-1),
                addresses = ni.inetAddresses.toList().mapNotNull { it.hostAddress },
                // Hardware address is null for third-party apps on Android 6+.
                hardwareAddress = runCatching {
                    ni.hardwareAddress?.joinToString(":") { b -> "%02X".format(b) }
                }.getOrNull(),
                rxBytes = com.monitorcheck.core.SysFs.readLong("/sys/class/net/${ni.name}/statistics/rx_bytes"),
                txBytes = com.monitorcheck.core.SysFs.readLong("/sys/class/net/${ni.name}/statistics/tx_bytes")
            )
        }
        if (list.isEmpty()) Reading.unavailable("No interfaces enumerated")
        else Reading.available(list, "java.net.NetworkInterface + /sys/class/net")
    } catch (t: Throwable) {
        Reading.error(t.message)
    }

    fun trafficSection(): InfoSection {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val mRx = TrafficStats.getMobileRxBytes()
        val mTx = TrafficStats.getMobileTxBytes()
        val uid = android.os.Process.myUid()
        val unsupported = TrafficStats.UNSUPPORTED.toLong()

        fun item(label: String, v: Long, src: String) = InfoItem(
            label,
            if (v == unsupported || v < 0) Reading.unavailable("Counter not supported")
            else Reading.available(Fmt.bytes(v), src)
        )

        return InfoSection(
            "Traffic since boot",
            listOf(
                item("Total received", rx, "TrafficStats"),
                item("Total transmitted", tx, "TrafficStats"),
                item("Mobile received", mRx, "TrafficStats"),
                item("Mobile transmitted", mTx, "TrafficStats"),
                item("This app received", TrafficStats.getUidRxBytes(uid), "TrafficStats (own UID)"),
                item("This app transmitted", TrafficStats.getUidTxBytes(uid), "TrafficStats (own UID)")
            ),
            note = "Counters are cumulative since the last boot and are provided by the kernel."
        )
    }

    private fun bandOf(freqMhz: Int) = when {
        freqMhz in 2400..2500 -> "2.4 GHz"
        freqMhz in 4900..5900 -> "5 GHz"
        freqMhz in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun wifiStandardLabel(v: Int) = when (v) {
        1 -> "802.11a/b/g (legacy)"
        4 -> "802.11n (Wi-Fi 4)"
        5 -> "802.11ac (Wi-Fi 5)"
        6 -> "802.11ax (Wi-Fi 6)"
        7 -> "802.11ad"
        8 -> "802.11be (Wi-Fi 7)"
        else -> "Unknown"
    }

    private fun simStateLabel(v: Int) = when (v) {
        TelephonyManager.SIM_STATE_ABSENT -> "Absent"
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "Permanently disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card IO error"
        else -> "Unknown"
    }

    private fun phoneTypeLabel(v: Int) = when (v) {
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        TelephonyManager.PHONE_TYPE_NONE -> "None"
        else -> "Unknown"
    }

    private fun networkTypeLabel(v: Int) = when (v) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA (3G)"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA (3G)"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA (3G)"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+ (3G)"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
        TelephonyManager.NETWORK_TYPE_NR -> "NR (5G)"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
        else -> "Type $v"
    }
}
