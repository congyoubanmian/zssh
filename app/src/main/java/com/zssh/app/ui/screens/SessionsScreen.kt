package com.zssh.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zssh.app.ssh.AppSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话列表页：版本检查(M2) + 模型配置推送 → session/list，多端共享同一份历史 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpen: (String) -> Unit,
    onNewSession: (String) -> Unit,   // 参数=新会话工作目录
    onModelConfig: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("连接检查中…") }
    var error by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var showDirPicker by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        try {
            val cfg = AppSession.config ?: throw IllegalStateException("无连接配置")
            AppSession.ensureConnected(cfg)
            val v = AppSession.versionCheck()
            status = if (v != null) "远端 zcode-server v$v ✓（已部署，跳过安装）" else "远端未部署 zcode-server（M2 部署模块待接入）"
            // 供应商配置双向同步：远端 → 手机（拉取电脑端推送的配置），手机 → 远端（推送本地编辑）
            try {
                val pulled = AppSession.pullProviderConfig(ctx)
                if (pulled > 0) status += "；已同步远端 $pulled 个供应商"
            } catch (e: Exception) { /* 拉取失败不阻塞 */ }
            val providers = com.zssh.app.data.ConnectionStore.getProviders(ctx)
            if (providers.isNotEmpty()) {
                val ok = AppSession.pushProviderConfig(providers)
                if (ok) status += "；本地模型配置已推送"
            }
            val agent = AppSession.ensureAgent(AppSession.homeDir)
            val arr = agent.listSessions()
            sessions = (0 until arr.length()).map { arr.getJSONObject(it) }
                .sortedByDescending { it.optLong("updatedAt", 0) }
            ready = true
        } catch (e: Exception) {
            error = e.message ?: "未知错误"
        }
    }

    if (showDirPicker) {
        DirPickerDialog(
            initial = AppSession.homeDir,
            onConfirm = { showDirPicker = false; onNewSession(it) },
            onDismiss = { showDirPicker = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话列表 · ${AppSession.config?.name ?: ""}") },
                actions = { TextButton(onClick = onModelConfig) { Text("模型配置") } },
            )
        },
        floatingActionButton = {
            if (ready) FloatingActionButton(onClick = { showDirPicker = true }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            if (ready && sessions.isEmpty()) Text("远端还没有任何会话，点 + 开始新会话")
            LazyColumn(Modifier.fillMaxSize()) {
                items(sessions, key = { it.optString("sessionId") }) { s ->
                    val ts = s.optLong("updatedAt", 0)
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(s.optString("sessionId")) }.padding(12.dp, 8.dp),
                    ) {
                        Text(s.optString("title").ifBlank { "(无标题)" }, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${s.optJSONObject("workspace")?.optString("workspacePath") ?: "?"} · ${if (ts > 0) fmt.format(Date(ts)) else "?"} · ${s.optString("status")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
