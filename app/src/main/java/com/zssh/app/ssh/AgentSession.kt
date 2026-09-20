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
 * Kotlin 版的 m0/poc_ssh.mjs —— 协议细节全部来自 M0 实测：
 *  - 启动即有 startup/storageState 通知
 *  - 必答 session/requestRuntimePreferences（15s 超时）
 *  - interaction/requestPermission 需答 {decision, reason?}
 *  - session/create {workspace:{workspacePath,workspaceKey}} / session/resume {sessionId}
 *  - session/subscribe {sessionId, deliveryKind:"desktop-continuous"} 后才有 session/event
 *  - session/send {sessionId, content}
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

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val serverPending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(1)
    private var session: net.schmizz.sshj.connection.channel.direct.Session? = null
    private val enginePath by lazy { "$homeDir/.zcode/server/agents/glm/zcode.cjs" }

    suspend fun start() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val command = buildString {
            append("cd $workspacePath && ")
            append("ZCODE_HOME=$homeDir ZCODE_DATA_BASE_DIR=$homeDir ")
            append("ZCODE_BUILTIN_PROVIDER_CONFIG_FILE=$homeDir/.zcode/v2/runtime/provider/bundled/zcode-builtin.json ")
            append("$homeDir/.zcode/server/node $enginePath app-server --stdio")
        }
        Log.i(TAG, "启动引擎: $command")
        val s = ssh.startSession()
        session = s
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
        Unit
    }

    @Volatile private var started = false
    private val closed = AtomicBoolean(false)

    private suspend fun waitForStartup() {
        // startup 通知会进 events 流；轮询 started 标志即可（引擎首包 1s 内到达）
        val deadline = System.currentTimeMillis() + 15_000
        while (!started && !closed.get() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
        if (!started) throw IllegalStateException(
            if (closed.get()) "引擎启动失败（通道已关闭，请检查远端是否已部署 zcode-server）"
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
                    if (msg.has("error")) d.completeExceptionally(IllegalStateException(msg.getJSONObject("error").toString()))
                    else d.complete(msg.optJSONObject("result") ?: JSONObject())
                }
            }
            msg.has("method") -> {
                val method = msg.getString("method")
                started = started || method == "startup/storageState"
                val params = msg.optJSONObject("params") ?: JSONObject()
                // 服务端反向请求必须应答
                if (hasId) {
                    val serverId = msg.getString("id")
                    when (method) {
                        "session/requestRuntimePreferences" ->
                            respond(serverId, JSONObject().put("nativeSearchEnhancementsEnabled", false))
                        "interaction/requestPermission" -> {
                            serverPending[serverId] = CompletableDeferred()
                            permissionRequests.emit(params.put("_serverId", serverId))
                        }
                        else -> respond(serverId, JSONObject())
                    }
                }
                if (method == "session/event") events.emit(params)
            }
        }
    }

    /** UI 对权限请求的决策回包 */
    fun respondPermission(serverId: String, decision: String, reason: String) {
        respond(serverId, JSONObject().put("decision", decision).put("reason", reason))
        serverPending.remove(serverId)?.complete(JSONObject())
    }

    private fun respond(id: String, result: JSONObject) {
        writeLine(JSONObject().put("id", id).put("result", result).toString())
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
