package com.monitorcheck.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.monitor.Series
import com.monitorcheck.ui.MonitorViewModel

/**
 * Observes a single graph series.
 *
 * PERFORMANCE: each series is its own StateFlow, so a chart recomposes only when the
 * data it actually draws changes. Previously one global counter invalidated every
 * chart on screen at once, which was the main source of dashboard jank.
 */
@Composable
fun observeSeries(vm: MonitorViewModel, series: Series): List<Float> {
    val values by vm.series.flow(series).collectAsStateWithLifecycle()
    return values
}
