package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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

/**
 * Which USB stereo pair to demux out of a multichannel Pioneer mixer capture (e.g. the
 * DJM-900NXS2 presents 5 pairs / 10ch to the app). Mirrors the per-output picker in Pioneer's
 * own Windows/Mac Setting Utility, since the app can't reliably tell which pair the mixer's MIX
 * output actually lands on -- vendor MIX/REC OUT routing is best-effort and not confirmed on
 * every model. AUTO keeps the existing auto-pick-loudest-pair behavior.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelPairSelector(
    selectedOffset: Int,
    pairCount: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChannelPairTile(
            label = "AUTO",
            selected = selectedOffset < 0,
            enabled = enabled,
            onClick = { onSelect(-1) }
        )
        for (pair in 0 until pairCount) {
            val offset = pair * 2
            ChannelPairTile(
                label = "${offset + 1}-${offset + 2}",
                selected = selectedOffset == offset,
                enabled = enabled,
                onClick = { onSelect(offset) }
            )
        }
    }
}

@Composable
private fun ChannelPairTile(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick
        ),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}
