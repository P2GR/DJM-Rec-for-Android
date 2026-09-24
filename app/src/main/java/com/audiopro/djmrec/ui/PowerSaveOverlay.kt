package com.audiopro.djmrec.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.audiopro.djmrec.ui.theme.AccentRed
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import com.audiopro.djmrec.ui.theme.rememberReducedMotion

/**
 * Battery-saver overlay: a true-black (AMOLED-off) screen showing only a red "recording
 * live" dot and the running timer. Waveform, meters and previews leave composition while
 * this is up (which also switches off the native waveform analyzer through the usual
 * visibility plumbing) and the system bars are hidden.
 *
 * Purely visual: capture, encoding and the recording notification are untouched, so an
 * ongoing recording never notices power saving mode.
 */
@Composable
fun PowerSaveOverlay(
    recording: Boolean,
    saving: Boolean,
    elapsedMillis: Long,
    onStop: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    // Predictable back: system back closes power saving instead of leaving the app.
    BackHandler(onBack = onClose)
    DisposableEffect(context) {
        val activity = context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Classic blinking REC dot while recording (steady grey when armed only, steady red
            // under reduced motion). The blink is pure alpha on one 14dp dot -- negligible power
            // versus the blacked-out screen.
            val dotAlpha = if (!recording || reducedMotion) 1f else {
                val transition = rememberInfiniteTransition(label = "recDot")
                val blink by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 1_000
                            1f at 600
                            0f at 700
                        },
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "recDotBlink"
                )
                blink
            }
            Box(
                Modifier.size(14.dp).background(
                    (if (recording) AccentRed else Color(0xFF4A4F63)).copy(alpha = dotAlpha),
                    CircleShape
                )
            )
            Text(
                elapsedText(elapsedMillis),
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextPrimary
            )
            Text(
                when {
                    saving -> "Saving..."
                    recording -> "Recording live"
                    else -> "Monitoring - not recording"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp)
            ) { Text("Close") }
            Button(
                onClick = onStop,
                enabled = recording && !saving,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
            ) { Text(if (saving) "Saving" else "Stop recording") }
        }
    }
}
