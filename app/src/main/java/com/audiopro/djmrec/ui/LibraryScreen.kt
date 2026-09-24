package com.audiopro.djmrec.ui

import android.content.Intent
import android.database.ContentObserver
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.storage.*
import com.audiopro.djmrec.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun LibraryScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recordings by remember { mutableStateOf<List<LibraryRecording>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<LibraryRecording?>(null) }
    var rename by remember { mutableStateOf<LibraryRecording?>(null) }
    var renameText by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<LibraryRecording?>(null) }
    var exportTarget by remember { mutableStateOf<LibraryRecording?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = exportTarget
        if (uri != null && target != null) scope.launch {
            busy = true
            withContext(Dispatchers.IO) { runCatching { RecordingLibrary.export(context, target, uri) } }
                .onSuccess { snackbarHostState.showSnackbar("Exported \"${target.displayName}\"") }
                .onFailure { error = it.message ?: "Could not export a copy. Check free storage and choose a different location." }
            busy = false
        }
        exportTarget = null
    }
    fun share(recording: LibraryRecording) {
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = recording.mimeType
            putExtra(Intent.EXTRA_STREAM, recording.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(recording.displayName, recording.uri)
        }, "Share set")) }.onFailure { error = "No sharing app is available" }
    }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) { runCatching { RecordingLibrary.list(context) } }
            .onSuccess { recordings = it }.onFailure { error = it.message ?: "Could not load recordings. Check storage permission and refresh." }
        loading = false
    }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refresh++ }
        }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your recordings", style = MaterialTheme.typography.headlineSmall)
                Text("${recordings.size} recordings / ${sizeLabel(recordings.sumOf { it.size })}", color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh sets") }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search recordings") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val visible = recordings.filter { it.displayName.contains(query, ignoreCase = true) }
        if (visible.isEmpty() && !loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "Your saved recordings will appear here.\nRecordings in progress stay hidden."
                    else "No matching recordings", color = TextSecondary)
            }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(visible, key = { it.uri.toString() }) { recording ->
                var menu by remember { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDark) {
                    Row(Modifier.fillMaxWidth().clickable { selected = recording }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, null, Modifier.size(28.dp), tint = AccentGreen)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(recording.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${recording.extension.uppercase()} / ${elapsedText(recording.duration)} / ${sizeLabel(recording.size)}",
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions for ${recording.title}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; selected = recording })
                                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; share(recording) })
                                DropdownMenuItem(text = { Text("Export a copy") }, enabled = !busy, onClick = { menu = false; exportTarget = recording; export.launch(recording.displayName) })
                                DropdownMenuItem(text = { Text("Rename") }, enabled = !busy, onClick = { menu = false; rename = recording; renameText = recording.title })
                                DropdownMenuItem(text = { Text("Delete", color = AccentRed) }, enabled = !busy, onClick = { menu = false; delete = recording })
                            }
                        }
                    }
                }
            }
        }
        selected?.let { recording -> SetPlayer(recording, onClose = { selected = null }, onError = { error = it }) }
        SnackbarHost(snackbarHostState, Modifier.fillMaxWidth())
    }
    rename?.let { recording -> AlertDialog(onDismissRequest = { if (!busy) rename = null }, title = { Text("Rename set") },
        text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Recording name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                val requestedName = renameText
                selected = selected?.takeUnless { it.uri == recording.uri }
                withContext(Dispatchers.IO) { runCatching { RecordingLibrary.rename(context, recording, requestedName) } }
                    .onSuccess {
                        rename = null
                        refresh++
                        snackbarHostState.showSnackbar("Renamed to \"$requestedName\"")
                    }
                    .onFailure { error = it.message ?: "Could not rename this recording. Try a different name." }
                busy = false
            }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { rename = null }, enabled = !busy) { Text("Cancel") } }) }
    delete?.let { recording -> AlertDialog(onDismissRequest = { if (!busy) delete = null }, title = { Text("Delete this recording?") },
        text = { Text("${recording.displayName}\nYou can undo this right after deleting. Older file-based recordings are removed permanently.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val target = recording
            selected = selected?.takeUnless { it.uri == target.uri }
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { runCatching { RecordingLibrary.trash(context, target) } }
                busy = false
                delete = null
                result.onSuccess { undoable ->
                    refresh++
                    if (undoable) {
                        val outcome = snackbarHostState.showSnackbar(
                            message = "Moved to Trash", actionLabel = "Undo",
                            duration = SnackbarDuration.Short
                        )
                        if (outcome == SnackbarResult.ActionPerformed) {
                            withContext(Dispatchers.IO) { runCatching { RecordingLibrary.untrash(context, target) } }
                        }
                        refresh++
                    } else {
                        snackbarHostState.showSnackbar("Recording deleted")
                    }
                }.onFailure {
                    error = "Could not delete \"${target.displayName}\": ${it.message ?: "the file is busy"}. Close other apps using it and try again."
                    refresh++
                }
            }
        }) { Text("Delete", color = AccentRed) } }, dismissButton = { TextButton(onClick = { delete = null }, enabled = !busy) { Text("Keep") } }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Could not complete action") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

@Composable
private fun SetPlayer(recording: LibraryRecording, onClose: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(recording.uri) { MediaPlayer() }
    var ready by remember(recording.uri) { mutableStateOf(false) }
    var playing by remember(recording.uri) { mutableStateOf(false) }
    var position by remember(recording.uri) { mutableLongStateOf(0L) }
    var duration by remember(recording.uri) { mutableLongStateOf(recording.duration) }
    DisposableEffect(player) {
        player.setOnPreparedListener { ready = true; duration = it.duration.toLong(); it.start(); playing = true }
        player.setOnCompletionListener { playing = false; position = duration }
        player.setOnErrorListener { _, _, _ -> playing = false; ready = false; onError("This recording could not be played"); true }
        runCatching { player.setDataSource(context, recording.uri); player.prepareAsync() }.onFailure { onError("Could not open recording") }
        onDispose { player.release() }
    }
    LaunchedEffect(player, playing) { while (playing) { position = runCatching { player.currentPosition.toLong() }.getOrDefault(position); delay(200) } }
    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceVariantDark) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = ready, onClick = { if (playing) player.pause() else player.start(); playing = !playing }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause playback" else "Play recording")
                }
                Text(recording.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close player") }
            }
            Slider(position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                { position = it.toLong(); player.seekTo(it.toInt()) }, enabled = ready,
                valueRange = 0f..duration.coerceAtLeast(1).toFloat())
            Text("${elapsedText(position)} / ${elapsedText(duration)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun sizeLabel(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
