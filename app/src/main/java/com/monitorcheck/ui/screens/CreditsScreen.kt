package com.monitorcheck.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors

/**
 * Credits / About page.
 *
 * Static information only — no network call is made from this page. The single
 * button that opens the project repository hands the URL to the system browser
 * via an ordinary VIEW intent; the app itself never fetches anything.
 */
private const val REPO_URL = "https://github.com/GenzPx/MonitoredCheck"

@Composable
fun CreditsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val packageInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "Unknown"
    val versionCode = packageInfo?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
        else @Suppress("DEPRECATION") it.versionCode.toLong()
    }?.toString() ?: "Unknown"

    LazyColumn(contentPadding = contentPadding) {
        item {
            CreditsCard("Monitored Check") {
                Text(
                    "An all-in-one, privacy-first Android system monitor, hardware inspector " +
                        "and diagnostics toolkit. Built native in Kotlin with Jetpack Compose " +
                        "and Material 3. Runs entirely on-device and works without root.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                MonoRow("Version", versionName)
                MonoRow("Version code", versionCode)
                MonoRow("License", "MIT")
                MonoRow("Package", context.packageName)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Open project on GitHub") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Opens in your browser — this app itself makes no network request here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            CreditsCard("Developer & contributors") {
                MonoRow("Author", "GenzPx")
                MonoRow("Repository", "github.com/GenzPx/MonitoredCheck")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Monitored Check is open source under the MIT license. Issues, ideas and " +
                        "pull requests are welcome on the repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            CreditsCard("Built with") {
                listOf(
                    "Kotlin" to "Language, coroutines and flows",
                    "Jetpack Compose" to "Declarative UI toolkit",
                    "Material 3" to "Design system with dynamic colour",
                    "AndroidX Navigation" to "In-app navigation",
                    "AndroidX DataStore" to "Local settings storage",
                    "AndroidX Lifecycle" to "ViewModel and lifecycle-aware state",
                    "Android SDK / NDK interfaces" to "Every real reading in the app"
                ).forEach { (name, role) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(190.dp))
                        Text(role, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "No chart library, no analytics SDK, no ad SDK and no tracking library " +
                        "is included — by design.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.ok
                )
            }
        }

        item {
            CreditsCard("Data sources") {
                Text(
                    "Every value in this app is read from a real Android API, a real kernel " +
                        "interface (/proc, /sys) or real hardware. When the platform withholds " +
                        "something, the app says \"Unavailable\" or \"Restricted by Android\" " +
                        "and explains why — it never invents a number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            NoticeCard(
                title = "Privacy",
                body = "No telemetry, no analytics, no tracking, no ads, no accounts and no " +
                    "cloud. All history stays in app-private storage on this device. The " +
                    "network is only touched when you press a button in Network Tools.",
                tone = StatusColors.ok
            )
        }

        item {
            CreditsCard("Thanks") {
                Text(
                    "To the Android Open Source Project for the platform interfaces this app " +
                        "reads, and to everyone who tests, reports issues and contributes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CreditsCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
