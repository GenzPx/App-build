package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.power.AppActivityUsage
import com.monitorcheck.power.PowerActivityRepository
import com.monitorcheck.power.PowerActivitySnapshot
import com.monitorcheck.ui.components.InfoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.SectionCard
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PowerActivityScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val repo = remember { PowerActivityRepository(context.applicationContext) }
    var snapshot by remember { mutableStateOf<PowerActivitySnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    fun reload() { loading = true }
    LaunchedEffect(loading) { if (loading) { snapshot = withContext(Dispatchers.IO) { repo.snapshot() }; loading = false } }
    LazyColumn(contentPadding = contentPadding) {
        item { NoticeCard("Doze & background activity", "Public Android APIs only. Full per-app wakelock history may be restricted.") }
        item { Button(onClick = { reload() }, enabled = !loading) { Text(if (loading) "Reading…" else "Refresh") } }
        snapshot?.let { s ->
            item { SectionCard(InfoSection("Power state", listOf(InfoItem("Device idle", s.deviceIdle), InfoItem("Power saver", s.powerSave), InfoItem("Optimization", s.ownBatteryOptimization), InfoItem("Standby bucket", s.ownStandbyBucket)), s.note), true) }
            item { Text("App activity · last 24 hours", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary) }
            items(s.apps.size) { index -> ActivityCard(s.apps[index]) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
@Composable private fun ActivityCard(app: AppActivityUsage) { Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) { Column(Modifier.padding(12.dp)) { Text(app.label); Text(app.packageName, color = StatusColors.muted); Text("Foreground ${Fmt.duration(app.foregroundMs)} · ${Fmt.timestamp(app.lastUsedTime)}", style = MaterialTheme.typography.labelSmall, color = StatusColors.muted) } } }
private inline fun androidx.compose.foundation.lazy.LazyListScope.items(count: Int, crossinline itemContent: @Composable (Int) -> Unit) = items(count = count) { index -> itemContent(index) }
