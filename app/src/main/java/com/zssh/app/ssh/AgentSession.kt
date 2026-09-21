package com.zssh.app.ssh

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZCode Agent 引擎会话（JSON-RPC over SSH exec channel）。
 * Kotlin 版的 m0/poc_ssh.mjs —— 协议细节最初来自 M0 实测，现已对照开源仓库校准：
 * 权威定义在 zcode 仓库 packages/shared/src/zcode-protocol/index.ts（zod schema 全集，均 strict）。
 *  - startup/storageState 按 phase 门禁：ready 才算握手完成，failed 立即失败
 *  - 必答 session/requestRuntimePreferences（15s 超时；仅 nativeSearchEnhancementsEnabled 必填）
 *  - interaction/requestPermission 需答 {decision, reason?, permissionUpdates?}（始终允许需带 updates）；不支持的反向请求回 -32601
 *  - interaction/requestUserInput 需答 {action:"accept"|"decline"|"cancel", content?, reason?}
 *    （AskUserQuestion/ExitPlanMode 的问题经它下发，accept 的 content 为用户答案）
 *  - session/create {workspace:{workspacePath,workspaceKey}} / session/resume {sessionId}
 *  - session/subscribe {sessionId, deliveryKind:"desktop-continuous"} 后才有 session/event
 *  - session/send {sessionId, content}（异步 ACK，turn 结果走事件）
 */
