package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.ui.theme.AccentRed

/**
 * Transport row. Only ever shows the actions valid for the current [RecordingState]:
 * Idle/Preparing/Monitoring -> Record; Recording -> Pause + Stop; Paused -> Resume + Stop.
 */
@Composable
fun TransportControls(
    state: RecordingState,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        when (state) {
            is RecordingState.Idle, is RecordingState.Error, is RecordingState.Monitoring,
            is RecordingState.Preparing -> {
                TransportButton(
                    icon = Icons.Filled.FiberManualRecord,
                    contentDescription = "Record",
                    containerColor = AccentRed,
                    onClick = onRecord
                )
            }

            is RecordingState.Recording -> {
                TransportButton(
                    icon = Icons.Filled.Pause,
                    contentDescription = "Pause",
                    containerColor = MaterialTheme.colorScheme.secondary,
                    onClick = onPause
                )
                TransportButton(
                    icon = Icons.Filled.Stop,
                    contentDescription = "Stop",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onStop
                )
            }

            is RecordingState.Paused -> {
                TransportButton(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = "Resume",
                    containerColor = MaterialTheme.colorScheme.primary,
                    onClick = onResume
                )
                TransportButton(
                    icon = Icons.Filled.Stop,
                    contentDescription = "Stop",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onStop
                )
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(32.dp))
    }
}
