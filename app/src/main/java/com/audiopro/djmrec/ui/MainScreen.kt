package com.audiopro.djmrec.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.SurfaceDark
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private enum class Destination(val label: String, val icon: ImageVector) {
    RECORDING("Recording", Icons.Filled.FiberManualRecord),
    RMX_SIM("RMX-1000 Sim", Icons.Filled.Tune),
    BPM_DETECT("BPM Detect", Icons.Filled.Speed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedDestination by rememberSaveable { mutableStateOf(Destination.RECORDING) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = selectedDestination != Destination.RMX_SIM,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = SurfaceDark
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "DJM-REC",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(8.dp))

                Destination.entries.forEach { dest ->
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = dest.icon,
                                contentDescription = dest.label,
                                tint = if (selectedDestination == dest) TextPrimary else TextSecondary
                            )
                        },
                        label = {
                            Text(
                                text = dest.label,
                                color = if (selectedDestination == dest) TextPrimary else TextSecondary
                            )
                        },
                        selected = selectedDestination == dest,
                        onClick = {
                            selectedDestination = dest
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = TextSecondary.copy(alpha = 0.12f),
                            unselectedContainerColor = SurfaceDark
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = BackgroundDark,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = selectedDestination.label,
                            color = TextPrimary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Menu",
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (selectedDestination) {
                    Destination.RECORDING -> RecorderScreen(viewModel = viewModel)
                    Destination.RMX_SIM -> RmxSimulatorScreen()
                    Destination.BPM_DETECT -> BpmDetectScreen()
                }
            }
        }
    }
}
