package com.audiopro.djmrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.diagnostics.LogExporter
import com.audiopro.djmrec.diagnostics.RemoteDiagnostics
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.AccentRed
import com.audiopro.djmrec.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var description by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Support & diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Found a bug or a mixer that misbehaves? Send us a report and we will use it to fix support for more devices.",
            color = TextSecondary
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Bug report", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; sent = false; error = null },
                    label = { Text("What went wrong?") },
                    supportingText = {
                        Text(if (description.isBlank()) "Required - a sentence or two helps us reproduce it."
                            else "Include what you expected and what happened.")
                    },
                    isError = description.isBlank() && error != null,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "The report includes this phone and Android version, the connected mixer and its USB descriptors, audio devices, app settings and recent logs. No audio recordings or file names are included. Sending also enables crash reporting so the report can be delivered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Button(
                    enabled = !sending && !exporting && description.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    onClick = {
                        sending = true
                        sent = false
                        error = null
                        scope.launch {
                            val report = withContext(Dispatchers.IO) {
                                "USER DESCRIPTION:\n${description.trim()}\n\n" +
                                    LogExporter.collectDiagnosticReport(context)
                            }
                            RemoteDiagnostics.sendManualReport(description, report) { ok ->
                                sending = false
                                if (ok) sent = true
                                else error = "Could not send the report. Check your internet connection and try again."
                            }
                        }
                    }
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Send report")
                    }
                }
                Text(
                    when {
                        sending -> "Collecting diagnostics and sending..."
                        sent -> "Report sent - thank you!"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sent) AccentGreen else TextSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentRed,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                OutlinedButton(
                    enabled = !exporting && !sending,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        exporting = true
                        scope.launch {
                            runCatching {
                                val file = withContext(Dispatchers.IO) {
                                    val report = LogExporter.collectDiagnosticReport(context)
                                    LogExporter.writeReportToFile(context, report)
                                }
                                LogExporter.shareReport(context, file)
                            }.onFailure {
                                error = "Could not export a copy: ${it.message ?: "unknown error"}"
                            }
                            exporting = false
                        }
                    }
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.height(18.dp))
                    Text(" Export a copy instead")
                }
            }
        }
    }
}