class AgentSession(
    private val ssh: SSHClient,
    private val homeDir: String,
    private val workspacePath: String,
) {
    /** UI 消费的事件流（session/event 通知的 params）。
     *  SUSPEND + trySendBlocking：缓冲满时读线程阻塞等待（背压），绝不丢事件 ——
     *  丢掉 turn.completed 会让界面永久卡在运行状态 */
    val events = kotlinx.coroutines.flow.MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** 服务端发来的权限请求，等待 UI 决策 */
    val permissionRequests = kotlinx.coroutines.flow.MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** 服务端发来的问答式输入请求（AskUserQuestion/ExitPlanMode 都走 interaction/requestUserInput），等待 UI 应答 */
    val userInputRequests = kotlinx.coroutines.flow.MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val serverPending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(1)
    private var session: net.schmizz.sshj.connection.channel.direct.Session? = null
    private val enginePath by lazy { "$homeDir/.zcode/server/agents/glm/zcode.cjs" }

    suspend fun start() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // 远端路径一律单引号包裹：目录含空格时命令不会被拆断，元字符也不构成注入
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val command = buildString {
            append("cd ${q(workspacePath)} && ")
            // ZCODE_HOME 指 .zcode 目录本身（默认 ~/.zcode，见仓库 services/node.ts），
            // 与 ZCODE_DATA_BASE_DIR（替换 $HOME 前缀）语义不同，不能都设成 homeDir
            append("ZCODE_HOME=${q("$homeDir/.zcode")} ZCODE_DATA_BASE_DIR=${q(homeDir)} ")
            // BUILTIN 与 PERSONAL 必须成对提供（provider-node resolveNodeProviderRuntimePaths 硬性要求）
            append("ZCODE_BUILTIN_PROVIDER_CONFIG_FILE=${q("$homeDir/.zcode/v2/runtime/provider/bundled/zcode-builtin.json")} ")
            append("ZCODE_PERSONAL_PROVIDER_CONFIG_FILE=${q("$homeDir/.zcode/v2/provider_config.json")} ")
            append("${q("$homeDir/.zcode/server/node")} ${q(enginePath)} app-server --stdio")
        }
        Log.i(TAG, "启动引擎: $command")
        val s = ssh.startSession()
        session = s
        try {
            val cmd = s.exec(command)
            val reader = Thread {
                runBlocking {
                    try {
                        val reader = BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) handleLine(line!!)
                        // EOF：引擎退出或 SSH 断开。必须通知 UI，否则界面永久卡在运行状态
                        onEngineClosed(cmd.exitErrorMessage ?: "引擎退出 (exit=${cmd.exitStatus})")
                    } catch (e: Exception) {
                        onEngineClosed("连接断开: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            // 引擎启动握手：等第一条通知到达（startup/storageState）
            waitForStartup()
        } catch (e: Exception) {
            // 启动失败必须清理半开的通道，否则 SSH 通道与读线程泄漏
            runCatching { s.close() }
            onEngineClosed("引擎启动失败: ${e.message}")
            throw e
        }
        Unit
    }

    @Volatile private var started = false
    /** 存储初始化失败原因（startup/storageState phase=failed 时记录，供 waitForStartup 抛出） */
    @Volatile private var startupFailure: String? = null
    private val closed = AtomicBoolean(false)

    private suspend fun waitForStartup() {
        // 等存储启动走完（startup/storageState 的 phase 变为 ready）；失败会置 closed，循环随即退出
        val deadline = System.currentTimeMillis() + 15_000
        while (!started && !closed.get() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
        if (!started) throw IllegalStateException(
            startupFailure
                ?: if (closed.get()) "引擎启动失败（通道已关闭，请检查远端是否已部署 zcode-server）"
                else "引擎启动超时（无首包）"
        )
    }

    /** 引擎通道终止（进程退出/SSH 断开）：失败化所有挂起请求，并向 UI 发合成事件 */
    private suspend fun onEngineClosed(reason: String) {
        if (closed.getAndSet(true)) return
        Log.w(TAG, "引擎通道关闭: $reason")
        pending.values.forEach { it.completeExceptionally(IllegalStateException("引擎连接断开: $reason")) }
        pending.clear()
        runCatching {
            events.emit(
                JSONObject().put("type", "_zssh/disconnected")
                    .put("payload", JSONObject().put("reason", reason))
            )
        }
    }

    private suspend fun handleLine(line: String) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return
        val msg = try { JSONObject(trimmed) } catch (e: org.json.JSONException) { return }
        val hasId = msg.has("id") && !msg.isNull("id")
        when {
            hasId && (msg.has("result") || msg.has("error")) -> {
                val id = msg.getInt("id")
                pending.remove(id)?.let { d ->
                    if (msg.has("error")) {
                        // 业务码在 error.code，附属码在 error.data.code（见 zcodeProtocolErrorCodes）
                        val err = msg.getJSONObject("error")
                        val dataCode = err.optJSONObject("data")?.optString("code")?.takeIf { it.isNotBlank() }
                        d.completeExceptionally(IllegalStateException(
                            "[${err.optInt("code", 0)}] ${err.optString("message").ifBlank { "引擎返回错误" }}"
                                + (dataCode?.let { " ($it)" } ?: "")
                        ))
                    } else d.complete(msg.optJSONObject("result") ?: JSONObject())
                }
            }
            msg.has("method") -> {
                val method = msg.getString("method")
                val params = msg.optJSONObject("params") ?: JSONObject()
                if (method == "startup/storageState") {
                    // 存储启动进度（schema 取值 checking/waiting_for_lock/migrating/committing/ready/failed）
                    when (params.optString("phase")) {
                        "ready" -> started = true
                        "failed" -> {
                            startupFailure = "存储初始化失败: ${params.optString("errorCode").ifBlank { "unknown" }}"
                            onEngineClosed(startupFailure!!)
                        }
                    }
                }
                // 服务端反向请求必须应答；应答体必须符合 strict schema，空对象会被 -32602 拒
                if (hasId) {
                    val serverId = msg.getString("id")
                    when (method) {
                        "session/requestRuntimePreferences" ->
                            respond(serverId, JSONObject().put("nativeSearchEnhancementsEnabled", false))
                        "interaction/requestPermission" -> {
                            val toolName = params.optString("toolName")
                            if (toolName == "AskUserQuestion" || toolName == "ExitPlanMode") {
                                // 桌面端同款抑制（zcodeTaskServiceAdapter isUserInputBackedPermissionToolName）：
                                // 这两个工具的权限请求只是 core 的等待态标记，弹 Allow/Deny 无法把答案写回
                                // 工具 input；真正要答的问题经 interaction/requestUserInput 到达，这里直接放行
                                respond(serverId, JSONObject().put("decision", "allow")
                                    .put("reason", "问答式工具由 requestUserInput 应答"))
                            } else {
                                serverPending[serverId] = CompletableDeferred()
                                permissionRequests.emit(params.put("_serverId", serverId))
                            }
                        }
                        // 问答式输入（AskUserQuestion / ExitPlanMode 都走它）：进队列由 UI 弹窗应答，
                        // 应答体 strict schema {action, content?, reason?}（见 zcodeUserInputResponseSchema）
                        "interaction/requestUserInput" -> {
                            serverPending[serverId] = CompletableDeferred()
                            userInputRequests.emit(params.put("_serverId", serverId))
                        }
                        // 判别联合应答：headersApplied=false + errorMessage
                        "interaction/requestProviderRuntimeHeaders" ->
                            respond(serverId, JSONObject().put("headersApplied", false)
                                .put("errorMessage", "Android 客户端无法提供供应商运行时请求头"))
                        "interaction/requestOfficialMcpAuthHeaders" ->
                            respond(serverId, JSONObject().put("ok", false)
                                .put("reason", "Android 客户端不支持 MCP 鉴权"))
                        // 官方降级信号：宿主不支持的方法回 -32601，引擎按默认逻辑继续
                        else -> respondError(serverId, -32601, "Android 客户端不支持该方法: $method")
                    }
                }
                if (method == "session/event") events.emit(params)
            }
        }
    }

    /** UI 对权限请求的决策回包。response 必须符合 zcodePermissionResponseSchema(strict，
     *  见 zcode 仓库 zcode-protocol-legacy-types.ts)：{decision: allow|deny|escalate|modify,
     *  reason?, modifiedInput?, permissionUpdates?}，多一个字段都会被引擎 -32602 拒掉；
     *  始终允许类应答（allow_project）需带 permissionUpdates（addRules 持久化规则） */
    fun respondPermission(serverId: String, response: JSONObject) {
        respond(serverId, response)
        serverPending.remove(serverId)?.complete(JSONObject())
    }

    /** UI 对问答式输入的应答回包：accept 时 content 为用户答案（桌面端 ElicitationDialog 同构）；
     *  decline/cancel 只附 reason。应答体是 strict schema，content/reason 缺省时不能塞空值 */
    fun respondUserInput(serverId: String, action: String, content: JSONObject? = null, reason: String? = null) {
        val result = JSONObject().put("action", action)
        if (content != null) result.put("content", content)
        if (!reason.isNullOrBlank()) result.put("reason", reason)
        respond(serverId, result)
        serverPending.remove(serverId)?.complete(JSONObject())
    }

    private fun respond(id: String, result: JSONObject) {
        writeLine(JSONObject().put("id", id).put("result", result).toString())
    }

    /** 对反向请求回 JSON-RPC 错误（-32601 是官方约定的"宿主不支持"降级信号） */
    private fun respondError(id: String, code: Int, message: String) {
        writeLine(JSONObject().put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message)).toString())
    }

    suspend fun request(method: String, params: JSONObject, timeoutMs: Long = 60_000): JSONObject =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val id = nextId.incrementAndGet()
            val d = CompletableDeferred<JSONObject>()
            pending[id] = d
            try {
                writeLine(JSONObject().put("id", id).put("method", method).put("params", params).toString())
                kotlinx.coroutines.withTimeout(timeoutMs) { d.await() }
            } finally {
                pending.remove(id)
            }
        }

    private fun writeLine(s: String) {
        check(!closed.get()) { "引擎通道已关闭，请返回重新连接" }
        val out = session?.let { it.getOutputStream() } ?: throw IllegalStateException("引擎通道未打开")
        synchronized(out) { out.write((s + "\n").toByteArray(Charsets.UTF_8)); out.flush() }
    }

    // ---- 高层 API ----
    suspend fun listSessions(): JSONArray {
        val r = request("session/list", JSONObject())
        return r.optJSONArray("sessions") ?: JSONArray()
    }

    suspend fun createSession(workspacePath: String): JSONObject =
        request("session/create", JSONObject().put("workspace", JSONObject().put("workspacePath", workspacePath).put("workspaceKey", "android:$workspacePath")), 120_000)

    suspend fun resumeSession(sessionId: String): JSONObject =
        request("session/resume", JSONObject().put("sessionId", sessionId), 120_000)

    suspend fun subscribe(sessionId: String, afterSeq: Long? = null) {
        val p = JSONObject().put("sessionId", sessionId).put("deliveryKind", "desktop-continuous")
        if (afterSeq != null && afterSeq > 0) p.put("afterSeq", afterSeq)
        request("session/subscribe", p)
    }

    suspend fun sendMessage(sessionId: String, content: String) {
        request("session/send", JSONObject().put("sessionId", sessionId).put("content", content), 300_000)
    }

    /** 引擎通道是否已终止（UI/AppSession 据此判断需要重连） */
    val isClosed: Boolean get() = closed.get()

    /** 停止当前轮并等待引擎确认（响应返回≈轮次已终止，turn.completed 事件随后到达） */
    suspend fun stopSession(sessionId: String) {
        runCatching { request("session/stop", JSONObject().put("sessionId", sessionId), 10_000) }
    }

    /** 切换会话模型（恢复桌面端创建的会话时，其模型远端可能不存在，切到可用模型） */
    suspend fun setModel(sessionId: String, model: JSONObject) {
        request("session/setModel", JSONObject().put("sessionId", sessionId).put("model", model))
    }

    /** 切换权限模式（plan/build/edit/yolo；对应 CLI --mode） */
    suspend fun setMode(sessionId: String, mode: String) {
        request("session/setMode", JSONObject().put("sessionId", sessionId).put("mode", mode))
    }

    fun close() {
        runCatching { session?.close() }
    }

    companion object { private const val TAG = "AgentSession" }
}
