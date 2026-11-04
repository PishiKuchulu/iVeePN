package com.pishi.iveepn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pishi.iveepn.R
import com.pishi.iveepn.data.AppLogger
import com.pishi.iveepn.data.LogEntry
import com.pishi.iveepn.data.LogLevel
import com.pishi.iveepn.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter(@StringRes val labelRes: Int, val levels: Set<LogLevel>) {
    ALL(R.string.logs_filter_all, setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)),
    INFO(R.string.logs_filter_info, setOf(LogLevel.INFO)),
    PROBLEMS(R.string.logs_filter_problems, setOf(LogLevel.WARN, LogLevel.ERROR)),
}

private suspend fun writeLogFile(context: Context): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "logs").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(dir, "iveepn-logs-$stamp.txt")
    file.writeText(AppLogger.exportAsText())
    file
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val entries by AppLogger.entries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var expandedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun copyEntry(entry: LogEntry) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.logs_clip_label), entry.message))
        Toast.makeText(context, context.getString(R.string.logs_toast_entry_copied), Toast.LENGTH_SHORT).show()
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            text = { Text(stringResource(R.string.logs_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLogger.clear()
                        showClearConfirm = false
                    },
                ) {
                    Text(stringResource(R.string.common_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    val countsByFilter: Map<LogFilter, Int> = remember(entries) {
        LogFilter.entries.associateWith { option -> entries.count { it.level in option.levels } }
    }
    val visible: List<LogEntry> = remember(entries, filter) {
        if (filter == LogFilter.ALL) entries else entries.filter { it.level in filter.levels }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (visible.size == entries.size) {
                            stringResource(R.string.logs_title_count, entries.size)
                        } else {
                            stringResource(R.string.logs_title_filtered, visible.size, entries.size)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (entries.isEmpty()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.logs_toast_empty),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@IconButton
                        }
                        scope.launch {
                            val file = writeLogFile(context)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, file.name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    shareIntent,
                                    context.getString(R.string.logs_share_chooser),
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.cd_share_logs),
                        )
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                context.getString(R.string.logs_clip_label),
                                AppLogger.exportAsText(),
                            ),
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.logs_toast_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.cd_copy_logs),
                        )
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = stringResource(R.string.cd_clear_logs),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // The shared Spacing.contentMaxWidth cap (520.dp), so log lines
        // do not stretch edge-to-edge on tablets and foldables.
        Column(
            Modifier
                .widthIn(max = Spacing.contentMaxWidth)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in LogFilter.entries) {
                    val count = countsByFilter[option] ?: 0
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },

                        label = { Text("${stringResource(option.labelRes)} ($count)") },
                    )
                }
            }

            if (visible.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(
                            when {
                                entries.isEmpty() -> R.string.logs_empty_none
                                filter == LogFilter.PROBLEMS -> R.string.logs_empty_problems
                                else -> R.string.logs_empty_filter
                            },
                        ),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible.asReversed(), key = { it.id }) { entry ->
                        val color = when (entry.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${timeFormat.format(Date(entry.timestampMillis))} ${entry.level.name} [${entry.tag}]",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = color,
                                )
                                Text(
                                    entry.message,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            expandedIds = if (entry.id in expandedIds) {
                                                expandedIds - entry.id
                                            } else {
                                                expandedIds + entry.id
                                            }
                                        },
                                        onLongClick = { copyEntry(entry) },
                                    ),
                                    maxLines = if (entry.id in expandedIds) Int.MAX_VALUE else 6,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { copyEntry(entry) }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.cd_copy_entry),
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
