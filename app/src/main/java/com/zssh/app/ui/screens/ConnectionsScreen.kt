package com.zssh.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zssh.app.data.AuthType
import com.zssh.app.data.ConnectionConfig
import com.zssh.app.data.ConnectionStore
import com.zssh.app.ssh.AppSession
import com.zssh.app.ui.theme.AppSemantic

/**
 * SSH 连接列表页（底部 Tab1，对应桌面端向导第 1 步"选择方式"的主入口）。
 * 顶部为「当前连接」状态卡（仅在已连接时显示）；下方为连接卡片列表，
 * 整卡点击即连接，卡内提供 连接/编辑/删除 操作（删除需二次确认）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onAdd: () -> Unit,
    onConnect: (ConnectionConfig) -> Unit,
    onEdit: (ConnectionConfig) -> Unit,
) {
    val ctx = LocalContext.current
    var conns by remember { mutableStateOf(ConnectionStore.list(ctx)) }
    val connState by AppSession.connState.collectAsState()
    var pendingDelete by remember { mutableStateOf<ConnectionConfig?>(null) }

    // remember 只在首次组合时执行；这里挂 ON_RESUME 观察者，
    // 保证从编辑页返回（resume）时列表重新加载
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) conns = ConnectionStore.list(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 删除二次确认弹窗
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除连接") },
            text = { Text("确定删除「${target.name}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ConnectionStore.delete(ctx, target.id)
                    conns = ConnectionStore.list(ctx)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SSH 连接") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建连接") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            (connState as? AppSession.ConnState.Connected)?.let { connected ->
                CurrentConnectionCard(
                    label = connected.label,
                    version = AppSession.serverVersion,
                    // 断开走后台线程：disconnect 是阻塞网络操作，主线程调用会卡 UI
                    // 只断当前活动连接（多连接设计：其他机器保持后台存活）
                    onDisconnect = { Thread { AppSession.disconnect() }.start() },
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp),
                )
            }
            if (conns.isEmpty()) {
                EmptyState(onAdd = onAdd, modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conns, key = { it.id }) { c ->
                        ConnectionCard(
                            conn = c,
                            isActive = AppSession.config?.id == c.id,
                            isConnected = AppSession.isConnected(c.id),
                            onConnect = { onConnect(c) },
                            onEdit = { onEdit(c) },
                            onDelete = { pendingDelete = c },
                        )
                    }
                }
            }
        }
    }
}

/** 「当前连接」状态卡：在线绿点 + 地址（默认脱敏） + zcode-server 版本 + 断开按钮 */
@Composable
private fun CurrentConnectionCard(
    label: String,
    version: String?,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddress by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 在线绿点
            Box(Modifier.size(10.dp).clip(CircleShape).background(AppSemantic.success()))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showAddress) label else maskAddressLabel(label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton(onClick = { showAddress = !showAddress }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (showAddress) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (showAddress) "隐藏地址" else "显示地址",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    version?.let { "zcode v$it" } ?: "版本未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDisconnect) { Text("断开") }
        }
    }
}

/** 单条连接卡片：圆形头像 + 名称/地址 + 认证方式角标；底部操作行（连接/编辑/删除）。整卡点击 = 连接 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionCard(
    conn: ConnectionConfig,
    isActive: Boolean,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // 地址默认脱敏（列表页是公开视野，主机/端口属于敏感信息）；点眼睛临时显示
    var showAddress by remember { mutableStateOf(false) }
    Card(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 圆形头像：primary 底色 + 名称首字符大写
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        conn.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        conn.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showAddress) "${conn.username}@${conn.host}:${conn.port}"
                            else maskAddress(conn.username, conn.host, conn.port),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        IconButton(onClick = { showAddress = !showAddress }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (showAddress) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (showAddress) "隐藏地址" else "显示地址",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 认证方式小角标
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(
                        if (conn.authType == AuthType.PASSWORD) "密码" else "私钥",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp, 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onConnect,
                    contentPadding = PaddingValues(16.dp, 0.dp),
                ) {
                    Text(
                        when {
                            isActive -> "当前"
                            isConnected -> "切换"   // 后台存活的连接：点击只切 active，不断旧
                            else -> "连接"
                        }
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 空状态：无连接时的引导（大图标 + 说明 + 新建按钮） */
@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Terminal,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("还没有 SSH 连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "添加一台服务器，即可在手机上进行远程 AI 编程",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) { Text("新建连接") }
    }
}

/** 地址脱敏：user@1**.***.***.***:**（host 首段/IP 首段保留便于辨认，端口一律打星） */
private fun maskAddress(user: String, host: String, port: Int): String {
    val maskedHost = when {
        host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' } -> {
            // IPv4：首段可见，其余打星
            host.substringBefore('.') + ".***.***.***"
        }
        host.length > 4 -> host.take(2) + "***" + host.takeLast(2)
        else -> "***"
    }
    return "$user@$maskedHost:***"
}

/** 连接标签（user@host:port）脱敏：复用 maskAddress 的打星规则 */
private fun maskAddressLabel(label: String): String {
    val at = label.indexOf('@')
    val colon = label.lastIndexOf(':')
    if (at <= 0 || colon <= at) return label.take(3) + "***"
    return maskAddress(label.substring(0, at), label.substring(at + 1, colon), label.substring(colon + 1).toIntOrNull() ?: 22)
}
