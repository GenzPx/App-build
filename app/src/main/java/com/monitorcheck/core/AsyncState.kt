package com.monitorcheck.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Loading state for data that must never be produced on the main thread.
 *
 * Every repository in this app touches procfs, sysfs, PackageManager or SQLite.
 * Doing that inside a composable body blocks the UI thread and drops frames, so all
 * screens go through these helpers instead.
 */
sealed interface AsyncState<out T> {
    data object Loading : AsyncState<Nothing>
    data class Ready<T>(val value: T) : AsyncState<T>
    data class Failed(val message: String) : AsyncState<Nothing>

    val valueOrNull: T? get() = (this as? Ready)?.value
    val isLoading: Boolean get() = this is Loading
}

/**
 * Loads [producer] once on [Dispatchers.IO] and, when [refreshKey] changes, reloads it.
 *
 * Unlike `remember(key) { blockingCall() }` this never runs on the main thread, so a
 * slow sysfs read shows a spinner instead of freezing the UI.
 */
@Composable
fun <T> rememberAsync(
    vararg keys: Any?,
    producer: suspend () -> T
): State<AsyncState<T>> {
    val state = remember { mutableStateOf<AsyncState<T>>(AsyncState.Loading) }
    LaunchedEffect(*keys) {
        // Keep any previous value visible while refreshing to avoid UI flicker.
        val result = try {
            AsyncState.Ready(withContext(Dispatchers.IO) { producer() })
        } catch (t: Throwable) {
            AsyncState.Failed(t.message ?: t.javaClass.simpleName)
        }
        state.value = result
    }
    return state
}

/**
 * Periodically reloads [producer] on [Dispatchers.IO] at [intervalMs].
 *
 * Used for panels whose content changes over time (thermal zones, battery details)
 * but which must not be re-read on every monitoring tick — the polling rate here is
 * deliberately decoupled from, and slower than, the dashboard sample rate.
 */
@Composable
fun <T> rememberPolled(
    intervalMs: Long,
    vararg keys: Any?,
    producer: suspend () -> T
): State<AsyncState<T>> {
    val state = remember { mutableStateOf<AsyncState<T>>(AsyncState.Loading) }
    LaunchedEffect(intervalMs, *keys) {
        while (isActive) {
            val result = try {
                AsyncState.Ready(withContext(Dispatchers.IO) { producer() })
            } catch (t: Throwable) {
                AsyncState.Failed(t.message ?: t.javaClass.simpleName)
            }
            state.value = result
            delay(intervalMs)
        }
    }
    return state
}
