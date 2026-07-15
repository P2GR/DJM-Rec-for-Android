package com.audiopro.djmrec.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.diagnostics.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Support & diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Create a technical report when USB capture or file encoding behaves unexpectedly.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text("Diagnostic report", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Includes app logs, USB descriptors, capture statistics, and current settings. Audio files are never included.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val report = LogExporter.collectDiagnosticReport(context)
                                    LogExporter.writeReportToFile(context, report)
                                }
                                LogExporter.shareReport(context, file)
                            } catch (error: Exception) {
                                Toast.makeText(
                                    context,
                                    "Failed to export logs: ${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                exporting = false
                            }
                        }
                    }
                ) {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Create and share report")
                    }
                }
            }
        }
    }
}
