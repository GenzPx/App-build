package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monitorcheck.benchmark.BenchmarkReport
import com.monitorcheck.benchmark.BenchmarkRunner
import com.monitorcheck.core.Fmt
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.UsageBar
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Benchmark page. Every score is a measured throughput figure from real work done on
 * this device — there is no reference scaling table and no synthetic "points" system.
 */
@Composable
fun BenchmarkScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val runner = remember { BenchmarkRunner() }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<BenchmarkReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    val cores = remember { Runtime.getRuntime().availableProcessors() }

    LazyColumn(contentPadding = contentPadding) {
        item {
            NoticeCard(
                title = "How these numbers are produced",
                body = "Each test runs actual work and divides it by the measured elapsed time: " +
                    "dependent integer chains, sqrt-heavy floating point, parallel execution " +
                    "across all $cores cores, large memory copies, SHA-256 hashing, and real " +
                    "file I/O with fsync.\n\nThe results are comparable between runs on this " +
                    "phone, but not against marketing figures from other devices. Thermal state, " +
                    "background apps and the CPU governor all affect them. Run it twice if a " +
                    "result looks off."
            )
        }

        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Run benchmark", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Takes roughly 20–40 seconds and will load every CPU core. Keep the " +
                            "screen on and avoid using other apps while it runs.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                running = true
                                report = null
                                job = scope.launch {
                                    try {
                                        report = runner.runAll(context.cacheDir, cores) {
                                            stage = it
                                        }
                                    } finally {
                                        running = false
                                        stage = ""
                                    }
                                }
                            },
                            enabled = !running
                        ) { Text(if (report == null) "Start benchmark" else "Run again") }

                        if (running) {
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = { job?.cancel(); running = false }) {
                                Text("Cancel")
                            }
                        }
                    }

                    if (running) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stage.ifBlank { "Warming up…" },
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        report?.let { r ->
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = StatusColors.ok.copy(alpha = 0.10f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Results", style = MaterialTheme.typography.titleMedium,
                            color = StatusColors.ok)
                        Text("completed in ${r.totalMs} ms · ${r.threadsUsed} threads",
                            style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                    }
                }
            }

            items(r.results.size) { i ->
                val res = r.results[i]
                val max = r.results.filter { it.unit == res.unit }.maxOf { it.score }.coerceAtLeast(1)
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom) {
                            Text(res.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (res.score > 0) "${res.score} ${res.unit}" else "n/a",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (res.score > 0) MaterialTheme.colorScheme.primary
                                else StatusColors.muted
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        if (res.score > 0) {
                            UsageBar(
                                fraction = (res.score.toDouble() / max).toFloat(),
                                color = MaterialTheme.colorScheme.primary,
                                height = 6
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(res.detail, style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                        Text("took ${res.durationMs} ms",
                            style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                    }
                }
            }

            item {
                NoticeCard(title = "Interpreting the results", body = r.note)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
