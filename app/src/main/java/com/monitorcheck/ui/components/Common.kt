package com.monitorcheck.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monitorcheck.core.DataStatus
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import com.monitorcheck.ui.theme.MonoNumberStyle
import com.monitorcheck.ui.theme.StatusColors
import kotlin.math.max
import kotlin.math.min

@Composable
fun statusColor(status: DataStatus): Color = when (status) {
    DataStatus.AVAILABLE -> MaterialTheme.colorScheme.onSurface
    DataStatus.LIMITED -> StatusColors.warn
    DataStatus.PERMISSION_REQUIRED -> StatusColors.warn
    DataStatus.RESTRICTED_BY_ANDROID -> StatusColors.critical
    DataStatus.REQUIRES_ROOT -> StatusColors.critical
    DataStatus.HARDWARE_NOT_SUPPORTED -> StatusColors.muted
    DataStatus.TEMPORARY_ERROR -> StatusColors.critical
    else -> StatusColors.muted
}

@Composable
fun StatusChip(status: DataStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun InfoRow(item: InfoItem, showSource: Boolean = false) {
    val r = item.reading
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
            if (r.isAvailable) {
                Text(
                    text = r.value ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.3f)
                )
            } else {
                Box(Modifier.weight(1.3f), contentAlignment = Alignment.CenterEnd) {
                    StatusChip(r.status)
                }
            }
        }

        val note = r.note
        if (!note.isNullOrBlank() && (!r.isAvailable || r.status == DataStatus.LIMITED)) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
        if (showSource && r.source != null && r.isAvailable) {
            Text(
                text = "source: ${r.source}",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SectionCard(
    section: InfoSection,
    showSources: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(4.dp))
            section.items.forEach { InfoRow(it, showSources) }
            section.note?.let {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    minValue: Float? = null,
    maxValue: Float? = null,
    filled: Boolean = true
) {
    val surfaceVariant = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    Canvas(modifier) {
        if (values.size < 2) return@Canvas

        val lo = minValue ?: values.min()
        val hi = maxValue ?: values.max()
        val range = if (hi - lo < 0.0001f) 1f else hi - lo

        for (f in listOf(0.25f, 0.5f, 0.75f)) {
            val y = size.height * f
            drawLine(surfaceVariant, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        val stepX = size.width / (values.size - 1).toFloat()
        val path = Path()
        val fill = Path()

        values.forEachIndexed { i, v ->
            val x = i * stepX
            val norm = ((v - lo) / range).coerceIn(0f, 1f)
            val y = size.height - (norm * size.height)
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }

        if (filled) {
            fill.lineTo(size.width, size.height)
            fill.close()
            drawPath(
                fill,
                Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)))
            )
        }
        drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}

@Composable
fun UsageBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Int = 8
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "usage"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp))
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(height.dp)
                .clip(RoundedCornerShape(height.dp))
                .background(color)
        )
    }
}

@Composable
fun loadColor(percent: Double): Color {
    val target = when {
        percent >= 85 -> StatusColors.critical
        percent >= 60 -> StatusColors.warn
        else -> StatusColors.ok
    }
    val animated by animateColorAsState(target, tween(500), label = "loadColor")
    return animated
}

@Composable
fun MetricValue(
    value: String,
    unit: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            fontSize = 26.sp,
            style = MaterialTheme.typography.headlineSmall,
            color = color
        )
        unit?.let {
            Text(
                text = " $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
fun UnavailableBlock(reading: Reading<*>, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        StatusChip(reading.status)
        reading.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted
            )
        }
    }
}

@Composable
fun NoticeCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: Color? = null,
    action: (@Composable () -> Unit)? = null
) {
    val accent = tone ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.09f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            action?.let {
                Spacer(Modifier.height(10.dp))
                it()
            }
        }
    }
}

@Composable
fun MonoRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MonoNumberStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MonoNumberStyle,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
