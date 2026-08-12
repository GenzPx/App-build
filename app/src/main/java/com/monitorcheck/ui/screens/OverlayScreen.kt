package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.Permissions
import com.monitorcheck.monitor.OverlayMonitorService
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors

@Composable fun OverlayScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current; val settings by vm.settings.collectAsStateWithLifecycle(); var granted by remember { mutableStateOf(Permissions.hasOverlay(context)) }
    androidx.compose.foundation.lazy.LazyColumn(contentPadding = contentPadding) {
        item { NoticeCard("Floating Performance HUD", "CPU, RAM, suhu dan network di atas aplikasi lain. Butuh Draw over other apps.") }
        item { Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) { Column(Modifier.padding(14.dp)) { Text(if (granted) "Permission granted" else "Permission required", color = if (granted) StatusColors.ok else StatusColors.warn); Spacer(Modifier.height(8.dp)); if (!granted) Button(onClick = { runCatching { context.startActivity(Permissions.overlaySettingsIntent(context)); granted = Permissions.hasOverlay(context) } }) { Text("Grant overlay permission") } else { Row(verticalAlignment = Alignment.CenterVertically) { Text("Enable floating HUD", Modifier.weight(1f)); Switch(checked = settings.overlayEnabled, onCheckedChange = { enabled -> vm.setOverlayEnabled(enabled); if (enabled) OverlayMonitorService.start(context) else OverlayMonitorService.stop(context) }) }; OutlinedButton(onClick = { OverlayMonitorService.stop(context); vm.setOverlayEnabled(false) }) { Text("Stop HUD") } } } } }
        item { NoticeCard("FPS limitation", "FPS aplikasi lain tidak bisa dibaca oleh aplikasi normal tanpa privileged access.", tone = StatusColors.warn) }
    }
}
