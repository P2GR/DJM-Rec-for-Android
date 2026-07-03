package com.audiopro.djmrec.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholder screen for the RMX-1000-style effects simulator.
 * Will host a touch-based effects pad with reverb, delay, filter, and bit-crush.
 */
@Composable
fun RmxSimulatorScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "RMX-1000 Simulator — coming soon")
    }
}
