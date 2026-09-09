package com.audiopro.djmrec.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProtocolResearchPanel() {
    if (!BuildConfig.PROTOCOL_RESEARCH) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val midi = remember { UsbMidiListener(context.applicationContext) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var marker by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(midi) { onDispose { midi.close() } }
    LaunchedEffect(Unit) { while (true) { delay(500); revision++ } }
    val snapshot = remember(revision) { ProtocolTrace.snapshot() }
    LaunchedEffect(snapshot.active) { if (!snapshot.active) midi.close() }
    Text("Protocol lab", style = MaterialTheme.typography.titleLarge)
    Text("Opt-in local capture, up to 10 minutes / 8 MiB / 16,384 events. Includes raw metadata, network addresses and USB identifiers. No audio payloads. Share only after review.")
    Text("${snapshot.reason} · ${snapshot.events.size} events · ${snapshot.dropped} dropped")
    Button(enabled = !busy, onClick = {
        if (snapshot.active) { midi.close(); ProtocolTrace.stop() } else ProtocolTrace.start()
        revision++
    }) { Text(if (snapshot.active) "Stop capture" else "Start new capture") }
    OutlinedTextField(value = marker, onValueChange = { marker = it.take(120) }, label = { Text("Experiment note (e.g. deck 1 loaded track)") })
    Button(enabled = snapshot.active && marker.isNotBlank(), onClick = {
        ProtocolTrace.event("marker", marker); marker = ""; revision++
    }) { Text("Add timestamped note") }
    Button(enabled = snapshot.active && !busy, onClick = { message = midi.start() }) { Text("Listen to USB MIDI") }
    Button(enabled = snapshot.active && !busy, onClick = {
        busy = true
        scope.launch {
            try { message = withContext(Dispatchers.IO) { UsbResearch.snapshot(context) } }
            catch (e: Exception) { message = e.message ?: "USB inspection failed" }
            finally { busy = false; revision++ }
        }
    }) { Text("Capture USB descriptors") }
    Text("A9 state probe: five read-only requests recovered from the Windows utility. Input selector / Serato state only; no track-name query. Run while recording and monitoring are stopped.")
    Button(enabled = snapshot.active && !busy, onClick = {
        busy = true
        scope.launch {
            try { message = withContext(Dispatchers.IO) { UsbResearch.readA9(context) } }
            catch (e: Exception) { message = e.message ?: "USB probe failed" }
            finally { busy = false; revision++ }
        }
    }) { Text("Read A9 mixer state once") }
    Button(enabled = snapshot.events.isNotEmpty() && !busy, onClick = {
        busy = true; midi.close()
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ProtocolTrace.export(context) }
                LogExporter.shareReport(context, file)
            } catch (e: Exception) { message = e.message ?: "Trace export failed" }
            finally { busy = false; revision++ }
        }
    }) { Text("Stop and share raw trace") }
    TextButton(enabled = !busy, onClick = {
        midi.close(); ProtocolTrace.clear()
        java.io.File(context.cacheDir, "logs/protocol-trace.ndjson").delete()
        revision++; message = "Trace cleared"
    }) { Text("Clear raw trace") }
    if (message.isNotBlank()) Text(message)
}
