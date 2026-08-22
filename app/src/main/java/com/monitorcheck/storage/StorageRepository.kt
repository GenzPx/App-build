package com.monitorcheck.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs
import java.io.File

data class VolumeInfo(
    val label: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    val state: String,
    val filesystem: String?
) {
    val usedPercent: Double get() = if (totalBytes > 0) usedBytes * 100.0 / totalBytes else 0.0
}

data class MountPoint(
    val device: String,
    val mountPoint: String,
    val filesystem: String,
    val options: String
)

class StorageRepository(private val context: Context) {

    private val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    fun volumes(): Reading<List<VolumeInfo>> {
        val out = ArrayList<VolumeInfo>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && storageManager != null) {
                for (volume in storageManager.storageVolumes) {
                    val dir: File? = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volume.directory
                        else -> runCatching {
                            val m = volume.javaClass.getMethod("getPathFile")
                            m.invoke(volume) as? File
                        }.getOrNull()
                    }
                    val state = runCatching { volume.state }.getOrDefault(Environment.MEDIA_UNKNOWN)
                    if (dir == null || !dir.exists()) {

                        out.add(VolumeInfo(
                            label = volume.getDescription(context) ?: "Volume",
                            path = "Not mounted",
                            totalBytes = 0, freeBytes = 0, usedBytes = 0,
                            isRemovable = volume.isRemovable,
                            isPrimary = volume.isPrimary,
                            state = state,
                            filesystem = null
                        ))
                        continue
                    }
                    val stat = StatFs(dir.absolutePath)
                    val total = stat.blockCountLong * stat.blockSizeLong
                    val free = stat.availableBlocksLong * stat.blockSizeLong
                    out.add(VolumeInfo(
                        label = volume.getDescription(context) ?: dir.name,
                        path = dir.absolutePath,
                        totalBytes = total,
                        freeBytes = free,
                        usedBytes = total - free,
                        isRemovable = volume.isRemovable,
                        isPrimary = volume.isPrimary,
                        state = state,
                        filesystem = filesystemFor(dir.absolutePath)
                    ))
                }
            }
            if (out.isEmpty()) {

                val dir = Environment.getDataDirectory()
                val stat = StatFs(dir.absolutePath)
                val total = stat.blockCountLong * stat.blockSizeLong
                val free = stat.availableBlocksLong * stat.blockSizeLong
                out.add(VolumeInfo("Internal storage", dir.absolutePath, total, free,
                    total - free, false, true, Environment.MEDIA_MOUNTED, filesystemFor(dir.absolutePath)))
            }
            return Reading.available(out, "StorageManager + StatFs")
        } catch (t: Throwable) {
            return Reading.error(t.message)
        }
    }

    fun primaryTotals(): Reading<Pair<Long, Long>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                val uuid = StorageManager.UUID_DEFAULT
                val total = ssm.getTotalBytes(uuid)
                val free = ssm.getFreeBytes(uuid)
                return Reading.available(total to free, "StorageStatsManager")
            } catch (_: Throwable) {  }
        }
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            Reading.available(total to free, "StatFs")
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    fun mounts(): Reading<List<MountPoint>> {
        val lines = SysFs.readLines("/proc/mounts")
            ?: return Reading.restricted("/proc/mounts is not readable")
        val list = lines.mapNotNull { line ->
            val p = line.trim().split(" ")
            if (p.size < 4) null
            else MountPoint(p[0], p[1], p[2], p[3])
        }
        return if (list.isEmpty()) Reading.unavailable() else Reading.available(list, "/proc/mounts")
    }

    private fun filesystemFor(path: String): String? =
        mounts().value
            ?.filter { path.startsWith(it.mountPoint) }
            ?.maxByOrNull { it.mountPoint.length }
            ?.filesystem

    fun diskStats(): Reading<List<Triple<String, Long, Long>>> {
        val lines = SysFs.readLines("/proc/diskstats")
            ?: return Reading.restricted("/proc/diskstats is not readable")
        val interesting = lines.mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 10) return@mapNotNull null
            val name = p[2]

            if (!(name.startsWith("sd") || name.startsWith("mmcblk") ||
                    name.startsWith("nvme") || name.startsWith("sda"))) return@mapNotNull null
            if (name.contains("p") && name.startsWith("mmcblk") && name.count { it.isDigit() } > 2) {
                return@mapNotNull null
            }
            val readSectors = p[5].toLongOrNull() ?: return@mapNotNull null
            val writeSectors = p[9].toLongOrNull() ?: return@mapNotNull null
            Triple(name, readSectors * 512, writeSectors * 512)
        }
        return if (interesting.isEmpty()) Reading.unavailable("No block devices reported")
        else Reading.available(interesting, "/proc/diskstats")
    }

    fun healthSection(): InfoSection {
        val items = ArrayList<InfoItem>()

        val lifeA = SysFs.readFirstLine("/sys/class/mmc_host/mmc0/mmc0:0001/life_time")
            ?: SysFs.readFirstLine("/sys/block/mmcblk0/device/life_time")
        items.add(InfoItem("eMMC life time estimate", lifeA?.let {
            Reading.limited(decodeLifeTime(it), "Vendor-reported wear band, not a precise percentage",
                "sysfs life_time")
        } ?: Reading.unavailable("Not exported by this device")))

        items.add(InfoItem("eMMC pre-EOL state", SysFs.readFirstLine(
            "/sys/block/mmcblk0/device/pre_eol_info")?.let {
            Reading.available(decodePreEol(it), "sysfs pre_eol_info")
        } ?: Reading.unavailable("Not exported by this device")))

        items.add(InfoItem("Storage type", detectStorageType()))

        items.add(InfoItem("Device name", SysFs.readFirstLine("/sys/block/mmcblk0/device/name")
            ?.let { Reading.available(it, "sysfs") }
            ?: SysFs.readFirstLine("/sys/block/sda/device/model")?.let { Reading.available(it, "sysfs") }
            ?: Reading.unavailable()))

        items.add(InfoItem("Manufacturer ID", SysFs.readFirstLine("/sys/block/mmcblk0/device/manfid")
            ?.let { Reading.available(it, "sysfs") } ?: Reading.unavailable()))

        items.add(InfoItem("SMART health", Reading.unsupported(
            "SMART is not available on Android. Mobile eMMC/UFS storage does not expose " +
                "SMART attributes to applications, and Monitored Check will not simulate a " +
                "health score."
        )))

        return InfoSection("Storage health", items,
            note = "Only genuine kernel-exported wear indicators are shown. Most consumer devices " +
                "expose none of them to unprivileged apps.")
    }

    private fun detectStorageType(): Reading<String> = when {
        SysFs.isReadable("/sys/block/sda/device/model") &&
            SysFs.listDir("/sys/class/scsi_device").isNotEmpty() ->
            Reading.available("UFS (SCSI-attached)", "/sys/class/scsi_device")
        SysFs.isReadable("/sys/block/mmcblk0/device/type") ->
            Reading.available("eMMC / MMC (${SysFs.readFirstLine("/sys/block/mmcblk0/device/type")})",
                "/sys/block/mmcblk0")
        SysFs.isReadable("/sys/block/mmcblk0") -> Reading.available("eMMC / MMC", "/sys/block")
        else -> Reading.unavailable("Cannot determine storage class without privileged access")
    }

    private fun decodeLifeTime(raw: String): String {

        val parts = raw.trim().split(Regex("\\s+"))
        return parts.joinToString("; ") { p ->
            val v = p.removePrefix("0x").toIntOrNull(16)
            when {
                v == null -> p
                v == 0 -> "not defined"
                v in 1..10 -> "${(v - 1) * 10}-${v * 10}% of rated life used"
                v == 11 -> "exceeded rated life"
                else -> p
            }
        }
    }

    private fun decodePreEol(raw: String): String = when (raw.trim().removePrefix("0x")) {
        "01" -> "Normal"
        "02" -> "Warning (80% of reserved blocks consumed)"
        "03" -> "Urgent (90% of reserved blocks consumed)"
        else -> raw
    }

    fun infoSections(): List<InfoSection> {
        val vols = volumes()
        val sections = ArrayList<InfoSection>()

        vols.value?.forEach { v ->
            sections.add(InfoSection(
                v.label,
                listOf(
                    InfoItem.of("Path", v.path),
                    InfoItem("Total", if (v.totalBytes > 0)
                        Reading.available(Fmt.bytes(v.totalBytes), "StatFs") else Reading.unavailable()),
                    InfoItem("Used", if (v.totalBytes > 0)
                        Reading.available("${Fmt.bytes(v.usedBytes)} (${Fmt.percent(v.usedPercent)})", "StatFs")
                        else Reading.unavailable()),
                    InfoItem("Free", if (v.totalBytes > 0)
                        Reading.available(Fmt.bytes(v.freeBytes), "StatFs") else Reading.unavailable()),
                    InfoItem.of("Filesystem", v.filesystem),
                    InfoItem.of("State", v.state),
                    InfoItem.of("Removable", Fmt.yesNo(v.isRemovable)),
                    InfoItem.of("Primary", Fmt.yesNo(v.isPrimary))
                )
            ))
        }
        if (vols.value.isNullOrEmpty()) {
            sections.add(InfoSection("Volumes", listOf(InfoItem("Volumes", Reading(vols.status, null, vols.note)))))
        }

        val io = diskStats()
        sections.add(InfoSection(
            "Block device I/O since boot",
            io.value?.map { (name, r, w) ->
                InfoItem(name, Reading.available("read ${Fmt.bytes(r)} / written ${Fmt.bytes(w)}", "/proc/diskstats"))
            } ?: listOf(InfoItem("Disk statistics", Reading(io.status, null, io.note)))
        ))

        val m = mounts()
        sections.add(InfoSection(
            "Mount points",
            m.value?.filter {
                it.mountPoint.startsWith("/storage") || it.mountPoint == "/data" ||
                    it.mountPoint == "/" || it.mountPoint == "/system" || it.mountPoint == "/cache" ||
                    it.mountPoint == "/vendor"
            }?.map {
                InfoItem(it.mountPoint, Reading.available(
                    "${it.filesystem} on ${it.device}\n${it.options}", "/proc/mounts"))
            } ?: listOf(InfoItem("Mounts", Reading(m.status, null, m.note)))
        ))

        sections.add(healthSection())
        return sections
    }
}
