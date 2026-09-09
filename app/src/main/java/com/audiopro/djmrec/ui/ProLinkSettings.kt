package com.audiopro.djmrec.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.audiopro.djmrec.prolink.*

@Composable
fun ProLinkSettings(client: ProLinkClient, usbDescriptors: ByteArray = byteArrayOf()) {
    val state by client.state.collectAsState()
    val options by client.options.collectAsState()
    var networks by remember { mutableStateOf(client.networks()) }
    var selected by remember { mutableStateOf(networks.firstOrNull()?.id) }
    var mac by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    val connected = state.status == LinkStatus.CONNECTED || state.status == LinkStatus.DISCOVERING
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pro DJ Link · experimental", style = MaterialTheme.typography.titleLarge)
        Text("Connect this phone to the CDJs' Ethernet switch using an Ethernet adapter, or Wi-Fi on the same LAN. Keep mixer USB connected for audio. USB-only Link data from DJM-A9 is not verified.")
        Text(remember(usbDescriptors) { UsbLinkEvidence.describe(usbDescriptors) }, style = MaterialTheme.typography.bodySmall)
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        if (!connected) {
            networks.forEach { network ->
                FilterChip(selected == network.id, { selected = network.id }, label = { Text(network.label) })
            }
            OutlinedButton(onClick = {
                networks = client.networks()
                if (networks.none { it.id == selected }) selected = networks.firstOrNull()?.id
            }) { Text("Refresh networks") }
            if (networks.isEmpty()) Text("No Wi-Fi or Ethernet IPv4 network found.")
            TextButton(onClick = { advanced = !advanced }) { Text("Adapter MAC settings") }
            if (advanced) OutlinedTextField(mac, { mac = it.take(17) }, label = { Text("Current adapter MAC (optional)") },
                supportingText = { Text("Only needed when Android hides it. Use the selected adapter's current MAC, including Wi-Fi randomization.") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { networks.find { it.id == selected }?.let { client.connect(it, mac) } },
                enabled = networks.any { it.id == selected }) { Text("Connect to Pro DJ Link") }
        } else OutlinedButton(onClick = client::disconnect) { Text("Disconnect Pro DJ Link") }
        state.devices.forEach { Text("${it.number} · ${it.name} · ${it.address}", style = MaterialTheme.typography.bodySmall) }
        state.decks.forEach { deck ->
            Text("Deck ${deck.number}: ${deck.metadata?.label ?: if (deck.track == null) "No track" else deck.metadataMessage}")
            Text("${if (deck.playing) "Playing" else "Stopped"} · On air: ${deck.onAir?.toString() ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall)
        }
        LinkSwitch("Automatic recording track markers", options.automaticMarkers) { client.setOptions(options.copy(automaticMarkers = it)) }
        LinkSwitch("Require on-air signal", options.requireOnAir) { client.setOptions(options.copy(requireOnAir = it)) }
        Text("Match each CDJ player number to its mixer channel. If on-air is unavailable, turning this off includes every playing deck, including headphone cue playback.", style = MaterialTheme.typography.bodySmall)
        LinkSwitch("Now-playing banner in livestream", options.bannerEnabled) { client.setOptions(options.copy(bannerEnabled = it)) }
        if (options.bannerEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BannerPosition.entries.forEach { position ->
                    FilterChip(options.position == position, { client.setOptions(options.copy(position = position)) },
                        label = { Text(if (position == BannerPosition.TOP) "Top" else "Bottom") })
                }
            }
            OutlinedTextField(options.prefix, { client.setOptions(options.copy(prefix = it)) }, label = { Text("Banner prefix") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            LinkSwitch("Show artist", options.showArtist) { client.setOptions(options.copy(showArtist = it)) }
            LinkSwitch("Light banner background", options.lightBackground) { client.setOptions(options.copy(lightBackground = it)) }
            Text(options.text(state).ifEmpty { "Banner hidden until a selected deck has track metadata." })
        }
    }
}

@Composable
private fun LinkSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, change, modifier = Modifier.semanticsLabel(label))
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier = this.then(
    Modifier.semantics { contentDescription = label }
)
