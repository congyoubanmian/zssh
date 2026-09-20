package com.zssh.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zssh.app.ssh.AgentSession
import com.zssh.app.ssh.AppSession
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 聊天页：恢复/新建会话 → 订阅事件流 → 思维链(默认折叠) + 流式回复 + 工具卡片 + 权限弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,          // null = 新会话
    dir: String? = null,         // 新会话的工作目录（列表页目录选择器传入，对应 CLI --cwd）
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sid by remember { mutableStateOf(sessionId) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    data class Bubble(val role: String, val text: String, val kind: String = "text") {
        val isTool: Boolean get() = kind == "tool"
        val isReasoning: Boolean get() = kind == "reasoning"
    }
    val bubbles = remember { mutableStateListOf<Bubble>() }
    var streaming by remember { mutableStateOf("") }        // 正在流式输出的答案
    var reasoning by remember { mutableStateOf("") }        // 正在流式输出的思维链
    var reasoningLive by remember { mutableStateOf(false) } // 思维链是否进行中（闪动效果）
    var running by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    // 发送队列：运行中的新输入排队，turn 结束自动提交（对齐桌面端行为）
    val sendQueue = remember { mutableStateListOf<String>() }
    // 连接已断开（引擎进程退出/SSH 断开），禁止继续发送
    var connectionLost by remember { mutableStateOf(false) }
    // 事件/权限收集器任务（重连后需重启以订阅新引擎的流）
    var collectorJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 权限请求队列：一轮可能连发多个，逐个弹窗处理
    val permissionQueue = remember { mutableStateListOf<JSONObject>() }
    // 模型选择器状态
    var availableModels by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var currentModelRef by remember { mutableStateOf<JSONObject?>(null) }
    var currentLevel by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    // 权限模式（session/setMode，对应 CLI --mode）与新会话工作目录（对应 CLI --cwd）
    var currentMode by remember { mutableStateOf("build") }
    var selectedDir by remember { mutableStateOf<String?>(dir) }    // 列表页选定的目录；null=用 homeDir
    var showModePicker by remember { mutableStateOf(false) }
    var showDirPicker by remember { mutableStateOf(false) }

    // ---- 发送 / 排队 / 插队（组合级 action，事件收集器和输入栏共用）----
    fun doSend(text: String) {
        val agent = AppSession.agentOrNull() ?: return
        val current = sid ?: return
        if (running) {
            // 引擎禁止并发发送（-32010），运行中的输入进队列，turn 结束自动提交
            sendQueue.add(text)
            return
        }
        bubbles.add(Bubble("user", text))
        running = true
        scope.launch {
            try { agent.sendMessage(current, text) } catch (e: Exception) { error = e.message?.take(200); running = false }
        }
    }

    /** turn 结束后按序提交队列中的下一条 */
    fun drainQueue() {
        val agent = AppSession.agentOrNull() ?: return
        val current = sid ?: return
        if (sendQueue.isEmpty() || running) return
        val next = sendQueue.removeAt(0)
        bubbles.add(Bubble("user", next))
        running = true
        scope.launch {
            try { agent.sendMessage(current, next) } catch (e: Exception) { error = e.message?.take(200); running = false }
        }
    }

    /** 停止当前轮：等引擎确认后再短兜底出队（stop 响应先于 turn.completed 事件到达） */
    fun requestStop() {
        scope.launch {
            val agent = AppSession.agentOrNull() ?: return@launch
            val current = sid ?: return@launch
            runCatching { agent.stopSession(current) }
            kotlinx.coroutines.delay(400)
            if (!running) drainQueue()
        }
    }

    /** 插队：移到队首；有活跃轮次则中断（完成后自动出队），没有则直接发送 */
    fun jumpQueue(text: String) {
        sendQueue.remove(text)
        sendQueue.add(0, text)
        if (running) {
            requestStop()
        } else {
            drainQueue()
        }
    }

    /** 处理一条引擎事件（事件收集器调用；所有 UI 状态变更集中在此） */
    fun handleEvent(ev: JSONObject) {
        try {
            val type = ev.optString("type")
            val payload = ev.optJSONObject("payload") ?: JSONObject()
            when (type) {
                "model.streaming" -> {
                    val kind = payload.optString("kind")
                    val delta = payload.optString("delta")
                    when {
                        kind == "text_delta" && delta.isNotEmpty() -> {
                            // 答案开始输出：把累积的思维链固化为一条折叠气泡
                            if (reasoning.isNotEmpty()) {
                                bubbles.add(Bubble("assistant", reasoning, kind = "reasoning"))
                                reasoning = ""
                            }
                            reasoningLive = false
                            streaming += delta
                        }
                        kind == "reasoning_delta" && delta.isNotEmpty() -> {
                            reasoningLive = true
                            reasoning += delta
                        }
                    }
                }
                "tool.updated" -> {
                    val kind = payload.optString("kind")
                    val name = payload.optString("toolName")
                    when (kind) {
                        "scheduled", "started" -> if (name.isNotBlank()) bubbles.add(Bubble("assistant", "🛠 $name …", kind = "tool"))
                        "result" -> bubbles.add(Bubble("assistant", "🛠 完成", kind = "tool"))
                    }
                }
                "turn.completed" -> {
                    val response = payload.optString("response")
                    if (reasoning.isNotEmpty()) {
                        bubbles.add(Bubble("assistant", reasoning, kind = "reasoning"))
                        reasoning = ""
                    }
                    if (response.isNotBlank()) bubbles.add(Bubble("assistant", response))
                    streaming = ""
                    reasoningLive = false
                    running = false
                    drainQueue()
                }
                "turn.failed" -> {
                    bubbles.add(Bubble("assistant", "❌ 本轮失败: " + payload.toString().take(200), kind = "tool"))
                    streaming = ""
                    reasoning = ""
                    reasoningLive = false
                    running = false
                }
                "_zssh/disconnected" -> {
                    // AgentSession 合成事件：引擎通道终止。必须复位全部运行状态，否则界面永久卡住
                    val reason = payload.optString("reason")
                    bubbles.add(Bubble("assistant", "⚠️ 连接断开：$reason\n请点击下方「重新连接」恢复", kind = "tool"))
                    streaming = ""
                    reasoning = ""
                    reasoningLive = false
                    running = false
                    connectionLost = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatScreen", "事件处理异常", e)
        }
        // 滚动放在独立协程里，绝不阻塞事件处理
        scope.launch { runCatching { listState.scrollToItem(bubbles.size.coerceAtLeast(0)) } }
    }

    /** 订阅指定引擎实例的事件与权限请求流（重连后对新实例重新调用） */
    fun startCollector(agent: AgentSession) {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            launch { agent.events.collect { handleEvent(it) } }
            launch { agent.permissionRequests.collect { permissionQueue.add(it) } }
        }
    }

    suspend fun openSession(agent: AgentSession) {
        // 重连/重进时先清空，历史统一由本次响应重建
        bubbles.clear()
        streaming = ""
        reasoning = ""
        reasoningLive = false
        val current = sid
        val resp = if (current != null) {
            agent.resumeSession(current)
        } else {
            val dir = selectedDir ?: AppSession.homeDir
            val r = agent.createSession(dir)
            sid = r.optJSONObject("session")?.optString("sessionId")
            r
        }
        currentMode = resp.optJSONObject("settings")?.optJSONObject("mode")?.optString("current")
            ?: resp.optJSONObject("projection")?.optString("mode") ?: "build"
        val wsPath = resp.optJSONObject("session")?.optJSONObject("workspace")?.optString("workspacePath") ?: ""
        if (wsPath.isNotBlank()) selectedDir = wsPath
        // 历史：resp.messages = [{info:{role}, parts:[...]}]
        val msgs = resp.optJSONArray("messages")
        if (msgs != null) {
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                val role = m.optJSONObject("info")?.optString("role") ?: continue
                val parts = m.optJSONArray("parts") ?: continue
                for (p in 0 until parts.length()) {
                    val part = parts.getJSONObject(p)
                    val text = part.optString("text", "")
                    val toolName = part.optString("tool", part.optString("toolName", ""))
                    val isReasoningPart = part.optString("type") == "reasoning" || part.has("reasoning")
                    when {
                        text.isNotBlank() && isReasoningPart -> bubbles.add(Bubble(role, text, kind = "reasoning"))
                        text.isNotBlank() -> bubbles.add(Bubble(role, text))
                        toolName.isNotBlank() -> bubbles.add(Bubble(role, "🛠 $toolName", kind = "tool"))
                    }
                }
            }
        }
        // 模型回退：会话无模型或原模型在远端不存在时，切到远端可用模型
        // 注意：resume 响应里 session.model 可能为 undefined；k3 等模型必须带 reasoningLevel
        val sessionModel = resp.optJSONObject("session")?.optJSONObject("model")
        val available = resp.optJSONObject("settings")?.optJSONObject("model")?.optJSONArray("available")
        val currentOk = sessionModel != null && available != null && (0 until available.length()).any { i ->
            val ref = available.getJSONObject(i).optJSONObject("ref") ?: return@any false
            ref.optString("providerId") == sessionModel.optString("providerId") &&
                ref.optString("modelId") == sessionModel.optString("modelId")
        }
        if (!currentOk && available != null && available.length() > 0) {
            val entry = available.getJSONObject(0)
            val ref = entry.getJSONObject("ref")
            val modelObj = JSONObject().put("providerId", ref.optString("providerId")).put("modelId", ref.optString("modelId"))
            val levels = entry.optJSONObject("reasoning")?.optJSONArray("levels")
            if (levels != null && levels.length() > 0) {
                val level = levels.getJSONObject(0).optString("value")
                if (level.isNotBlank()) modelObj.put("options", JSONObject().put("reasoningLevel", level))
            }
            agent.setModel(sid!!, modelObj)
            currentModelRef = JSONObject().put("providerId", ref.optString("providerId")).put("modelId", ref.optString("modelId"))
            currentLevel = modelObj.optJSONObject("options")?.optString("reasoningLevel")
            bubbles.add(Bubble("assistant", "ℹ️ 原会话模型在远端不可用，已切换到 ${ref.optString("modelId")}", kind = "tool"))
        }
        // 填充模型选择器数据
        if (available != null) {
            availableModels = (0 until available.length()).map { available.getJSONObject(it) }
        }
        if (currentModelRef == null) currentModelRef = sessionModel
        // 订阅从当前 eventSeq 之后开始，避免重放历史事件
        val seq = resp.optJSONObject("runtime")?.optLong("eventSeq", 0) ?: 0
        agent.subscribe(sid!!, seq)
    }

    /** 断线重连：丢弃死引擎 → 新引擎 → 重新 resume 当前会话 */
    fun reconnect() {
        scope.launch {
            loading = true
            error = null
            try {
                val agent = AppSession.reconnectAgent(AppSession.homeDir)
                startCollector(agent)
                openSession(agent)
                connectionLost = false
            } catch (e: Exception) {
                error = e.message?.take(300)
                connectionLost = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val agent = AppSession.agentOrNull()
        if (agent == null) {
            error = "引擎未启动"; loading = false
            return@LaunchedEffect
        }
        startCollector(agent)
        try {
            openSession(agent)
        } catch (e: Exception) {
            error = e.message?.take(300)
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (sid == null) "新会话" else "会话", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    if (sid != null) {
                        TextButton(onClick = { showModePicker = true }) {
                            Text(
                                when (currentMode) { "yolo" -> "🚀"; "plan" -> "🔍"; "edit" -> "✏️"; else -> "🔒" } + currentMode,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    if (availableModels.isNotEmpty()) {
                        TextButton(onClick = { showModelPicker = true }) {
                            Text(
                                "⚡${currentModelRef?.optString("modelId") ?: "默认"}" + (currentLevel?.let { "·$it" } ?: ""),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    // 新会话：创建前可改目录；已有会话：显示其工作目录（创建时固定）
                    if (sid == null) {
                        TextButton(onClick = { showDirPicker = true }) {
                            Text("📁" + (selectedDir ?: AppSession.homeDir).substringAfterLast('/').ifBlank { "/" },
                                style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    } else if (selectedDir != null) {
                        Text("📁" + selectedDir!!.substringAfterLast('/').ifBlank { "/" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(12.dp)) {
                if (connectionLost) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "⚠️ 连接已断开",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { reconnect() }, contentPadding = PaddingValues(4.dp)) {
                                Text("重新连接", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (sendQueue.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sendQueue.forEach { q ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp)) {
                                Row(Modifier.fillMaxWidth().padding(6.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("⏳ $q", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { jumpQueue(q) }, contentPadding = PaddingValues(4.dp)) { Text("⚡立即") }
                                    TextButton(onClick = { sendQueue.remove(q) }, contentPadding = PaddingValues(4.dp)) { Text("✕") }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        input, { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (connectionLost) "连接已断开" else if (running) "运行中，发送将排队…" else "输入消息…") },
                        maxLines = 4,
                        enabled = !connectionLost,
                    )
                    if (running) {
                        OutlinedButton(onClick = { requestStop() }) { Text("停止") }
                    }
                    Button(onClick = { doSend(input.trim()) }, enabled = input.isNotBlank() && !connectionLost) { Text("发送") }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(Modifier.padding(16.dp)) {
                    Text("出错了", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(bubbles) { b ->
                        when {
                            b.isReasoning -> ReasoningBubble(b.text, live = false)
                            b.isTool -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                                    Text(b.text, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            b.role == "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.88f)) {
                                    Text(b.text, Modifier.padding(10.dp))
                                }
                            }
                            else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.88f)) {
                                    Text(b.text, Modifier.padding(10.dp))
                                }
                            }
                        }
                    }
                    if (reasoning.isNotEmpty() || reasoningLive) {
                        item { ReasoningBubble(reasoning, live = true) }
                    }
                    if (streaming.isNotEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.88f)) {
                                    Text(streaming + " ▌", Modifier.padding(10.dp))
                                }
                            }
                        }
                    }
                    if (running && streaming.isEmpty() && reasoning.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                Text("处理中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (showModelPicker) {
                AlertDialog(
                    onDismissRequest = { showModelPicker = false },
                    title = { Text("选择模型与思考强度") },
                    text = {
                        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(availableModels, key = { it.optJSONObject("ref")?.optString("providerId")!! + "/" + it.optJSONObject("ref")?.optString("modelId")!! }) { entry ->
                                val ref = entry.getJSONObject("ref")
                                val isSelected = currentModelRef?.optString("providerId") == ref.optString("providerId") &&
                                    currentModelRef?.optString("modelId") == ref.optString("modelId")
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        (entry.optString("label").ifBlank { ref.optString("modelId") }) + " · " + entry.optString("providerLabel", ""),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    val levels = entry.optJSONObject("reasoning")?.optJSONArray("levels")
                                    if (levels != null && levels.length() > 0) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                            for (i in 0 until levels.length()) {
                                                val lv = levels.getJSONObject(i)
                                                FilterChip(
                                                    selected = isSelected && currentLevel == lv.optString("value"),
                                                    onClick = {
                                                        val model = JSONObject()
                                                            .put("providerId", ref.optString("providerId"))
                                                            .put("modelId", ref.optString("modelId"))
                                                            .put("options", JSONObject().put("reasoningLevel", lv.optString("value")))
                                                        scope.launch {
                                                            runCatching {
                                                                AppSession.agentOrNull()?.setModel(sid!!, model)
                                                                currentModelRef = JSONObject().put("providerId", ref.optString("providerId")).put("modelId", ref.optString("modelId"))
                                                                currentLevel = lv.optString("value")
                                                                showModelPicker = false
                                                            }.onFailure { error = it.message?.take(200) }
                                                        }
                                                    },
                                                    label = { Text(lv.optString("label").ifBlank { lv.optString("value") }) },
                                                )
                                            }
                                        }
                                    } else {
                                        TextButton(onClick = {
                                            val model = JSONObject().put("providerId", ref.optString("providerId")).put("modelId", ref.optString("modelId"))
                                            scope.launch {
                                                runCatching {
                                                    AppSession.agentOrNull()?.setModel(sid!!, model)
                                                    currentModelRef = model; currentLevel = null; showModelPicker = false
                                                }.onFailure { error = it.message?.take(200) }
                                            }
                                        }) { Text("使用该模型") }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showModelPicker = false }) { Text("关闭") } },
                )
            }

            if (showModePicker) {
                AlertDialog(
                    onDismissRequest = { showModePicker = false },
                    title = { Text("权限模式") },
                    text = {
                        Column {
                            listOf(
                                "build" to "构建：文件改动需确认（默认）",
                                "edit" to "编辑：常规编辑自动，高风险确认",
                                "plan" to "计划：只读分析，不改文件",
                                "yolo" to "全自动：不弹权限确认",
                            ).forEach { (m, desc) ->
                                TextButton(onClick = {
                                    val current = sid ?: return@TextButton
                                    scope.launch {
                                        runCatching {
                                            AppSession.agentOrNull()?.setMode(current, m)
                                            currentMode = m
                                            showModePicker = false
                                        }.onFailure { error = it.message?.take(150) }
                                    }
                                }) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text((if (m == currentMode) "● " else "○ ") + m, style = MaterialTheme.typography.titleSmall)
                                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showModePicker = false }) { Text("关闭") } },
                )
            }

            if (showDirPicker) {
                DirPickerDialog(
                    initial = selectedDir ?: AppSession.homeDir,
                    onConfirm = { selectedDir = it; showDirPicker = false },
                    onDismiss = { showDirPicker = false },
                )
            }

            permissionQueue.firstOrNull()?.let { p ->
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("权限请求 (${p.optString("riskLevel")})") },
                    text = {
                        Column {
                            val pi = p.optJSONObject("input")
                            Text(pi?.optString("description")?.ifBlank { pi.optString("command") } ?: "", maxLines = 6)
                            Spacer(Modifier.height(6.dp))
                            Text(pi?.optString("command") ?: "", maxLines = 4, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            AppSession.agentOrNull()?.respondPermission(p.optString("_serverId"), "allow", "用户允许")
                            permissionQueue.remove(p)
                        }) { Text("允许") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            AppSession.agentOrNull()?.respondPermission(p.optString("_serverId"), "deny", "用户拒绝")
                            permissionQueue.remove(p)
                        }) { Text("拒绝") }
                    },
                )
            }
        }
    }
}

/** 思维链气泡：默认折叠，点击展开；live 时标题闪动 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReasoningBubble(text: String, live: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(8.dp, 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (live) "🧠 思考中" else "🧠 已思考（点击展开）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(if (live) alpha else 1f),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
            } else if (live && text.isNotEmpty()) {
                // 折叠时也给一点实时预览（跑马灯式单行）
                Text(
                    text.takeLast(60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
            }
        }
    }
}
