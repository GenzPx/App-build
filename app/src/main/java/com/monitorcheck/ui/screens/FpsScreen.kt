package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.hardware.display.DisplayRepository
import com.monitorcheck.hardware.display.FpsMonitor
import com.monitorcheck.monitor.RingBuffer
import com.monitorcheck.ui.components.MetricValue
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.SectionCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.theme.StatusColors
import java.util.Locale

@Composable
fun FpsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val displayRepo = remember { DisplayRepository(context) }
    val refreshHz = remember { displayRepo.refreshRate().value ?: 60f }
    val monitor = remember { FpsMonitor(refreshHz) }
    val stats by monitor.stats.collectAsStateWithLifecycle()
    val history = remember { RingBuffer(100) }
    var version by remember { mutableStateOf(0) }
    val sections = remember { displayRepo.infoSections() }

    DisposableEffect(Unit) {
        monitor.start()
        onDispose { monitor.stop() }
    }

    LaunchedEffect(stats.currentFps) {
        if (stats.measuring && stats.currentFps > 0) {
            history.add(stats.currentFps.toFloat())
            version++
        }
    }

    LazyColumn(contentPadding = contentPadding) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Frame rate", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    if (stats.measuring && stats.totalFrames > 0) {
                        val color = when {
                            stats.currentFps >= refreshHz * 0.9 -> StatusColors.ok
                            stats.currentFps >= refreshHz * 0.6 -> StatusColors.warn
                            else -> StatusColors.critical
                        }
                        MetricValue(String.format(Locale.US, "%.1f", stats.currentFps), "fps", color)
                        Spacer(Modifier.height(10.dp))
                        val v = version
                        Sparkline(
                            values = history.toList(),
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            color = color,
                            minValue = 0f,
                            maxValue = refreshHz.coerceAtLeast(60f)
                        )
                        Spacer(Modifier.height(10.dp))
                        MonoRow("average", String.format(Locale.US, "%.1f fps", stats.averageFps))
                        MonoRow("minimum", String.format(Locale.US, "%.1f fps", stats.minFps))
                        MonoRow("maximum", String.format(Locale.US, "%.1f fps", stats.maxFps))
                        MonoRow("frame time",
                            String.format(Locale.US, "%.2f ms", stats.averageFrameTimeMs))
                        MonoRow("dropped frames", stats.droppedFrames.toString())
                        MonoRow("total frames", stats.totalFrames.toString())
                        MonoRow("display refresh",
                            String.format(Locale.US, "%.2f Hz", stats.displayRefreshHz))
                    } else {
                        Text("Collecting frames…", style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.muted)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(onClick = { monitor.reset(); history.clear(); version++ }) {
                            Text("Reset")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            if (stats.measuring) monitor.stop() else monitor.start()
                        }) { Text(if (stats.measuring) "Stop" else "Start") }
                    }
                }
            }
        }

        item {
            NoticeCard(
                title = "Measurement method",
                body = monitor.methodDescription,
                tone = StatusColors.warn
            )
        }

        items(sections.size) { i -> SectionCard(sections[i], showSources = true) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
