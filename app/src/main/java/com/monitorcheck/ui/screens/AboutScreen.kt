package com.monitorcheck.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monitorcheck.BuildConfig
import com.monitorcheck.R
import com.monitorcheck.core.Fmt
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors

/**
 * About page: creator profile, support links, build metadata, tech stack, privacy
 * summary and licence. Every external link opens in the browser via an explicit
 * user tap — nothing is contacted automatically.
 */
@Composable
fun AboutScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current

    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    LazyColumn(contentPadding = contentPadding) {

        // ---- Creator ----
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        modifier = Modifier.size(84.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "G",
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Genz", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Developer & creator of Monitored Check",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "@GenzPx",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Button(onClick = { open("https://github.com/GenzPx") }) {
                            Icon(Icons.Default.Person, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GitHub profile")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { open("https://github.com/GenzPx/App-build") }) {
                            Icon(Icons.Default.Code, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Source")
                        }
                    }
                }
            }
        }

        // ---- Support ----
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE91E63).copy(alpha = 0.10f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, null, tint = Color(0xFFE91E63),
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Support the developer",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFE91E63))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Monitored Check is free, open source, ad-free and contains no tracking " +
                            "of any kind. If it is useful to you, a donation helps keep it that way. " +
                            "Completely optional — nothing in the app is ever locked behind payment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { open("https://saweria.co/Genzsenpai") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                    ) { Text("Saweria — Genzsenpai") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { open("https://trakteer.id/Genzsenpai") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Trakteer — Genzsenpai") }
                }
            }
        }

        // ---- App / build info ----
        item {
            AboutCard("Application") {
                MonoRow("name", "Monitored Check")
                MonoRow("version", BuildConfig.VERSION_NAME)
                MonoRow("version code", BuildConfig.VERSION_CODE.toString())
                MonoRow("package", BuildConfig.APPLICATION_ID)
                MonoRow("build type", BuildConfig.BUILD_TYPE)
                MonoRow("min SDK", "24 (Android 7.0)")
                MonoRow("target SDK", "35 (Android 15)")
                MonoRow("running on", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                MonoRow("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            }
        }

        // ---- Tech stack ----
        item {
            AboutCard("Built with") {
                listOf(
                    "Kotlin" to "Primary language, coroutines and Flow throughout",
                    "Jetpack Compose" to "Declarative UI toolkit",
                    "Material 3 / Material You" to "Design system with dynamic colour",
                    "Navigation Compose" to "Screen navigation",
                    "DataStore Preferences" to "Settings persistence",
                    "SQLite" to "Local battery history, no ORM dependency",
                    "EGL / OpenGL ES" to "Real GPU identification",
                    "Choreographer" to "Frame rate measurement"
                ).forEach { (name, desc) ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(desc, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Architecture: modular MVVM with a repository layer. A single central " +
                        "monitoring engine feeds every screen, so cost stays bounded no matter " +
                        "how many cards are visible.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted
                )
            }
        }

        // ---- Privacy ----
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = StatusColors.ok.copy(alpha = 0.10f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = StatusColors.ok,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Privacy policy", style = MaterialTheme.typography.titleMedium,
                            color = StatusColors.ok)
                    }
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "No telemetry, analytics or tracking of any kind",
                        "No advertising and no third-party SDKs",
                        "No account, no sign-in, no personal data collected",
                        "Crash reports stay on this device — the reporter has no network code",
                        "Monitoring history is stored only in app-private storage",
                        "Network is used solely when you run a network tool yourself",
                        "Public IP lookup is the one feature that contacts a third party, " +
                            "and it warns you first"
                    ).forEach {
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("✓ ", color = StatusColors.ok,
                                style = MaterialTheme.typography.bodySmall)
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ---- Data integrity pledge ----
        item {
            NoticeCard(
                title = "The data integrity rule",
                body = "Monitored Check never fabricates a value. There is no random CPU load, " +
                    "no simulated GPU usage, no estimated battery health, no fake FPS and no " +
                    "invented storage health.\n\nEvery number comes from a real Android API or " +
                    "kernel interface. When the platform will not provide something, the app " +
                    "shows the true reason — Unavailable, Unsupported, Permission Required, " +
                    "Restricted by Android, or Requires Root — instead of a plausible-looking " +
                    "number. This is enforced in code, not just by convention."
            )
        }

        // ---- Licence ----
        item {
            AboutCard("Licence") {
                Text(
                    "MIT License\n\nCopyright (c) 2026 Genz (@GenzPx)\n\n" +
                        "Permission is hereby granted, free of charge, to any person obtaining a " +
                        "copy of this software and associated documentation files, to deal in the " +
                        "Software without restriction, including the rights to use, copy, modify, " +
                        "merge, publish, distribute, sublicense and/or sell copies of the Software.\n\n" +
                        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { open("https://github.com/GenzPx/App-build/blob/main/LICENSE") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Read full licence") }
            }
        }

        // ---- Disclaimer ----
        item {
            AboutCard("Disclaimer") {
                Text(
                    "Monitored Check is a diagnostic and informational tool.\n\n" +
                        "Pattern Scanner is a transparent local heuristic, not an antivirus. It " +
                        "has no signature database and cannot certify any file as safe or " +
                        "malicious.\n\nStorage deletion is irreversible and always requires your " +
                        "explicit confirmation. The developer is not liable for data loss " +
                        "resulting from actions you confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Made with care by Genz",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.muted)
                Text("github.com/GenzPx",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted)
            }
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
