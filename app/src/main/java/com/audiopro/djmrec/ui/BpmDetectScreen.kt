package com.audiopro.djmrec.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholder screen for BPM detection and tap-tempo.
 * Will host tap-tempo, auto-BPM from mic, and beat-grid display.
 */
@Composable
fun BpmDetectScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "BPM Detect — coming soon")
    }
}
