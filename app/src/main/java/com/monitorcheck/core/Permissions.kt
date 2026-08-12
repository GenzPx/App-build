package com.monitorcheck.core

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Centralised runtime permission / special-access checks.
 *
 * Monitored Check requests nothing at first launch. Each feature asks only for what
 * it needs, right before it needs it, and degrades gracefully when denied.
 */
object Permissions {

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Usage Access: required for per-app usage stats and app storage sizes. */
    fun hasUsageStats(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    /** All-files access (API 30+) used by the optional deep storage analyzer. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager()

    fun hasLegacyStorageRead(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            has(context, android.Manifest.permission.READ_EXTERNAL_STORAGE)

    fun canReadStorage(context: Context): Boolean = hasAllFilesAccess() || hasLegacyStorageRead(context)

    fun hasLocation(context: Context): Boolean =
        has(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            has(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            has(context, android.Manifest.permission.POST_NOTIFICATIONS)

    fun hasPhoneState(context: Context): Boolean =
        has(context, android.Manifest.permission.READ_PHONE_STATE)

    fun hasOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context) =
        android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        )

    val usageAccessSettingsAction: String = Settings.ACTION_USAGE_ACCESS_SETTINGS
}
