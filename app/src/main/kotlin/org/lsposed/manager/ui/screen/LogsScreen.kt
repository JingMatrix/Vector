/*
 * This file is part of Vector.
 *
 * Vector is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vector is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Vector.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2026 Vector Contributors
 */

package org.lsposed.manager.ui.screen

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.manager.App
import org.lsposed.manager.ConfigManager
import org.lsposed.manager.R
import org.lsposed.manager.receivers.LSPManagerServiceHolder
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.extended.Delete
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LogsScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()

    // 监听 isRefreshing 状态，执行实际的日志加载
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            try {
                val verbose = selectedTabIndex == 1
                val logs = withContext(Dispatchers.IO) {
                    val parcel = ConfigManager.getLog(verbose)
                    if (parcel != null) {
                        BufferedReader(InputStreamReader(FileInputStream(parcel.fileDescriptor))).use { br ->
                            br.lines().parallel().toList()
                        }
                    } else {
                        emptyList()
                    }
                }
                logLines = logs
            } catch (e: Throwable) {
                val stackTrace = Log.getStackTraceString(e).split("\n")
                logLines = stackTrace
            } finally {
                isRefreshing = false
            }
        }
    }

    // 切换标签时触发刷新
    LaunchedEffect(selectedTabIndex) {
        isRefreshing = true
    }
    val saveLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.logs_saving, Toast.LENGTH_SHORT).show()
                }

                val cr = context.contentResolver
                cr.openFileDescriptor(uri, "wt")?.use { zipFd ->
                    LSPManagerServiceHolder.getService().getLogs(zipFd)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.logs_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                val cause = e.cause
                val message = cause?.message ?: e.message
                val text = context.getString(R.string.logs_save_failed2, message)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
                Log.w(App.TAG, "save log", e)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.Logs),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            val now = LocalDateTime.now()
                            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                            val filename = "LSPosed_${now.format(formatter)}.zip"
                            saveLogsLauncher.launch(filename)
                        }
                    ) {
                        Icon(MiuixIcons.Download, contentDescription = "Save")
                    }
                    IconButton(
                        onClick = {
                            if (ConfigManager.clearLogs(selectedTabIndex == 1)) {
                                Toast.makeText(context, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
                                isRefreshing = true
                            } else {
                                Toast.makeText(context, R.string.logs_clear_failed_2, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(MiuixIcons.Delete, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding()
                )
        ) {
            TabRow(
                tabs = listOf(
                    stringResource(R.string.nav_item_logs_module),
                    stringResource(R.string.nav_item_logs_verbose)
                ),
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it }
            )

            PullToRefresh(
                isRefreshing = isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = { isRefreshing = true },
                modifier = Modifier.fillMaxSize()
            ) {
                if (logLines.isEmpty() && !isRefreshing) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No logs available",
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        items(logLines) { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
