package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.audio.RecordingFormat

/** Compact output-format tiles. Selection remains available during live monitoring. */
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        formats.forEach { format ->
            val detail = when (format) {
                RecordingFormat.WAV -> "PCM"
                RecordingFormat.FLAC -> "LOSSLESS"
                RecordingFormat.MP3 -> "320 KBPS"
            }
            val isSelected = selected == format
            Surface(
                modifier = Modifier.selectable(
                    selected = isSelected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onSelect(format) }
                ).weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                    Text(
                        text = format.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
