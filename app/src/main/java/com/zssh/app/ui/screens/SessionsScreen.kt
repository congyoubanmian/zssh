package com.zssh.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zssh.app.data.DeploySettings
import com.zssh.app.ssh.AppSession
import com.zssh.app.ssh.DeployService
import com.zssh.app.ui.theme.AppSemantic
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话列表页：版本检查(M2) + 部署(M2-A 远端自下载) + 模型配置推送 → session/list，多端共享同一份历史。
 *  底部 Tab2：未连接时显示空状态引导去「连接」页，连接后自动初始化。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpen: (String) -> Unit,
    onNewSession: (String) -> Unit,   // 参数=新会话工作目录
    onGoConnect: () -> Unit,          // 未连接时跳 SSH tab
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("连接检查中…") }
    var error by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var showDirPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // ---- 连接门控：仅 Connected 时才跑初始化 ----
    val conn by AppSession.connState.collectAsState()
    val connected = conn is AppSession.ConnState.Connected

    // ---- M2-A 部署：远端未装 zcode-server 时展示 ----
    var retryKey by remember { mutableStateOf(0) }
    var deployNeeded by remember { mutableStateOf(false) }
    var deploying by remember { mutableStateOf(false) }
    val deploySteps = remember { mutableStateListOf<String>() }
    var cdnUrl by remember { mutableStateOf(DeploySettings.cdnBase(ctx)) }

    fun startDeploy() {
        deploying = true; error = null; deploySteps.clear()
        scope.launch {
            try {
                DeploySettings.saveCdnBase(ctx, cdnUrl)
                val cfg = AppSession.config ?: throw IllegalStateException("无连接配置（请从连接列表重新进入）")
                val ssh = AppSession.ensureConnected(cfg)
                val detect = AppSession.detect ?: throw IllegalStateException("缺少平台检测结果，请返回重新连接")
                val outcome = DeployService.deploy(ssh, detect, cdnUrl) { step -> deploySteps.add(step) }
                status = "zcode-server v${outcome.appVersion} ✓（新装 ${outcome.installed.size} 个组件、跳过 ${outcome.skipped.size} 个）"
                deployNeeded = false
                retryKey++   // 重新走初始化：版本检查 → 供应商同步 → 会话列表
            } catch (e: Exception) {
                error = "部署失败：${e.message?.take(400)}"
            } finally {
                deploying = false
            }
        }
    }

    LaunchedEffect(retryKey, connected) {
        // 有配置就自动接通（DetectScreen 只做探测、不保持连接；ensureConnected 幂等，
        // connected 置位后本 effect 重跑进入正式初始化）
        val cfg = AppSession.config ?: return@LaunchedEffect
        try {
            if (!connected) status = "连接服务器中…"
            AppSession.ensureConnected(cfg)
            val v = AppSession.versionCheck()
            if (v == null) {
                status = "远端未部署 zcode-server，可在下方一键部署（远端自行从 CDN 下载，手机不耗流量）"
                deployNeeded = true
                return@LaunchedEffect
            }
            status = "远端 zcode-server v$v ✓（已部署）"
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

    // 搜索过滤：标题 / 工作目录，大小写不敏感
    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions
        else sessions.filter { s ->
            s.optString("title").contains(query, ignoreCase = true) ||
                (s.optJSONObject("workspace")?.optString("workspacePath") ?: "")
                    .contains(query, ignoreCase = true)
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
                title = {
                    val name = AppSession.config?.name
                    Text(if (connected && !name.isNullOrBlank()) "会话 · $name" else "会话")
                },
                actions = {
                    IconButton(onClick = { error = null; retryKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        floatingActionButton = {
            if (ready) {
                ExtendedFloatingActionButton(
                    onClick = { showDirPicker = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新会话") },
                )
            }
        },
    ) { pad ->
        // 空状态只看「有没有连接配置」：正在自动连接（尚未 Connected）时显示状态卡而不是空屏
        if (AppSession.config == null) {
            // ---- 空状态：未配置服务器 ----
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.CloudOff, contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text("尚未连接服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "请先在「连接」页登录 SSH 服务器，\n连接成功后即可查看和管理远端会话。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onGoConnect) { Text("去连接") }
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            // ---- 状态卡 ----
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ready) {
                        Box(Modifier.size(8.dp).background(AppSemantic.success(), CircleShape))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { error = null; retryKey++ }) { Text("重试") }
            }
            if (deployNeeded) {
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        if (deploying) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            deploySteps.forEach { s ->
                                Text(
                                    "• $s", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            OutlinedTextField(
                                cdnUrl, { cdnUrl = it },
                                label = { Text("CDN 基础地址") },
                                placeholder = { Text("https://…（zcode 远端资源发布根目录）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "版本与组件清单从该地址自动发现（latest.json / manifest-{平台}.json），" +
                                    "与桌面端 ZCODE_REMOTE_ASSET_CDN_BASE_URL 同源；组件由远端自行下载并校验 SHA256。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { startDeploy() }, enabled = cdnUrl.isNotBlank()) {
                                Text("部署到远端（远端自行下载）")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // ---- 搜索框：仅在列表可用且非空时显示 ----
            if (ready && sessions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    query, { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("搜索标题或目录") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (ready && sessions.isEmpty()) Text("远端还没有任何会话，点右下角「新会话」开始")
            if (ready && sessions.isNotEmpty() && filtered.isEmpty()) {
                Text("没有匹配的会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                itemsIndexed(filtered, key = { i, s -> s.optString("sessionId").ifBlank { "idx-$i" } }) { _, s ->
                    val ts = s.optLong("updatedAt", 0)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(s.optString("sessionId")) },
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.optString("title").ifBlank { "(无标题)" },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${s.optJSONObject("workspace")?.optString("workspacePath") ?: "?"} · ${if (ts > 0) fmt.format(Date(ts)) else "?"}",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val st = s.optString("status")
                            if (st.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        st,
                                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
