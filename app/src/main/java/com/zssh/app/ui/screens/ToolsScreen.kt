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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 工具页（底部 Tab3）：模型配置 / 套餐额度 / 检查远端更新 / 引擎版本 / 部署·重装 zcode-server / 远端日志 / 闲时任务 */
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

    // ---- 闲时任务（offPeak：低峰时段自动执行的长任务，offPeak/create L3506-3554 / offPeak/list L3556-3560）----
    var offPeakExpanded by remember { mutableStateOf(false) }
    var offPeakCreating by remember { mutableStateOf(false) }
    var offPeakLoading by remember { mutableStateOf(false) }
    val offPeakTasks = remember { mutableStateListOf<JSONObject>() }   // 引擎侧快照：mutableStateListOf 保证列表变化触发重组
    var offPeakError by remember { mutableStateOf<String?>(null) }
    var offPeakMessage by remember { mutableStateOf<String?>(null) }
    var offPeakMode by remember { mutableStateOf("yolo") }   // 权限模式默认 yolo（闲时无人值守场景）
    var offPeakTitle by remember { mutableStateOf("") }
    var offPeakPrompt by remember { mutableStateOf("") }

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

    /** 拉取闲时任务列表（offPeak/list，params 恒空对象，index.ts L3556-3560）。
     *  引擎获取对齐 SessionsScreen 的 ensureAgent：工具页进入时引擎可能未启动，
     *  ensureAgent 幂等复用 slot.agent、必要时冷启动（waitForStartup 上限 15s，offPeakLoading 覆盖整段）；
     *  SSH 已断时 ensureAgent 抛 IllegalStateException("SSH 未连接")，走 catch 错误文案不崩 */
    fun loadOffPeak() {
        if (!connected || offPeakLoading) return
        offPeakLoading = true; offPeakError = null
        scope.launch {
            try {
                val agent = AppSession.ensureAgent(AppSession.homeDir)
                val arr = agent.listOffPeakTasks()
                offPeakTasks.clear()
                for (i in 0 until arr.length()) offPeakTasks.add(arr.getJSONObject(i))
            } catch (e: Exception) {
                offPeakError = "任务列表加载失败：${e.message?.take(300) ?: "未知"}"
            } finally {
                offPeakLoading = false
            }
        }
    }

    /** 创建闲时任务（offPeak/create，index.ts L3506-3554）。
     *  result 是 ok 判别联合（zcodeOffPeakCreateResultSchema L3535-3553）：
     *  ok=true → {task:{offPeakTaskId,title,status,queuePosition?,sessionId?,createdAt}}；
     *  ok=false → {failureStage,errorCategory,errorCode}，按 offPeakStageText/offPeakErrorText 中文映射 */
    fun createOffPeak() {
        if (offPeakTitle.isBlank() || offPeakPrompt.isBlank() || offPeakCreating) return
        offPeakCreating = true; offPeakError = null; offPeakMessage = null
        scope.launch {
            try {
                val agent = AppSession.ensureAgent(AppSession.homeDir)
                val res = agent.createOffPeakTask(offPeakTitle.trim(), offPeakPrompt.trim(), offPeakMode)
                if (res.optBoolean("ok", false)) {
                    val t = res.optJSONObject("task")
                    val pos = t?.optInt("queuePosition", 0) ?: 0
                    offPeakMessage = "已创建：${t?.optString("title").orEmpty()}" +
                        "（${offPeakStatusText(t?.optString("status"))}" +
                        (if (pos > 0) "，队列第 $pos 位" else "") + "）"
                    offPeakTitle = ""; offPeakPrompt = ""
                    loadOffPeak()   // 创建成功后自动串行拉取刷新列表
                } else {
                    offPeakError = "创建失败［${offPeakStageText(res.optString("failureStage"))}］：" +
                        offPeakErrorText(res.optString("errorCategory")) +
                        "（${res.optString("errorCode")}）"
                }
            } catch (e: Exception) {
                offPeakError = "创建失败：${e.message?.take(300) ?: "未知"}"
            } finally {
                offPeakCreating = false
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

            // 7. 闲时任务（需要连接，展开式：创建表单 + 任务列表）
            Card(
                onClick = {
                    offPeakExpanded = !offPeakExpanded
                    // 首次展开自动拉取一次；此后刷新一律手动「刷新」/创建后自动拉取
                    //（offPeak 域无专属会话事件，不做事件订阅）
                    if (offPeakExpanded && offPeakTasks.isEmpty()) loadOffPeak()
                },
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule, contentDescription = null,
                            tint = if (connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("闲时任务", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "低峰时段自动执行的长任务（offPeak）",
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
                    if (offPeakExpanded && connected) {
                        Spacer(Modifier.height(12.dp))
                        // ---- 创建表单（params 严格按 zcodeOffPeakCreateParamsSchema L3506-3516）----
                        OutlinedTextField(
                            offPeakTitle, { offPeakTitle = it },
                            label = { Text("标题") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            offPeakPrompt, { offPeakPrompt = it },
                            label = { Text("任务描述") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                        Spacer(Modifier.height(8.dp))
                        // 权限模式单选（值域 build/edit/plan/yolo，L3503-3504；文案对齐 ChatScreen 模式弹窗）
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "build" to "构建",
                                "edit" to "编辑",
                                "plan" to "计划",
                                "yolo" to "全自动",
                            ).forEach { (m, label) ->
                                FilterChip(
                                    selected = offPeakMode == m,
                                    onClick = { offPeakMode = m },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text(
                            offPeakModeDesc(offPeakMode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { createOffPeak() },
                            enabled = offPeakTitle.isNotBlank() && offPeakPrompt.isNotBlank() && !offPeakCreating,
                        ) {
                            Text("创建任务")
                            if (offPeakCreating) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                        // ---- 任务列表（offPeak/list 快照，本地只读展示）----
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "任务列表",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { loadOffPeak() }, enabled = !offPeakLoading) {
                                Text("刷新")
                            }
                        }
                        if (offPeakLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else if (offPeakTasks.isEmpty()) {
                            Text(
                                "暂无闲时任务",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            offPeakTasks.forEach { t ->
                                val pos = t.optInt("queuePosition", 0)
                                val created = t.optLong("createdAt", 0L)
                                Column {
                                    Text(t.optString("title"), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        offPeakStatusText(t.optString("status")) +
                                            (if (pos > 0) " · 队列第 $pos 位" else "") +
                                            (if (created > 0)
                                                " · " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                                    .format(Date(created))
                                            else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        offPeakMessage?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        offPeakError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
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

/** 闲时任务创建失败分类（errorCategory，zcodeOffPeakCreateResultSchema L3543-3550 七值枚举）→ 中文；
 *  eligibility_3101/quota_3103 是服务端业务门禁（套餐资格/额度不足），属正常反馈而非异常 */
private fun offPeakErrorText(cat: String): String = when (cat) {
    "client_validation" -> "参数问题"
    "eligibility_3101" -> "当前套餐不支持闲时任务"
    "quota_3103" -> "闲时配额不足"
    "network" -> "网络错误"
    "invalid_response" -> "服务端响应异常"
    "local_persist" -> "本地保存失败"
    else -> "创建失败"
}

/** 闲时任务创建失败阶段（failureStage，L3542：client_validation/ticket_request/local_persist）→ 中文；
 *  未知值原样保留，不吞协议信息 */
private fun offPeakStageText(stage: String): String = when (stage) {
    "client_validation" -> "本地校验"
    "ticket_request" -> "排队请求"
    "local_persist" -> "本地落盘"
    else -> stage
}

/** 闲时任务状态（zcodeOffPeakTaskSnapshotSchema L3522：六值枚举）→ 中文；null/空容错为「未知」 */
private fun offPeakStatusText(s: String?): String = when (s) {
    "queued" -> "排队中"
    "paused" -> "已暂停"
    "running" -> "运行中"
    "completed" -> "已完成"
    "failed" -> "已失败"
    "cancelled" -> "已取消"
    else -> "未知"
}

/** 闲时任务权限模式描述（值域 build/edit/plan/yolo，L3503-3504；文案对齐 ChatScreen 模式弹窗） */
private fun offPeakModeDesc(mode: String): String = when (mode) {
    "build" -> "构建：文件改动需确认"
    "edit" -> "编辑：常规编辑自动，高风险确认"
    "plan" -> "计划：只读分析，不改文件"
    "yolo" -> "全自动：不弹权限确认"
    else -> mode
}
