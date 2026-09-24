package com.audiopro.djmrec.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.OutlineSubtle
import com.audiopro.djmrec.ui.theme.SurfaceDark
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import com.audiopro.djmrec.ui.theme.DjmRecMotion
import com.audiopro.djmrec.ui.theme.rememberReducedMotion
import com.audiopro.djmrec.update.AppUpdate
import com.audiopro.djmrec.update.UpdateChecker

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    RECORDING("record", "Record", Icons.Filled.FiberManualRecord),
    LIVE("live", "Go Live", Icons.Filled.LiveTv),
    RECORDINGS("recordings", "Recordings", Icons.Filled.LibraryMusic),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
    SUPPORT("support", "Support & diagnostics", Icons.Filled.BugReport)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val destinationState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val context = LocalContext.current
    val application = context.applicationContext as DjmRecApplication
    val recoveryNotice by application.recoveryNotice.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val liveState by viewModel.liveStreamState.collectAsState()
    var selectedDestination by rememberSaveable { mutableStateOf(Destination.RECORDING) }
    // Fullscreen camera console takes over the whole window (no bars); closing it from the
    // console brings the app chrome back while the stream keeps running.
    val cameraMode = selectedDestination == Destination.LIVE && liveState.isActive &&
        liveState.usesCamera && viewModel.cameraConsoleOpen.value
    // The power-saving overlay is a true fullscreen takeover: hide both bars with the console.
    // Scoped to the Record tab so a deep link can't leave other screens without chrome.
    val chromeless = cameraMode ||
        (viewModel.powerSaveActive.value && selectedDestination == Destination.RECORDING)
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }

    LaunchedEffect(Unit) {
        availableUpdate = UpdateChecker.check(context.applicationContext)
    }

    // djmrec://<route> deep links land here and navigate once the shell is up.
    val pendingRoute by viewModel.pendingRoute.collectAsState()
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            Destination.entries.firstOrNull { it.route == route }?.let { selectedDestination = it }
            viewModel.pendingRoute.value = null
        }
    }

    recoveryNotice?.let { message ->
        AlertDialog(
            onDismissRequest = application::dismissRecoveryNotice,
            title = { Text("Recording recovered") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = application::dismissRecoveryNotice) { Text("OK") }
            }
        )
    }

    val mayPromptForUpdate = selectedDestination == Destination.SETTINGS && recoveryNotice == null &&
        recordingState !is RecordingState.Recording &&
        recordingState !is RecordingState.Paused &&
        recordingState !is RecordingState.Preparing
    if (availableUpdate != null && mayPromptForUpdate) {
        val update = availableUpdate!!
        AlertDialog(
            onDismissRequest = {
                UpdateChecker.defer(context, update.tag)
                availableUpdate = null
            },
            title = { Text("DJM REC ${update.version} available") },
            text = { Text("A newer release is ready on GitHub. Recording will never be interrupted for an update.") },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                    availableUpdate = null
                }) { Text("View update") }
            },
            dismissButton = {
                TextButton(onClick = {
                    UpdateChecker.defer(context, update.tag)
                    availableUpdate = null
                }) { Text("Later") }
            }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            if (!chromeless) NavigationBar(containerColor = SurfaceDark, tonalElevation = 0.dp) {
                listOf(Destination.RECORDING, Destination.LIVE, Destination.RECORDINGS, Destination.SETTINGS).forEach { dest ->
                    NavigationBarItem(
                        selected = selectedDestination == dest,
                        onClick = { selectedDestination = dest },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BackgroundDark,
                            selectedTextColor = AccentGreen,
                            indicatorColor = AccentGreen,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        },
        topBar = {
            if (!chromeless) TopAppBar(
                title = {
                    Text(
                        text = if (selectedDestination == Destination.RECORDING) "DJM REC" else selectedDestination.label,
                        color = TextPrimary
                    )
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = TextPrimary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null, tint = TextSecondary) },
                            text = { Text("Support & diagnostics") },
                            onClick = { menuOpen = false; selectedDestination = Destination.SUPPORT }
                        )
                        HorizontalDivider(color = OutlineSubtle)
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.Coffee, contentDescription = null, tint = TextSecondary) },
                            text = { Text("Buy me a coffee") },
                            onClick = {
                                menuOpen = false
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/p2gr"))
                                )
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        val reducedMotion = rememberReducedMotion()
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = selectedDestination,
                transitionSpec = {
                    DjmRecMotion.pageTransform(
                        forward = targetState.ordinal >= initialState.ordinal,
                        reduced = reducedMotion
                    )
                },
                label = "destinations",
                modifier = Modifier.fillMaxSize()
            ) { destination ->
                destinationState.SaveableStateProvider(destination.name) {
                    when (destination) {
                        Destination.RECORDING -> RecorderScreen(viewModel = viewModel, onOpenLibrary = { selectedDestination = Destination.RECORDINGS })
                        Destination.LIVE -> LiveStreamScreen(viewModel = viewModel)
                        Destination.RECORDINGS -> LibraryScreen(onBack = null)
                        Destination.SETTINGS -> SettingsScreen(viewModel = viewModel)
                        Destination.SUPPORT -> DiagnosticsScreen()
                    }
                }
            }
        }
    }
}
