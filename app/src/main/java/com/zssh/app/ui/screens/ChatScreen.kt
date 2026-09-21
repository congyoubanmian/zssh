package com.zssh.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zssh.app.ssh.AgentSession
import com.zssh.app.ssh.AppSession
import com.zssh.app.ui.theme.AppSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 聊天页：恢复/新建会话 → 订阅事件流 → 思维链(默认折叠) + 流式回复 + 工具卡片 + 权限弹窗 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // 问答式输入队列（AskUserQuestion/ExitPlanMode 走 interaction/requestUserInput）：同样逐个弹窗应答
    val userInputQueue = remember { mutableStateListOf<JSONObject>() }
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
    // 上下文用量（A9）：create/resume 响应快照 projection.contextUsed/contextWindow；
    // 快照缺字段或窗口为 0 时保持 null，顶栏隐藏进度条（不报错）
    var contextUsed by remember { mutableStateOf<Long?>(null) }
    var contextWindow by remember { mutableStateOf<Long?>(null) }

    // ---- 发送 / 排队 / 插队（组合级 action，事件收集器和输入栏共用）----
    fun doSend(text: String) {
        val agent = AppSession.agentOrNull() ?: return
        val current = sid ?: return
        if (connectionLost) return
        if (running) {
            // 引擎禁止并发发送（-32010），运行中的输入进队列，turn 结束自动提交
            sendQueue.add(text)
            return
        }
        bubbles.add(Bubble("user", text))
        running = true
        input = ""   // 发送即清空输入框（含排队路径，见下方 jumpQueue）
        scope.launch {
            try { agent.sendMessage(current, text) } catch (e: Exception) { error = e.message?.take(200); running = false }
        }
    }

    /** turn 结束后按序提交队列中的下一条 */
    fun drainQueue() {
        val agent = AppSession.agentOrNull() ?: return
        val current = sid ?: return
        if (sendQueue.isEmpty() || running || connectionLost) return
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
                // A13 多端互踢：桌面端已处理的权限/问答弹窗，手机端按关联键自动消失。
                // 只移除本地队列条目、绝不向引擎补应答 —— 请求已收敛（resolveInteraction
                // 先到先得，晚到应答幂等 noop，见 zcodeV4HostCommand.ts），补答无意义；
                // 队列里找不到（本机没发过/已自己答掉）则 removeAll 自然空转，静默忽略
                "permission.resolved" -> {
                    // payload: {requestId?, toolCallId, decision?, reason?, ...}，schema 必带 toolCallId。
                    // requestId 全端一致，但按计划用 toolCallId 关联：队列条目即
                    // interaction/requestPermission 参数（zcodePermissionRequestParamsSchema），
                    // toolCallId 为必填字段，稳定可比
                    val toolCallId = payload.optString("toolCallId")
                    if (toolCallId.isNotBlank()) {
                        permissionQueue.removeAll { it.optString("toolCallId") == toolCallId }
                    }
                }
                "userInput.resolved" -> {
                    // payload: {requestId, value?, cancelled?}，schema 必带 requestId。
                    // 队列条目即 interaction/requestUserInput 参数，requestId 为必填字段
                    val requestId = payload.optString("requestId")
                    if (requestId.isNotBlank()) {
                        userInputQueue.removeAll { it.optString("requestId") == requestId }
                    }
                }
                "_zssh/disconnected" -> {
                    // AgentSession 合成事件：引擎通道终止。必须复位全部运行状态，否则界面永久卡住；
                    // 排队消息与待答权限/问答请求一并清空（引擎已死，应答无意义且点击会触发未捕获异常）
                    val reason = payload.optString("reason")
                    bubbles.add(Bubble("assistant", "⚠️ 连接断开：$reason\n请点击下方「重新连接」恢复", kind = "tool"))
                    streaming = ""
                    reasoning = ""
                    reasoningLive = false
                    running = false
                    sendQueue.clear()
                    permissionQueue.clear()
                    userInputQueue.clear()
                    connectionLost = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatScreen", "事件处理异常", e)
        }
        // 滚动放在独立协程里，绝不阻塞事件处理
        // 尾部条目数 = bubbles.size + 可选的 reasoning/streaming/处理中 三项（索引从 0 起，目标是最后一项）
        scope.launch {
            runCatching {
                val extras = (if (reasoning.isNotEmpty() || reasoningLive) 1 else 0) +
                    (if (streaming.isNotEmpty()) 1 else 0) +
                    (if (running && streaming.isEmpty() && reasoning.isEmpty()) 1 else 0)
                val lastIndex = bubbles.size + extras - 1
                if (lastIndex >= 0) listState.scrollToItem(lastIndex)
            }
        }
    }

    /** 订阅指定引擎实例的事件与权限/问答请求流（重连后对新实例重新调用） */
    fun startCollector(agent: AgentSession) {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            launch { agent.events.collect { handleEvent(it) } }
            launch { agent.permissionRequests.collect { permissionQueue.add(it) } }
            launch { agent.userInputRequests.collect { userInputQueue.add(it) } }
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
        // 上下文用量：snapshot 顶层 projection（非负整数）；缺失时 optLong 默认 -1 → 置 null 隐藏
        val proj = resp.optJSONObject("projection")
        contextUsed = proj?.optLong("contextUsed", -1L)?.takeIf { it >= 0 }
        contextWindow = proj?.optLong("contextWindow", -1L)?.takeIf { it > 0 }
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
            // 历史加载完滚到底部（handleEvent 的滚动只覆盖事件，打开已有会话时 bubbles 是整批填充的）
            if (bubbles.isNotEmpty()) {
                scope.launch { runCatching { listState.scrollToItem(bubbles.size - 1) } }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
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
            // 上下文用量进度条（A9）：LinearProgressIndicator 显示 used/window 比例 + 百分比文字；
            // 快照缺字段/窗口为 0 时整行隐藏（纯渲染，不触发任何协议请求）
            val used = contextUsed
            val window = contextWindow
            if (used != null && window != null && window > 0) {
                val frac = (used.toFloat() / window).coerceIn(0f, 1f)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.weight(1f).height(4.dp),
                        // 接近窗口上限（≥90%）转警示色，提示可 compact（ROADMAP#6）
                        color = if (frac >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    val tokens = if (window >= 1000) "${used / 1000}k/${window / 1000}k" else "$used/$window"
                    Text(
                        "上下文 ${(frac * 100).toInt()}%（$tokens）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
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
                else -> Column(Modifier.fillMaxSize()) {
                    // 错误以横幅呈现，聊天记录始终保留（整页替换会让历史不可见且无恢复入口）
                    error?.let {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            Text(it, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
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
                                        // FlowRow 自动换行：chip 过多/过宽时不再被压扁（截图里 max 被挤成竖排）
                                        androidx.compose.foundation.layout.FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                        ) {
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

            // 权限弹窗（B10）：内嵌真实工具预览（编辑 diff / 命令高亮 / JSON 摘要），
            // 按选中 option 的 kind/decision 应答；回写走 IO 线程（writeLine 是阻塞通道写）
            permissionQueue.firstOrNull()?.let { p ->
                PermissionDialog(
                    request = p,
                    onRespond = { resp ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { AppSession.agentOrNull()?.respondPermission(p.optString("_serverId"), resp) }
                                .onFailure { android.util.Log.e("ChatScreen", "应答权限失败", it) }
                        }
                        permissionQueue.remove(p)
                    },
                )
            }

            // 问答式输入弹窗（AskUserQuestion/ExitPlanMode）：提交 → accept+content，取消 → decline；
            // 回写走 IO 线程（writeLine 是阻塞通道写，与权限弹窗一致）
            userInputQueue.firstOrNull()?.let { req ->
                UserInputDialog(
                    request = req,
                    onSubmit = { content ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { AppSession.agentOrNull()?.respondUserInput(req.optString("_serverId"), "accept", content) }
                                .onFailure { android.util.Log.e("ChatScreen", "应答提问失败", it) }
                        }
                        userInputQueue.remove(req)
                    },
                    onCancel = {
                        scope.launch(Dispatchers.IO) {
                            runCatching { AppSession.agentOrNull()?.respondUserInput(req.optString("_serverId"), "decline", reason = "用户取消了回答") }
                                .onFailure { android.util.Log.e("ChatScreen", "应答提问失败", it) }
                        }
                        userInputQueue.remove(req)
                    },
                )
            }
        }
    }
}

// ---- 权限弹窗（B10：内嵌真实工具预览）----
// 协议事实（zcode 仓库 apps/zcode-cli permission-options.ts / interaction-broker.ts）：
//  - params：{toolName, input, reason, riskLevel, options:[{optionId, kind, name, description?, response}]}
//  - legacy 选项集只有 allow_once / allow_project / deny（allowSession 只在 v4 投放）
//  - 每个选项自带完整 response（allow_project 的 permissionUpdates 持久化规则在 response 里）
//  - 应答须符合 zcodePermissionResponseSchema(strict)：{decision, reason?, modifiedInput?, permissionUpdates?}

/** 权限选项（排序后的渲染单元）；rank 允许一次(0)→始终允许(1)→拒绝一次(2)→始终拒绝(3)→未知(4) */
private data class PermOption(
    val optionId: String,
    val kind: String,
    val rank: Int,
    val json: JSONObject,      // 引擎原样选项（response 在里面）
)

/** 选项分类排序：允许一次 → 始终允许 → 拒绝一次 → 始终拒绝；未知 kind 垫底（显示引擎原名） */
private fun permissionOptionRank(optionId: String, kind: String): Int {
    val id = optionId.lowercase()
    val k = kind.lowercase()
    val deny = k.contains("deny") || id.contains("deny") || id.startsWith("reject")
    val allow = k.contains("allow") || id.contains("allow")
    val persist = k.contains("always") || k.contains("project") || id.contains("always") || id.contains("project")
    return when {
        allow && !deny && !persist -> 0   // allow_once
        allow && !deny -> 1               // allow_project（始终允许）
        deny && !persist -> 2             // deny（拒绝一次）
        deny -> 3                         // 始终拒绝（deny_project，当前协议未投放，留位）
        else -> 4                         // 未知 kind（如 v4 专属选项，不进 legacy wire）
    }
}

/** 按钮文案：rank 决定中文标签；会话作用域的始终允许单独说明 */
private fun permissionOptionLabel(o: PermOption): String = when (o.rank) {
    0 -> "允许一次"
    1 -> if (o.kind.contains("session", true) || o.optionId.contains("session", true)) "本会话不再询问" else "始终允许"
    2 -> "拒绝"
    3 -> "始终拒绝"
    else -> o.json.optString("name").ifBlank { o.optionId }
}

/** 解析并排序 options（sortedBy 稳定，同 rank 保持引擎顺序）；引擎正常必带（schema min 1），
 *  缺失时兜底最简 允许/拒绝 两项（对齐 CLI buildProtocolPermissionOptions 的形状） */
private fun parsePermissionOptions(request: JSONObject): List<PermOption> {
    val arr = request.optJSONArray("options") ?: JSONArray()
    val parsed = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
        val id = o.optString("optionId")
        val kind = o.optString("kind")
        PermOption(id, kind, permissionOptionRank(id, kind), o)
    }
    if (parsed.isNotEmpty()) return parsed.sortedBy { it.rank }
    return listOf(
        PermOption("allow_once", "allow_once", 0,
            JSONObject().put("optionId", "allow_once").put("kind", "allow_once")
                .put("response", JSONObject().put("decision", "allow").put("reason", "Approved once"))),
        PermOption("deny", "deny", 2,
            JSONObject().put("optionId", "deny").put("kind", "deny")
                .put("response", JSONObject().put("decision", "deny"))),
    )
}

/** 用户拒绝的固定文案：对齐 CLI permission-options.ts —— 必须明确告知模型工具未执行并停下等指示 */
private const val PERMISSION_DENIED_BY_USER_CONTENT =
    "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user to tell you how to proceed."

/** 拒绝理由合成（对齐 buildPermissionDeniedContent）：空反馈 → 标准文案；有反馈 → 标准文案 + 用户原话 */
private fun permissionDeniedContent(feedback: String): String {
    val t = feedback.trim()
    return if (t.isEmpty()) PERMISSION_DENIED_BY_USER_CONTENT
    else PERMISSION_DENIED_BY_USER_CONTENT + " To tell you how to proceed, the user said:\n$t"
}

/** 组装应答（zcodePermissionResponseSchema strict，字段只能少不能多）。
 *  以选中 option 自带的 response 为底（引擎预组装，allow_project 的 permissionUpdates 含
 *  ruleContent），再按 kind 修正 decision/reason；始终允许缺 permissionUpdates 时按 CLI
 *  defaultPermissionUpdates 合成。会话作用域（allowSession）wire 上语义由引擎合成，不带规则 */
private fun buildPermissionResponse(option: PermOption, toolName: String, input: JSONObject?, feedback: String): JSONObject {
    val deny = option.kind.lowercase().contains("deny") || option.optionId.lowercase().contains("deny")
    val isSessionScope = option.kind.contains("session", true) || option.optionId.contains("session", true)
    val persist = !deny && option.rank == 1 && !isSessionScope
    val base = option.json.optJSONObject("response")
    val decision = if (deny) "deny" else base?.optString("decision")?.takeIf { it.isNotBlank() } ?: "allow"
    val resp = JSONObject().put("decision", decision)
    resp.put("reason", if (deny) permissionDeniedContent(feedback)
        else base?.optString("reason")?.takeIf { it.isNotBlank() } ?: "Approved by user")
    if (persist) {
        val updates = base?.optJSONArray("permissionUpdates")
        if (updates != null && updates.length() > 0) resp.put("permissionUpdates", updates)
        else resp.put("permissionUpdates", defaultPermissionUpdates(toolName, input))
    }
    return resp
}

/** 默认持久化规则（对齐 CLI permission-options.ts defaultPermissionUpdates）：
 *  addRules + allow + 按工具整体；ruleContent 优先取命令/文件等关键入参，使规则只匹配本次同类操作 */
private fun defaultPermissionUpdates(toolName: String, input: JSONObject?): JSONArray {
    val rule = JSONObject().put("toolName", toolName)
    listOf("command", "url", "file_path", "path", "pattern")
        .firstNotNullOfOrNull { k -> input?.optString(k)?.takeIf { it.isNotBlank() } }
        ?.let { rule.put("ruleContent", it) }
    return JSONArray().put(
        JSONObject().put("type", "addRules").put("behavior", "allow").put("rules", JSONArray().put(rule))
    )
}

/** 权限请求弹窗：内嵌真实工具预览（编辑 diff / 命令高亮 / JSON 摘要）+
 *  按 rank 排序的决策按钮（允许一次→始终允许→拒绝一次→始终拒绝），拒绝可附文字反馈 */
@Composable
private fun PermissionDialog(
    request: JSONObject,
    onRespond: (JSONObject) -> Unit,
) {
    val toolName = request.optString("toolName").ifBlank { "工具" }
    val input = request.optJSONObject("input")
    val risk = request.optString("riskLevel").ifBlank { "medium" }
    val reason = request.optString("reason")
    val options = remember(request) { parsePermissionOptions(request) }
    val hasDeny = options.any { it.rank == 2 || it.rank == 3 }
    var feedback by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {},   // 引擎在等应答，必须显式决策；点弹窗外不关闭
        title = {
            Column {
                Text("权限请求 · $toolName", style = MaterialTheme.typography.titleLarge)
                Text(
                    "风险: $risk", style = MaterialTheme.typography.labelMedium,
                    color = when (risk) {
                        "critical", "high" -> MaterialTheme.colorScheme.error
                        "medium" -> AppSemantic.warning()
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            ) {
                if (reason.isNotBlank()) {
                    Text(reason, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    Spacer(Modifier.height(8.dp))
                }
                PermissionPreview(toolName, input)
                if (hasDeny) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        feedback, { feedback = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("拒绝理由（可空，将随拒绝反馈给模型）") },
                        minLines = 1, maxLines = 3,
                    )
                }
                Spacer(Modifier.height(12.dp))
                options.forEach { o ->
                    val label = permissionOptionLabel(o)
                    val onClick = { onRespond(buildPermissionResponse(o, toolName, input, feedback)) }
                    when (o.rank) {
                        0 -> Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        1 -> FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        else -> OutlinedButton(
                            onClick = onClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (o.rank == 2 || o.rank == 3)
                                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            else ButtonDefaults.outlinedButtonColors(),
                        ) { Text(label) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {},   // 决策按钮内嵌在 text 区（最多 4 个，标准操作栏排不下）
        dismissButton = {},
    )
}

/** 预览分派：编辑类（toolName 含 Edit/Write 且带 file_path）→ 简化 diff；
 *  命令类（入参带 command，Bash 等）→ 等宽全文；其余 → 入参 JSON 摘要 */
@Composable
private fun PermissionPreview(toolName: String, input: JSONObject?) {
    val command = input?.optString("command")?.takeIf { it.isNotBlank() }
    val looksEdit = toolName.contains("Edit") || toolName.contains("Write")
    when {
        // 条件直接写在分支里（不走布尔局部变量），保证 input 的智能转换成立
        input != null && looksEdit && input.optString("file_path").isNotBlank() &&
            (input.has("old_string") || input.has("new_string") || input.has("content")) ->
            EditDiffPreview(input)
        command != null -> CommandPreview(command, input?.optString("description")?.takeIf { it.isNotBlank() })
        else -> JsonSummaryPreview(input)
    }
}

private const val MAX_DIFF_LINES = 24        // diff 单侧最多展示行数
private const val MAX_DIFF_LINE_CHARS = 200  // diff 单行截断长度

/** 编辑类预览：简化 diff——旧文红底删行（−）、新文绿底增行（+）；Write 无旧文时只渲染增行；超长截断 */
@Composable
private fun EditDiffPreview(input: JSONObject) {
    val filePath = input.optString("file_path")
    val oldLines = input.optString("old_string").takeIf { it.isNotBlank() }?.lines() ?: emptyList()
    val newLines = input.optString("new_string").ifBlank { input.optString("content") }
        .takeIf { it.isNotBlank() }?.lines() ?: emptyList()
    Column(Modifier.fillMaxWidth()) {
        Text("📄 " + filePath.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
        Text(filePath, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                oldLines.take(MAX_DIFF_LINES).forEach { DiffLine("−", it, delete = true) }
                if (oldLines.size > MAX_DIFF_LINES) DiffTruncated("旧文", oldLines.size)
                newLines.take(MAX_DIFF_LINES).forEach { DiffLine("+", it, delete = false) }
                if (newLines.size > MAX_DIFF_LINES) DiffTruncated("新文", newLines.size)
            }
        }
    }
}

/** diff 单行：删行红底 / 增行绿底，等宽字体，单行超长截断 */
@Composable
private fun DiffLine(sign: String, text: String, delete: Boolean) {
    Text(
        sign + " " + text.take(MAX_DIFF_LINE_CHARS) + if (text.length > MAX_DIFF_LINE_CHARS) " …" else "",
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(if (delete) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f) else AppSemantic.diffAddBg())
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (delete) MaterialTheme.colorScheme.onErrorContainer else AppSemantic.diffAddFg(),
    )
}

/** diff 截断提示行 */
@Composable
private fun DiffTruncated(which: String, total: Int) {
    Text(
        "… $which 共 $total 行，仅显示前 $MAX_DIFF_LINES 行",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val MAX_COMMAND_CHARS = 4000   // 命令全文展示上限

/** 命令类预览（Bash 等）：命令全文等宽突出显示；模型给的 description 一并展示 */
@Composable
private fun CommandPreview(command: String, description: String?) {
    Column(Modifier.fillMaxWidth()) {
        Text("命令", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                command.take(MAX_COMMAND_CHARS) +
                    if (command.length > MAX_COMMAND_CHARS) "\n…（共 ${command.length} 字符，已截断）" else "",
                Modifier.fillMaxWidth().padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
        }
    }
}

private const val MAX_JSON_CHARS = 1200      // JSON 摘要截断长度

/** 其余工具预览：入参 JSON 摘要（格式化 + 截断，等宽小字，自身可滚动） */
@Composable
private fun JsonSummaryPreview(input: JSONObject?) {
    if (input == null || input.length() == 0) {
        Text("（无入参）", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val s = runCatching { input.toString(2) }.getOrDefault(input.toString())
    Column(Modifier.fillMaxWidth()) {
        Text("工具入参", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                s.take(MAX_JSON_CHARS) + if (s.length > MAX_JSON_CHARS) "\n…（共 ${s.length} 字符，已截断）" else "",
                Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** 问答式输入弹窗（interaction/requestUserInput；AskUserQuestion 与 ExitPlanMode 共用）。
 *  只渲染单问题：questions[0]，缺失时回退 prompt 自由文本作答（D2 范围，多问题分页后续做）。
 *  options 单选列表 / multiSelect=true 多选 chip；应答 content 与桌面端 ElicitationDialog 同构：
 *  {answers:{问题:答案拼接}, answer_0, answer(单题兼容)}，空答案不提交（可选澄清，非必填表单） */
@Composable
private fun UserInputDialog(
    request: JSONObject,
    onSubmit: (JSONObject?) -> Unit,
    onCancel: () -> Unit,
) {
    val question = request.optJSONArray("questions")?.optJSONObject(0)
    val isPlanApproval = request.optString("toolName") == "ExitPlanMode"
    val questionText = question?.optString("question")?.takeIf { it.isNotBlank() }
        ?: request.optString("prompt").takeIf { it.isNotBlank() } ?: "请补充信息"
    // 展示正文：ExitPlanMode 的 questions[0].question 是服务端固定文案
    // "Review this implementation plan."（interaction-broker.ts 的常量），真正的计划
    // 在 params.input.plan（ExitPlanModeInputSchema.plan，string），缺失再回退 questionText。
    // 仅用于展示 —— buildContent 的 answers 键仍须用 questionText（问题原文作键，协议约定）
    val bodyText = (if (isPlanApproval) request.optJSONObject("input")?.optString("plan")?.takeIf { it.isNotBlank() } else null)
        ?: questionText
    val header = question?.optString("header")?.takeIf { it.isNotBlank() }
    val options = question?.optJSONArray("options")
    val optionCount = options?.length() ?: 0
    val multiSelect = question?.optBoolean("multiSelect", false) == true

    // 选中值（按点击顺序保留）；单选时 toggle 即切换。
    // remember 以 request 为 key：userInputQueue 推进到下一条请求（本端应答/
    // userInput.resolved 互踢/断线清队）时复位选中与自定义文本，避免沿用上一条的
    // 作答状态 —— 与 PermissionDialog 的 remember(request) 写法对齐
    val selected = remember(request) { mutableStateListOf<String>() }
    var customAnswer by remember(request) { mutableStateOf("") }

    fun toggle(value: String) {
        if (multiSelect) {
            if (!selected.remove(value)) selected.add(value)
        } else {
            selected.clear()
            selected.add(value)
        }
    }

    // 最终答案 = 选中项 + 自定义补充（对齐桌面端 getQuestionAnswers 的拼接顺序）
    fun currentAnswers(): List<String> =
        selected.toList() + listOfNotNull(customAnswer.trim().takeIf { it.isNotBlank() })

    /** accept 的 content：answers map + answer_0 + 单题 answer（多选为数组，单选取首个） */
    fun buildContent(answers: List<String>): JSONObject {
        val content = JSONObject()
        if (answers.isNotEmpty()) {
            val value: Any = if (multiSelect) JSONArray(answers) else answers[0]
            content.put("answers", JSONObject().put(questionText, answers.joinToString(", ")))
            content.put("answer_0", value)
            content.put("answer", value)
        }
        return content
    }

    AlertDialog(
        onDismissRequest = {},   // 必答反向请求：不允许点外部关闭（与权限弹窗一致）
        title = { Text(if (isPlanApproval) "📋 计划待确认" else (header ?: "需要你的回答")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                // 计划/问题正文：ExitPlanMode 的 plan 可能很长，可滚动 + 截断保护
                Text(bodyText.take(4000), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                if (optionCount > 0) {
                    if (multiSelect) {
                        // 多选：chip 逐行排（描述放 chip 下方），FlowRow 在当前 BOM 仍是实验 API 故不用
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in 0 until optionCount) {
                                val opt = options!!.getJSONObject(i)
                                val value = opt.optString("value")
                                Column {
                                    FilterChip(
                                        selected = selected.contains(value),
                                        onClick = { toggle(value) },
                                        label = { Text(opt.optString("label").ifBlank { value }) },
                                    )
                                    opt.optString("description").takeIf { it.isNotBlank() }?.let {
                                        Text(it, Modifier.padding(start = 12.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    } else {
                        // 单选：列表行（与权限模式选择器同款 ●/○ 样式）
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in 0 until optionCount) {
                                val opt = options!!.getJSONObject(i)
                                val value = opt.optString("value")
                                TextButton(onClick = { toggle(value); customAnswer = "" }) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text((if (selected.contains(value)) "● " else "○ ") + opt.optString("label").ifBlank { value },
                                            style = MaterialTheme.typography.titleSmall)
                                        opt.optString("description").takeIf { it.isNotBlank() }?.let {
                                            Text(it.take(500), style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // 选中项的 preview（如有）：折叠展示，点开可看全文
                    options!!.let { opts ->
                        (0 until optionCount).map { opts.getJSONObject(it) }
                            .firstOrNull { selected.contains(it.optString("value")) }
                            ?.optString("preview")?.takeIf { it.isNotBlank() }?.let { preview ->
                                var expanded by remember { mutableStateOf(false) }
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                        Text(
                                            if (expanded) preview.take(4000) else preview.take(120),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (preview.length > 120) {
                                            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                                                Text(if (expanded) "收起" else "展开全部")
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
                // 无选项（纯 prompt）必给自由输入；有选项时作为"其他"补充
                OutlinedTextField(
                    customAnswer, { customAnswer = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (optionCount > 0) "其他（自定义回答）…" else "输入你的回答…") },
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(buildContent(currentAnswers())) },
                enabled = currentAnswers().isNotEmpty(),
            ) { Text(if (isPlanApproval) "确认计划" else "提交") }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) { Text(if (isPlanApproval) "拒绝" else "取消") }
        },
    )
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
