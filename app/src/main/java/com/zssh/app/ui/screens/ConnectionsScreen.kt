package com.zssh.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zssh.app.data.ConnectionConfig
import com.zssh.app.data.ConnectionStore

/** 连接列表页（对应桌面端向导第 1 步"选择方式"的主入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onAdd: () -> Unit,
    onConnect: (ConnectionConfig) -> Unit,
    onEdit: (ConnectionConfig) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var conns by remember { mutableStateOf(ConnectionStore.list(ctx)) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("远程连接") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Text("+", style = MaterialTheme.typography.titleLarge) }
        },
    ) { pad ->
        if (conns.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无连接，点击 + 新建")
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(conns, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text("${c.username}@${c.host}:${c.port}") },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { onConnect(c) }) { Text("连接") }
                                TextButton(onClick = { onEdit(c) }) { Text("编辑") }
                                TextButton(onClick = {
                                    ConnectionStore.delete(ctx, c.id)
                                    conns = ConnectionStore.list(ctx)
                                }) { Text("删除") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
