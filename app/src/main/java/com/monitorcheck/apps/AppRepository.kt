package com.monitorcheck.apps

import android.app.usage.StorageStats
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import com.monitorcheck.core.Permissions
import com.monitorcheck.core.Reading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val targetSdk: Int,
    val minSdk: Int?,
    val uid: Int,
    val apkSizeBytes: Long,
    val appSizeBytes: Long?,
    val dataSizeBytes: Long?,
    val cacheSizeBytes: Long?,
    val lastUsedTime: Long?,
    val permissions: List<String>,
    val sourceDir: String?
) {
    val totalSizeBytes: Long?
        get() = if (appSizeBytes != null) appSizeBytes + (dataSizeBytes ?: 0) + (cacheSizeBytes ?: 0)
        else null
}

enum class AppFilter(val label: String) {
    ALL("All"), USER("User apps"), SYSTEM("System"), RECENT("Recently used"), LARGE("Large apps")
}

enum class AppSort(val label: String) {
    NAME("Name"), SIZE("Size"), INSTALL_DATE("Install date"),
    UPDATE_DATE("Update date"), LAST_USED("Last used"), TARGET_SDK("Target SDK")
}

/**
 * Installed application inventory.
 *
 * Uses PackageManager for the base list. Per-app storage sizes need the
 * StorageStatsManager API which requires the user-granted Usage Access special
 * permission — without it, sizes are reported as Permission Required rather than
 * being estimated.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    suspend fun loadApps(includePermissions: Boolean = false): List<AppEntry> =
        withContext(Dispatchers.IO) {
            val hasUsage = Permissions.hasUsageStats(context)
            val storageStats = if (hasUsage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            } else null
            val lastUsedMap = if (hasUsage) loadLastUsed() else emptyMap()

            val flags = if (includePermissions) PackageManager.GET_PERMISSIONS else 0
            val packages: List<PackageInfo> = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(flags)
                }
            } catch (_: Throwable) {
                emptyList()
            }

            packages.mapNotNull { info ->
                try {
                    val ai = info.applicationInfo ?: return@mapNotNull null
                    val apkSize = try { java.io.File(ai.sourceDir).length() } catch (_: Throwable) { 0L }
                    var appSize: Long? = null
                    var dataSize: Long? = null
                    var cacheSize: Long? = null

                    if (storageStats != null) {
                        try {
                            val stats: StorageStats = storageStats.queryStatsForUid(
                                StorageManager.UUID_DEFAULT, ai.uid)
                            appSize = stats.appBytes
                            dataSize = stats.dataBytes
                            cacheSize = stats.cacheBytes
                        } catch (_: Throwable) { /* per-app failure stays null */ }
                    }

                    AppEntry(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(ai).toString() }
                            .getOrDefault(info.packageName),
                        versionName = info.versionName,
                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong(),
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                        isEnabled = ai.enabled,
                        firstInstallTime = info.firstInstallTime,
                        lastUpdateTime = info.lastUpdateTime,
                        targetSdk = ai.targetSdkVersion,
                        minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                            ai.minSdkVersion else null,
                        uid = ai.uid,
                        apkSizeBytes = apkSize,
                        appSizeBytes = appSize,
                        dataSizeBytes = dataSize,
                        cacheSizeBytes = cacheSize,
                        lastUsedTime = lastUsedMap[info.packageName],
                        permissions = info.requestedPermissions?.toList() ?: emptyList(),
                        sourceDir = ai.sourceDir
                    )
                } catch (_: Throwable) {
                    null
                }
            }
        }

    /** Last-used timestamps from UsageStatsManager (needs Usage Access). */
    private fun loadLastUsed(): Map<String, Long> = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 30L * 24 * 3600 * 1000
        usm.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY, start, end)
            ?.filter { it.lastTimeUsed > 0 }
            ?.associate { it.packageName to it.lastTimeUsed }
            ?: emptyMap()
    } catch (_: Throwable) {
        emptyMap()
    }

    fun storageStatsAvailability(): Reading<String> = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ->
            Reading.unsupported("Per-app storage stats require Android 8.0+")
        !Permissions.hasUsageStats(context) ->
            Reading.permission("Grant Usage Access to read per-app app/data/cache sizes")
        else -> Reading.available("Usage Access granted", "StorageStatsManager")
    }

    fun filterAndSort(
        apps: List<AppEntry>,
        filter: AppFilter,
        sort: AppSort,
        query: String
    ): List<AppEntry> {
        val now = System.currentTimeMillis()
        var list = when (filter) {
            AppFilter.ALL -> apps
            AppFilter.USER -> apps.filter { !it.isSystem }
            AppFilter.SYSTEM -> apps.filter { it.isSystem }
            AppFilter.RECENT -> apps.filter {
                it.lastUsedTime != null && now - it.lastUsedTime < 7L * 24 * 3600 * 1000
            }
            AppFilter.LARGE -> apps.filter {
                (it.totalSizeBytes ?: it.apkSizeBytes) > 100L * 1024 * 1024
            }
        }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        return when (sort) {
            AppSort.NAME -> list.sortedBy { it.label.lowercase() }
            AppSort.SIZE -> list.sortedByDescending { it.totalSizeBytes ?: it.apkSizeBytes }
            AppSort.INSTALL_DATE -> list.sortedByDescending { it.firstInstallTime }
            AppSort.UPDATE_DATE -> list.sortedByDescending { it.lastUpdateTime }
            AppSort.LAST_USED -> list.sortedByDescending { it.lastUsedTime ?: 0 }
            AppSort.TARGET_SDK -> list.sortedByDescending { it.targetSdk }
        }
    }

    /** Opens the system App Info screen — the supported way to reach app controls. */
    fun appInfoIntent(packageName: String) = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", packageName, null)
    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun launchIntent(packageName: String) = pm.getLaunchIntentForPackage(packageName)

    /** Runtime permissions actually granted to a package, per PackageManager. */
    suspend fun permissionStates(packageName: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                }
                val requested = info.requestedPermissions ?: return@withContext emptyList()
                val flags = info.requestedPermissionsFlags
                requested.mapIndexed { i, perm ->
                    val granted = flags != null && i < flags.size &&
                        (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    perm to if (granted) "Granted" else "Denied"
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

    fun ownUid(): Int = Process.myUid()
}
