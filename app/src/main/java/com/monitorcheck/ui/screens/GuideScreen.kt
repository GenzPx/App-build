package com.monitorcheck.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monitorcheck.ui.GuideCatalog
import com.monitorcheck.ui.GuideEntry
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors

@Composable
fun GuideScreen(contentPadding: PaddingValues) {
    var selected by remember { mutableStateOf<GuideEntry?>(null) }
    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.title) },
            text = {
                Column {
                    Text(entry.purpose)
                    Spacer(Modifier.height(8.dp))
                    Text("Cara kerja", color = MaterialTheme.colorScheme.primary)
                    Text(entry.howItWorks)
                    entry.limitation?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Batasan", color = StatusColors.warn)
                        Text(it)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Mengerti") } }
        )
    }
    LazyColumn(contentPadding = contentPadding) {
        item { NoticeCard("Guide App", "Tap menu untuk melihat nama, fungsi, cara kerja dan batasan.") }
        items(GuideCatalog.entries.size) { index ->
            val entry = GuideCatalog.entries[index]
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clickable { selected = entry }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) {
                Column(Modifier.padding(14.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(entry.purpose, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
private inline fun androidx.compose.foundation.lazy.LazyListScope.items(count: Int, crossinline itemContent: @Composable (Int) -> Unit) = items(count = count) { index -> itemContent(index) }
