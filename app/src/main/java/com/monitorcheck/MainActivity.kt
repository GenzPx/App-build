package com.monitorcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.MonitoredCheckApp
import com.monitorcheck.ui.theme.MonitoredCheckTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Tell the monitoring engine when we are visible so it can throttle polling
        // while backgrounded. This is the main battery-saving lever.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.setForeground(true)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    viewModel.setForeground(false)
                }
            }
        }

        setContent {
            val settings by viewModel.settings.collectAsState()
            MonitoredCheckTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                MonitoredCheckApp(viewModel)
            }
        }
    }
}
