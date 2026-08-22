package com.monitorcheck.core

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

data class InfoSection(
    val title: String,
    val items: List<InfoItem>,
    val note: String? = null
)
