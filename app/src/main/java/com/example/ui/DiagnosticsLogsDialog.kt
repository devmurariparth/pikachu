package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.AssistantLogger
import com.example.LogLevel
import com.example.CrashPreventionManager
import com.example.data.BatteryOptimizationManager
import com.example.network.NetworkConnectivityManager

@Composable
fun DiagnosticsLogsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logs by AssistantLogger.logs.collectAsState()
    val isNetworkOnline by NetworkConnectivityManager.isNetworkAvailable.collectAsState()
    val networkType by NetworkConnectivityManager.networkType.collectAsState()
    val batteryLevel by BatteryOptimizationManager.batteryLevel.collectAsState()
    val isCharging by BatteryOptimizationManager.isCharging.collectAsState()
    val isLowBatteryActive by BatteryOptimizationManager.isLowBatteryActive.collectAsState()
    val crashReport = remember { CrashPreventionManager.getLastCrashReport(context) }

    var selectedLevelFilter by remember { mutableStateOf<LogLevel?>(null) }

    val filteredLogs = remember(logs, selectedLevelFilter) {
        if (selectedLevelFilter == null) logs
        else logs.filter { it.level == selectedLevelFilter }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .testTag("diagnostics_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "System Diagnostics & Telemetry",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Production Observability & Live Logs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_diagnostics_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close diagnostics")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Telemetry Overview Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isNetworkOnline) "🌐 Network: Online ($networkType)" else "⚠️ Network: Offline",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (isNetworkOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "🔋 Battery: $batteryLevel% ${if (isCharging) "⚡" else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (isLowBatteryActive) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚡ Low Battery Optimization: ACTIVE (wake word & visuals throttled)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (crashReport != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = crashReport,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Filter chips and log actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selectedLevelFilter == null,
                            onClick = { selectedLevelFilter = null },
                            label = { Text("All (${logs.size})", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedLevelFilter == LogLevel.WARN,
                            onClick = { selectedLevelFilter = if (selectedLevelFilter == LogLevel.WARN) null else LogLevel.WARN },
                            label = { Text("Warn", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedLevelFilter == LogLevel.ERROR,
                            onClick = { selectedLevelFilter = if (selectedLevelFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                            label = { Text("Error", fontSize = 11.sp) }
                        )
                    }

                    Row {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("MJ Logs", AssistantLogger.getAllLogsFormatted())
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("copy_logs_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                AssistantLogger.clear()
                                CrashPreventionManager.clearCrashReport(context)
                                Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("clear_logs_button")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Logs list
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(8.dp)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No log records found for current filter.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true
                        ) {
                            items(filteredLogs) { entry ->
                                val color = when (entry.level) {
                                    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                                    LogLevel.WARN -> Color(0xFFE65100)
                                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                }
                                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text(
                                        text = entry.toFormattedString(),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = color,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
