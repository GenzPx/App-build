package com.monitorcheck.core

/**
 * Every value surfaced by Monitored Check carries an explicit availability status.
 *
 * This is the backbone of the app's "never fake data" rule: a repository may only
 * return [DataStatus.AVAILABLE] when it actually read the value from a real Android
 * API, sysfs/procfs node, or hardware sensor. Anything else must return one of the
 * non-available statuses together with a human readable reason.
 */
enum class DataStatus(val label: String) {
    AVAILABLE("Available"),
    LIMITED("Limited"),
    UNAVAILABLE("Unavailable"),
    UNSUPPORTED("Unsupported"),
    PERMISSION_REQUIRED("Permission Required"),
    RESTRICTED_BY_ANDROID("Restricted by Android"),
    REQUIRES_ROOT("Requires Root"),
    HARDWARE_NOT_SUPPORTED("Hardware Not Supported"),
    TEMPORARY_ERROR("Temporary Error"),
    NOT_REQUESTED("Not requested"),
    UNKNOWN("Unknown")
}

/**
 * A single measured or read value.
 *
 * @param status  availability of the value
 * @param value   the real value, non-null only when [status] is AVAILABLE or LIMITED
 * @param note    optional explanation (why unavailable, how it was measured, ...)
 * @param source  where the value came from, e.g. "/proc/stat" or "BatteryManager"
 */
data class Reading<out T>(
    val status: DataStatus,
    val value: T? = null,
    val note: String? = null,
    val source: String? = null
) {
    val isAvailable: Boolean get() = (status == DataStatus.AVAILABLE || status == DataStatus.LIMITED) && value != null

    fun <R> map(transform: (T) -> R): Reading<R> =
        if (isAvailable) Reading(status, transform(value as T), note, source)
        else Reading(status, null, note, source)

    /** Text for UI display: the formatted value, or the availability label. */
    fun display(format: (T) -> String = { it.toString() }): String =
        if (isAvailable) format(value as T) else status.label

    companion object {
        fun <T> available(value: T, source: String? = null, note: String? = null) =
            Reading(DataStatus.AVAILABLE, value, note, source)

        fun <T> limited(value: T, note: String, source: String? = null) =
            Reading(DataStatus.LIMITED, value, note, source)

        fun <T> unavailable(note: String? = null, source: String? = null) =
            Reading<T>(DataStatus.UNAVAILABLE, null, note, source)

        fun <T> unsupported(note: String? = null) = Reading<T>(DataStatus.UNSUPPORTED, null, note)

        fun <T> restricted(note: String? = "Restricted by Android platform policy") =
            Reading<T>(DataStatus.RESTRICTED_BY_ANDROID, null, note)

        fun <T> permission(note: String? = null) = Reading<T>(DataStatus.PERMISSION_REQUIRED, null, note)

        fun <T> requiresRoot(note: String? = "Only readable on rooted devices") =
            Reading<T>(DataStatus.REQUIRES_ROOT, null, note)

        fun <T> noHardware(note: String? = null) = Reading<T>(DataStatus.HARDWARE_NOT_SUPPORTED, null, note)

        fun <T> error(note: String?) = Reading<T>(DataStatus.TEMPORARY_ERROR, null, note)

        fun <T> unknown(note: String? = null) = Reading<T>(DataStatus.UNKNOWN, null, note)
    }
}

/** A label/value pair used by the generic info list UI and the TXT report writer. */
data class InfoItem(
    val label: String,
    val reading: Reading<String>
) {
    val text: String get() = reading.display()

    companion object {
        fun of(label: String, value: String?, source: String? = null): InfoItem =
            if (value.isNullOrBlank()) InfoItem(label, Reading.unavailable(source = source))
            else InfoItem(label, Reading.available(value, source))

        fun restricted(label: String, note: String? = null) =
            InfoItem(label, Reading.restricted(note))

        fun unsupported(label: String, note: String? = null) =
            InfoItem(label, Reading.unsupported(note))
    }
}

/** Group of [InfoItem]s, rendered as a titled card and as a report section. */
data class InfoSection(
    val title: String,
    val items: List<InfoItem>,
    val note: String? = null
)
