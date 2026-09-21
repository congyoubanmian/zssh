package com.zssh.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zssh.app.data.DeploySettings
import com.zssh.app.ssh.AppSession
import com.zssh.app.ssh.DeployService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 工具页（底部 Tab3）：模型配置 / 套餐额度 / 检查远端更新 / 引擎版本 / 部署·重装 zcode-server / 远端日志 / 定时任务 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onModelConfig: () -> Unit,
    onQuota: () -> Unit,
    onLogs: () -> Unit,
    onGoConnect: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by AppSession.connState.collectAsState()
    val connected = conn is AppSession.ConnState.Connected

    // ---- 检查远端更新 ----
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf<String?>(null) }

    fun checkUpdate() {
        if (checkingUpdate) return
        val base = DeploySettings.cdnBase(ctx)
        if (base.isBlank()) {
            updateDialog = "尚未配置 CDN 基础地址，请先到「我的」页设置后再检查更新。"
            return
        }
        checkingUpdate = true
        scope.launch {
            try {
                val cur = AppSession.versionCheck()   // null = 远端未部署
                val raw = withContext(Dispatchers.IO) { httpGet("$base/latest.json") }
                val latest = parseLatestVersion(raw)
                val curText = cur ?: "未部署"
                updateDialog = when {
                    latest == null ->
                        "当前版本：$curText\n\n无法从 latest.json 解析出版本号，原文摘要：\n${raw.trim().take(150)}"
                    cur == latest ->
                        "当前版本：$curText\n最新版本：$latest\n\n已是最新 ✓"
                    else ->
                        "当前版本：$curText\n最新版本：$latest\n\n发现新版本，可用下方「部署 / 重装 zcode-server」升级。"
                }
            } catch (e: Exception) {
                updateDialog = "检查更新失败：${e.message?.take(300)}"
            } finally {
                checkingUpdate = false
            }
        }
    }

    // ---- 部署 / 重装（M2-A 远端自下载）----
    var deployExpanded by remember { mutableStateOf(false) }
    var deploying by remember { mutableStateOf(false) }
    val deploySteps = remember { mutableStateListOf<String>() }
    var deployResult by remember { mutableStateOf<String?>(null) }
    var deployError by remember { mutableStateOf<String?>(null) }
    var cdnUrl by remember { mutableStateOf(DeploySettings.cdnBase(ctx)) }

    fun startDeploy() {
        deploying = true; deployError = null; deployResult = null; deploySteps.clear()
        scope.launch {
            try {
                DeploySettings.saveCdnBase(ctx, cdnUrl)
                val cfg = AppSession.config ?: throw IllegalStateException("无连接配置，请从 SSH 页重新连接")
                val ssh = AppSession.ensureConnected(cfg)
                val detect = AppSession.detect ?: throw IllegalStateException("缺少平台检测结果，请重新连接")
                val outcome = DeployService.deploy(ssh, detect, cdnUrl) { step -> deploySteps.add(step) }
                deployResult = "部署完成：zcode-server v${outcome.appVersion}（新装 ${outcome.installed.size} 个组件、跳过 ${outcome.skipped.size} 个）"
            } catch (e: Exception) {
                deployError = "部署失败：${e.message?.take(400)}"
            } finally {
                deploying = false
            }
        }
    }

    updateDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateDialog = null },
            confirmButton = { TextButton(onClick = { updateDialog = null }) { Text("知道了") } },
            title = { Text("检查远端更新") },
            text = { Text(msg) },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("工具") }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!connected) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("未连接 SSH", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "以下部分功能需要先建立连接",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onGoConnect) { Text("去连接") }
                    }
                }
            }

            // 1. 模型配置（不需要连接）
            ToolEntryCard(
                icon = Icons.Filled.Tune,
                title = "模型配置",
                subtitle = "管理模型供应商与 API Key",
                onClick = onModelConfig,
            )

            // 2. 套餐额度（不需要连接：HTTPS 直连业务域，不走 SSH）
            ToolEntryCard(
                icon = Icons.Filled.DataUsage,
                title = "套餐额度",
                subtitle = "查看 5 小时 / 每周 / 工具等窗口剩余量",
                onClick = onQuota,
            )

            // 3. 检查远端更新（需要连接）
            ToolEntryCard(
                icon = Icons.Filled.SystemUpdateAlt,
                title = "检查远端更新",
                subtitle = if (checkingUpdate) "正在检查…" else "对比 CDN 上的最新版本",
                enabled = connected,
                checking = checkingUpdate,
                needConnHint = !connected,
                onClick = { checkUpdate() },
            )

            // 4. 引擎版本（C7：纯展示，不做真实版本比对）
            // serverVersion 是普通 var：靠本页已有的 connState / checkingUpdate 状态变化触发重组刷新
            // （连接流程 SessionsScreen → versionCheck、本页检查更新、断开 closeAll 都会改变上述状态）
            val serverVersion = AppSession.serverVersion
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Memory, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("引擎版本", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when {
                                serverVersion != null -> "远端 zcode-server v$serverVersion"
                                connected -> "未检测到远端引擎版本，可用上方「检查远端更新」重新检测"
                                else -> "未连接，连接后自动检测"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (connected) {
                            Spacer(Modifier.height(2.dp))
                            // 静态阈值文案占位：远端自更新通道未实现前不做版本比对，
                            // 后续接 DeployService 的 latest.json 发现后替换为真实比对结果
                            Text(
                                "远端引擎过旧，部分新功能不可用；请到桌面端运行 zcode 更新远端",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // 5. 部署 / 重装 zcode-server（需要连接，展开式）
            Card(
                onClick = { deployExpanded = !deployExpanded },
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CloudDownload, contentDescription = null,
                            tint = if (connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("部署 / 重装 zcode-server", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "远端自行从 CDN 下载组件，手机不耗流量",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!connected) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "请先在 SSH 页建立连接",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (deployExpanded && connected) {
                        Spacer(Modifier.height(12.dp))
                        if (deploying || deploySteps.isNotEmpty()) {
                            if (deploying) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                            }
                            deploySteps.forEach { s ->
                                Text(
                                    "• $s", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (!deploying) {
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
                        deployResult?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        deployError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // 6. 远端日志（需要连接）
            ToolEntryCard(
                icon = Icons.Filled.Article,
                title = "远端日志",
                subtitle = "查看远端 zcode 的运行日志",
                enabled = connected,
                needConnHint = !connected,
                onClick = onLogs,
            )
        }
    }
}

/** 工具入口卡：图标 + 标题 + 副标题 + 尾部箭头；置灰时可选附一行「请先在 SSH 页建立连接」 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    checking: Boolean = false,
    needConnHint: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (needConnHint) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "请先在 SSH 页建立连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (checking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 同步 GET（须在 IO 线程调用）：连接/读取超时均 10s */
private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        conn.inputStream.readBytes().toString(Charsets.UTF_8)
    } finally {
        conn.disconnect()
    }
}

/** 防御式解析 latest.json：根级 "version"/"appVersion"，或嵌套 "app" 对象内同名字段；找不到返回 null */
private fun parseLatestVersion(json: String): String? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    root.optString("version").takeIf { it.isNotBlank() }?.let { return it }
    root.optString("appVersion").takeIf { it.isNotBlank() }?.let { return it }
    root.optJSONObject("app")?.let { app ->
        app.optString("version").takeIf { it.isNotBlank() }?.let { return it }
        app.optString("appVersion").takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}
