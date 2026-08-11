package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monitorcheck.core.InfoSection
import com.monitorcheck.hardware.display.DisplayRepository
import com.monitorcheck.hardware.gpu.GpuRepository
import com.monitorcheck.storage.StorageRepository
import com.monitorcheck.system.DriverRepository
import com.monitorcheck.system.SystemRepository
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Generic scrollable page rendering a list of [InfoSection]s. */
@Composable
fun SectionListScreen(
    sections: List<InfoSection>,
    contentPadding: PaddingValues,
    header: (@Composable () -> Unit)? = null,
    showSources: Boolean = true
) {
    LazyColumn(contentPadding = contentPadding) {
        header?.let { item { it() } }
        items(sections.size) { i -> SectionCard(sections[i], showSources = showSources) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun DeviceInfoScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    // Build constants and feature flags are static, so compute once off the main thread.
    val sections by produceState(initialValue = emptyList<InfoSection>()) {
        value = withContext(Dispatchers.IO) {
            SystemRepository(context.applicationContext).deviceSections()
        }
    }
    SectionListScreen(sections, contentPadding)
}

@Composable
fun KernelScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sections by produceState(initialValue = emptyList<InfoSection>()) {
        value = withContext(Dispatchers.IO) {
            val repo = SystemRepository(context.applicationContext)
            repo.kernelSections() + repo.selinuxSection() + repo.integritySection()
        }
    }
    SectionListScreen(sections, contentPadding)
}

@Composable
fun SelinuxScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sections by produceState(initialValue = emptyList<InfoSection>()) {
        value = withContext(Dispatchers.IO) {
            val repo = SystemRepository(context.applicationContext)
            listOf(repo.selinuxSection(), repo.integritySection())
        }
    }
    SectionListScreen(sections, contentPadding, header = {
        NoticeCard(
            title = "Read-only inspection",
            body = "Monitored Check only reads SELinux state from the standard kernel interface. " +
                "It never attempts to change the policy, switch to permissive mode, or use root."
        )
    })
}

@Composable
fun BinderScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sections by produceState(initialValue = emptyList<InfoSection>()) {
        value = withContext(Dispatchers.IO) {
            listOf(SystemRepository(context.applicationContext).binderSection())
        }
    }
    SectionListScreen(sections, contentPadding, header = {
        NoticeCard(
            title = "Binder diagnostics",
            body = "Binder is Android's IPC layer. Detailed Binder statistics live in debugfs, " +
                "which modern Android keeps unmounted or SELinux-protected for applications. " +
                "This page reports exactly what is readable on this device without root."
        )
    })
}

@Composable
fun DriversScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sections by produceState(initialValue = emptyList<InfoSection>()) {
        value = withContext(Dispatchers.IO) {
            DriverRepository(context.applicationContext).sections()
        }
    }
    SectionListScreen(sections, contentPadding, header = {
        NoticeCard(
            title = "About driver information",
            body = "Android has no driver enumeration API for applications. This page combines " +
                "the capabilities each framework subsystem reports with the kernel nodes that " +
                "are world-readable on this device. A complete driver list would require root, " +
                "which Monitored Check does not use."
        )
    })
}

@Composable
fun GpuScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    // The EGL/GL query is expensive, so run it off the main thread and cache it.
    val sections by produceState(initialValue = emptyList<InfoSection>()) {
        value = withContext(Dispatchers.IO) {
            GpuRepository(context.applicationContext).infoSections()
        }
    }
    SectionListScreen(sections, contentPadding)
}

@Composable
fun DisplayScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val repo = remember { DisplayRepository(context) }
    val sections = remember { repo.infoSections() }
    SectionListScreen(sections, contentPadding)
}

@Composable
fun StorageInfoScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sections by produceState(initialValue = emptyList<InfoSection>()) {
        value = withContext(Dispatchers.IO) {
            StorageRepository(context.applicationContext).infoSections()
        }
    }
    SectionListScreen(sections, contentPadding)
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
