package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.audiopro.djmrec.R
import com.audiopro.djmrec.audio.RecordingFormat

/** Radio-button row for choosing the output container. Disabled entirely while recording. */
@Composable
fun FormatSelector(
    selected: RecordingFormat,
    formats: List<RecordingFormat>,
    enabled: Boolean,
    onSelect: (RecordingFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        formats.forEach { format ->
            val labelRes = when (format) {
                RecordingFormat.WAV -> R.string.format_wav
                RecordingFormat.FLAC -> R.string.format_flac
                RecordingFormat.MP3 -> R.string.format_mp3
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.selectable(
                    selected = selected == format,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onSelect(format) }
                )
            ) {
                RadioButton(
                    selected = selected == format,
                    onClick = null,
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = stringResource(id = labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
