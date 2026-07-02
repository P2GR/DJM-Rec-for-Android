package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.R
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo

/** Shows connection status plus the negotiated sample rate / bit depth once a mixer is attached. */
@Composable
fun DeviceStatusCard(
    device: UsbAudioDeviceInfo?,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val connected = device != null && device.hasPermission
        StatusDot(connected = connected)

        Icon(
            imageVector = Icons.Filled.Usb,
            contentDescription = null,
            tint = if (connected) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp).size(28.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device?.productName ?: stringResource(id = R.string.status_no_device),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (device != null) {
                val rate = if (device.negotiatedSampleRate > 0) device.negotiatedSampleRate
                else device.supportedSampleRates.maxOrNull() ?: 0
                Text(
                    text = "${rate} Hz \u2022 ${device.bitResolution}-bit \u2022 ${device.channelCount}ch",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onRescan) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Rescan USB devices",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusDot(connected: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (connected) AccentGreen else Color(0xFF4A4F63),
                shape = CircleShape
            )
    )
}

