package com.monitorcheck.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.monitorcheck.core.DataStatus
import com.monitorcheck.core.Reading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PermissionGroupKind(val label: String) {
    CAMERA("Camera"),
    MICROPHONE("Microphone"),
    LOCATION("Location"),
    STORAGE("Storage & media"),
    CONTACTS("Contacts"),
    PHONE("Phone & SMS"),
    SENSORS("Sensors & body"),
    NOTIFICATIONS("Notifications"),
    NETWORK("Network"),
    CALENDAR("Calendar"),
    SPECIAL("Special access"),
    OTHER("Other")
}

data class PermissionState(
    val permission: String,
    val shortName: String,
    val group: PermissionGroupKind,
    val status: DataStatus,
    val protectionLevel: String,
    val description: String?
)

data class AppPermissionSummary(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val granted: List<PermissionState>,
    val denied: List<PermissionState>
) {
    val sensitiveGrantedCount: Int
        get() = granted.count { it.protectionLevel.contains("dangerous", true) }
}

class PermissionInspector(private val context: Context) {

    private val pm = context.packageManager

    private fun groupOf(permission: String): PermissionGroupKind = when {
        permission.contains("CAMERA") -> PermissionGroupKind.CAMERA
        permission.contains("RECORD_AUDIO") || permission.contains("MICROPHONE") ->
            PermissionGroupKind.MICROPHONE
        permission.contains("LOCATION") -> PermissionGroupKind.LOCATION
        permission.contains("STORAGE") || permission.contains("MEDIA_") ||
            permission.contains("READ_MEDIA") -> PermissionGroupKind.STORAGE
        permission.contains("CONTACTS") || permission.contains("ACCOUNTS") ->
            PermissionGroupKind.CONTACTS
        permission.contains("SMS") || permission.contains("CALL") ||
            permission.contains("PHONE") -> PermissionGroupKind.PHONE
        permission.contains("BODY_SENSORS") || permission.contains("ACTIVITY_RECOGNITION") ->
            PermissionGroupKind.SENSORS
        permission.contains("NOTIFICATION") -> PermissionGroupKind.NOTIFICATIONS
        permission.contains("INTERNET") || permission.contains("NETWORK") ||
            permission.contains("WIFI") || permission.contains("BLUETOOTH") ->
            PermissionGroupKind.NETWORK
        permission.contains("CALENDAR") -> PermissionGroupKind.CALENDAR
        permission.contains("SYSTEM_ALERT_WINDOW") || permission.contains("MANAGE_") ||
            permission.contains("PACKAGE_USAGE_STATS") || permission.contains("BIND_") ->
            PermissionGroupKind.SPECIAL
        else -> PermissionGroupKind.OTHER
    }

    private fun protectionLevelLabel(info: PermissionInfo?): String {
        if (info == null) return "Unknown"
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when (info.protection) {
                PermissionInfo.PROTECTION_NORMAL -> "normal"
                PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                PermissionInfo.PROTECTION_INTERNAL -> "internal"
                else -> "unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            when (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) {
                PermissionInfo.PROTECTION_NORMAL -> "normal"
                PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                else -> "unknown"
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.protectionFlags else 0
        val extras = buildList {
            if (flags and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) add("privileged")
            if (flags and PermissionInfo.PROTECTION_FLAG_APPOP != 0) add("appop")
            if (flags and PermissionInfo.PROTECTION_FLAG_PRE23 != 0) add("pre23")
            if (flags and PermissionInfo.PROTECTION_FLAG_INSTANT != 0) add("instant")
        }
        return if (extras.isEmpty()) base else "$base|${extras.joinToString("|")}"
    }

    private fun permissionInfo(name: String): PermissionInfo? = try {

        pm.getPermissionInfo(name, 0)
    } catch (_: Throwable) { null }

    suspend fun inspectAll(): List<AppPermissionSummary> = withContext(Dispatchers.IO) {
        val packages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }
        } catch (_: Throwable) { emptyList() }

        packages.mapNotNull { info ->
            val ai = info.applicationInfo ?: return@mapNotNull null
            val requested = info.requestedPermissions ?: return@mapNotNull null
            val flags = info.requestedPermissionsFlags
            val granted = ArrayList<PermissionState>()
            val denied = ArrayList<PermissionState>()

            requested.forEachIndexed { i, perm ->
                val pi = permissionInfo(perm)
                val isGranted = flags != null && i < flags.size &&
                    (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                val state = PermissionState(
                    permission = perm,
                    shortName = perm.substringAfterLast('.'),
                    group = groupOf(perm),
                    status = if (isGranted) DataStatus.AVAILABLE else DataStatus.NOT_REQUESTED,
                    protectionLevel = protectionLevelLabel(pi),
                    description = try { pi?.loadDescription(pm)?.toString() } catch (_: Throwable) { null }
                )
                if (isGranted) granted.add(state) else denied.add(state)
            }

            AppPermissionSummary(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(ai).toString() }
                    .getOrDefault(info.packageName),
                isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                granted = granted,
                denied = denied
            )
        }.sortedByDescending { it.sensitiveGrantedCount }
    }

    suspend fun appsHolding(permission: String): List<AppPermissionSummary> =
        inspectAll().filter { s -> s.granted.any { it.permission == permission } }

    suspend fun groupSummary(): Map<PermissionGroupKind, List<Pair<String, Int>>> =
        withContext(Dispatchers.IO) {
            val all = inspectAll()
            val byGroup = HashMap<PermissionGroupKind, HashMap<String, Int>>()
            all.forEach { app ->
                app.granted.forEach { p ->
                    val map = byGroup.getOrPut(p.group) { HashMap() }
                    map[p.shortName] = (map[p.shortName] ?: 0) + 1
                }
            }
            byGroup.mapValues { (_, v) -> v.entries.sortedByDescending { it.value }.map { it.key to it.value } }
        }

    fun ownPermissions(): List<PermissionState> = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val requested = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags
        requested.mapIndexed { i, perm ->
            val isGranted = flags != null && i < flags.size &&
                (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            val pi = permissionInfo(perm)
            PermissionState(
                permission = perm,
                shortName = perm.substringAfterLast('.'),
                group = groupOf(perm),
                status = if (isGranted) DataStatus.AVAILABLE else DataStatus.NOT_REQUESTED,
                protectionLevel = protectionLevelLabel(pi),
                description = try { pi?.loadDescription(pm)?.toString() } catch (_: Throwable) { null }
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun usageHistoryAvailability(): Reading<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Reading.restricted(
            "Android 12+ records permission usage in the system Privacy Dashboard, but the " +
                "underlying API is restricted to system components. Monitored Check can show " +
                "current grant state only, and offers a shortcut to the system dashboard."
        )
        else -> Reading.unsupported(
            "Permission usage history requires Android 12+ and is a system-only feature."
        )
    }

    fun privacyDashboardIntent(): android.content.Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.content.Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        } else null
}
