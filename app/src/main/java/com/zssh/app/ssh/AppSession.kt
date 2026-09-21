package com.zssh.app.ssh

import com.zssh.app.data.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient

/**
 * 跨界面的全局远程会话状态（多连接版）：
 * - 按 config.id 维护连接槽位（SSH + 引擎 + detect 结果），互不干扰、可同时存活；
 * - activeId 指向「当前操作的连接」，UI 的 connState 与全部 XXX 方法都路由到它；
 * - 切换 = 改 activeId（旧连接保持在后台活跃），断开 = 销毁对应槽位。
 * 每个槽位的状态变更经自己的 mutex 串行化，防止并发下创建双连接/双引擎。
 */
object AppSession {
    /** 连接状态（UI 可观察）：tab 徽标、空状态引导、个人中心状态卡都读它 */
    sealed class ConnState {
        data object Idle : ConnState()
        data class Connected(val label: String) : ConnState()   // label = user@host:port
    }

    /** 一个连接的全部存活状态 */
    private class Slot(val cfg: ConnectionConfig) {
        var ssh: SSHClient? = null
        var agent: AgentSession? = null
        var detect: DetectResult? = null
        var serverVersion: String? = null
        val mutex = Mutex()
        val label: String get() = "${cfg.username}@${cfg.host}:${cfg.port}"
    }

    private val slots = LinkedHashMap<String, Slot>()   // key = config.id
    private val registryLock = Any()
    @Volatile private var activeId: String? = null

    private val _connState = kotlinx.coroutines.flow.MutableStateFlow<ConnState>(ConnState.Idle)
    val connState: kotlinx.coroutines.flow.StateFlow<ConnState> = _connState

    private fun slot(): Slot = activeId?.let { id -> synchronized(registryLock) { slots[id] } }
        ?: throw IllegalStateException("无活动连接，请先在连接列表建立连接")

    private fun active(): Slot? = activeId?.let { id -> synchronized(registryLock) { slots[id] } }

    private fun emitState(slot: Slot?) {
        _connState.value = if (slot == null) ConnState.Idle else ConnState.Connected(slot.label)
    }

    // ---- 连接管理（多连接入口）----

    /** 连接（或切换到）指定机器：已存活则仅切换 active，不重建、不断旧。
     *  整条走 IO 调度器：调用方多在主线程 launch，SshService.connect 是阻塞网络调用，
     *  主线程直调会吃 NetworkOnMainThreadException（message 为 null，UI 只能显示"未知错误"） */
    suspend fun connectTo(cfg: ConnectionConfig): SSHClient = withContext(Dispatchers.IO) {
        val slot = synchronized(registryLock) { slots.getOrPut(cfg.id) { Slot(cfg) } }
        slot.mutex.withLock {
            val existing = slot.ssh
            if (existing == null || !existing.isConnected) {
                runCatching { existing?.disconnect() }
                try {
                    slot.ssh = SshService.connect(cfg)
                } catch (e: Exception) {
                    android.util.Log.e("AppSession", "connectTo 失败 ${cfg.username}@${cfg.host}:${cfg.port}", e)
                    if (activeId == cfg.id) emitState(null)
                    throw e
                }
            }
        }
        activeId = cfg.id
        emitState(slot)
        slot.ssh!!
    }

    /** 切换活动连接（不建连接、不断旧）：仅当该连接有存活 SSH 时返回 true（调用方据此秒切，
     *  未存活则回退 detect 全流程）；从未连接过/已断线返回 false */
    fun switchTo(cfg: ConnectionConfig): Boolean {
        val slot = synchronized(registryLock) { slots[cfg.id] } ?: return false
        if (slot.ssh?.isConnected != true) return false
        activeId = cfg.id
        emitState(slot)
        return true
    }

    /** 当前活动连接的配置（无则 null） */
    var config: ConnectionConfig?
        get() = active()?.cfg
        private set(value) { /* 兼容旧写法：DetectScreen 曾直接赋值；新代码走 connectTo */ }

    /** 兼容旧调用点：等价 connectTo（语义=「确保这条已连接并成为活动」） */
    suspend fun ensureConnected(cfg: ConnectionConfig): SSHClient = connectTo(cfg)

    /** 指定连接是否有存活的 SSH（UI 徽标用） */
    fun isConnected(id: String): Boolean =
        synchronized(registryLock) { slots[id] }?.ssh?.isConnected == true

    /** 断开指定连接（null = 当前活动）；断的是活动连接时 active 指到剩余的第一个 */
    fun disconnect(id: String? = null) {
        val target = id ?: activeId ?: return
        val slot = synchronized(registryLock) { slots.remove(target) } ?: return
        runCatching { slot.agent?.close() }
        Thread {
            runCatching { slot.ssh?.disconnect() }
        }.start()
        if (target == activeId) {
            activeId = synchronized(registryLock) { slots.keys.firstOrNull() }
            emitState(activeId?.let { synchronized(registryLock) { slots[it] } })
        }
    }

    /** 兼容旧调用点：断开全部（登出/清理场景） */
    fun closeAll() {
        val snapshot = synchronized(registryLock) { slots.values.toList() }
        synchronized(registryLock) { slots.clear() }
        activeId = null
        emitState(null)
        snapshot.forEach { slot ->
            runCatching { slot.agent?.close() }
            Thread { runCatching { slot.ssh?.disconnect() } }.start()
        }
    }

    // ---- 以下方法全部路由到活动连接 ----

    var detect: DetectResult?
        get() = active()?.detect
        set(value) { active()?.detect = value ?: return }

    /** M2 版本检查结果（远端 zcode-server --version） */
    var serverVersion: String? = null
        get() = active()?.serverVersion
        private set

    val homeDir: String get() = active()?.detect?.home?.ifBlank { "~" } ?: "~"

    suspend fun versionCheck(): String? = withContext(Dispatchers.IO) {
        val slot = slot()
        val client = slot.ssh ?: return@withContext null
        try {
            val s = client.startSession()
            val out = try {
                val cmd = s.exec("~/.zcode/server/node ~/.zcode/server/zcode-server.cjs --version 2>/dev/null")
                cmd.join(15, java.util.concurrent.TimeUnit.SECONDS)
                cmd.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            } finally { s.close() }
            slot.serverVersion = out.ifBlank { null }
            slot.serverVersion
        } catch (e: Exception) {
            slot.serverVersion = null
            null
        }
    }

    /** 启动引擎（复用存活实例；已断线的实例丢弃重建，避免拿死通道挂住调用方） */
    suspend fun ensureAgent(workspacePath: String): AgentSession {
        val slot = slot()
        slot.mutex.withLock {
            val client = slot.ssh ?: throw IllegalStateException("SSH 未连接")
            slot.agent?.let { existing ->
                if (!existing.isClosed) return existing
                runCatching { existing.close() }
                slot.agent = null
            }
            val fresh = AgentSession(client, homeDir, workspacePath)
            try {
                fresh.start()
            } catch (e: Exception) {
                // 启动失败的实例不保留（其通道已在 start 内清理），避免半构造实例泄漏
                runCatching { fresh.close() }
                throw e
            }
            slot.agent = fresh
            return fresh
        }
    }

    /** 断线重连：丢弃死引擎、启动新实例（会话由调用方 resume 恢复） */
    suspend fun reconnectAgent(workspacePath: String): AgentSession = ensureAgent(workspacePath)

    fun agentOrNull(): AgentSession? = active()?.agent

    /** 从远端 provider_config.json 拉取供应商列表（含密钥）→ 手机本地存储。返回条数。 */
    suspend fun pullProviderConfig(ctx: android.content.Context): Int = withContext(Dispatchers.IO) {
        val client = slot().ssh ?: throw IllegalStateException("未连接远端，请先在连接列表建立连接")
        val content = exec(client, "cat \$HOME/.zcode/v2/provider_config.json 2>/dev/null")
        val providers = com.zssh.app.data.ModelProvider.parseRemoteConfig(content)
        if (providers.isNotEmpty()) {
            // 与本地已有合并：远端优先，本地独有保留
            val local = com.zssh.app.data.ConnectionStore.getProviders(ctx)
            val merged = providers + local.filter { l -> providers.none { it.name == l.name } }
            com.zssh.app.data.ConnectionStore.saveProviders(ctx, merged)
        }
        providers.size
    }

    /** 把手机端供应商列表推送合并进远端（按 providerName 更新/追加，不覆盖远端其他供应商） */
    suspend fun pushProviderConfig(providers: List<com.zssh.app.data.ModelProvider>): Boolean = withContext(Dispatchers.IO) {
        val client = slot().ssh ?: return@withContext false
        if (providers.isEmpty()) return@withContext false
        val readScript = buildString {
            append("F=\$HOME/.zcode/v2/provider_config.json; mkdir -p \$(dirname \$F); ")
            append("if [ -f \$F ]; then base64 -w0 \$F; else echo '{}'; fi")
        }
        val remoteB64orJson = exec(client, readScript).trim()
        val merged = buildMergedConfig(remoteB64orJson, providers)
        val payloadB64 = android.util.Base64.encodeToString(merged.toByteArray(), android.util.Base64.NO_WRAP)
        val writeScript = buildString {
            append("echo $payloadB64 | base64 -d > \$HOME/.zcode/v2/provider_config.json.new && ")
            append("chmod 600 \$HOME/.zcode/v2/provider_config.json.new && ")
            append("mv -f \$HOME/.zcode/v2/provider_config.json.new \$HOME/.zcode/v2/provider_config.json && echo PUSHED")
        }
        exec(client, writeScript).contains("PUSHED")
    }

    private fun buildMergedConfig(remoteContent: String, providers: List<com.zssh.app.data.ModelProvider>): String {
        // 远端内容是 base64（文件存在时）；不存在时是原始 "{}"，两种都兼容
        val raw = try {
            String(android.util.Base64.decode(remoteContent.trim(), android.util.Base64.NO_WRAP))
        } catch (e: Exception) { remoteContent }
        val root = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
        val configObj = root.optJSONObject("config") ?: org.json.JSONObject().also { root.put("schemaVersion", 1).put("config", it) }
        val rules = configObj.optJSONObject("providerConfigRules") ?: org.json.JSONObject().also { configObj.put("providerConfigRules", it) }
        val arr = rules.optJSONArray("providerRules") ?: org.json.JSONArray().also { rules.put("providerRules", it) }
        // 推送匹配：优先按 providerId（保留远端原始 uuid id，不破坏会话的模型引用），回退按 providerName
        for (p in providers) {
            val pid = p.providerId?.takeIf { it.isNotBlank() } ?: "android-" + p.name
            for (i in arr.length() - 1 downTo 0) {
                val r = arr.getJSONObject(i)
                if (r.optString("providerId") == pid || r.optString("providerName") == p.name) arr.remove(i)
            }
            arr.put(org.json.JSONObject().apply {
                put("providerId", pid)
                put("providerName", p.name)
                put("config", org.json.JSONObject().apply {
                    put("group", "standard-personal")
                    put("access", org.json.JSONObject().put("type", "api-key").put("apiKey", p.apiKey))
                    put("api", org.json.JSONObject().put("type", p.apiType).put("baseUrl", p.baseURL))
                    put("personalModelIds", org.json.JSONArray(p.models))
                    put("modelOrder", org.json.JSONArray(p.models))
                })
            })
        }
        return root.toString()
    }

    private suspend fun exec(client: SSHClient, cmd: String, timeoutSec: Long = 20): String = withContext(Dispatchers.IO) {
        val s = client.startSession()
        try {
            val c = s.exec(cmd)
            c.join(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            c.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally { s.close() }
    }

    /** 在现有（活动）连接上执行一条命令并返回 stdout；timeoutSec 可调（大输出/慢网用） */
    suspend fun execRemote(cmd: String, timeoutSec: Long = 20): String = withContext(Dispatchers.IO) {
        val client = slot().ssh ?: throw IllegalStateException("SSH 未连接")
        exec(client, cmd, timeoutSec)
    }

    /** 列出远端目录的子目录（新会话选工作目录用，对应 CLI --cwd） */
    suspend fun listRemoteDir(path: String): List<String> = withContext(Dispatchers.IO) {
        val client = slot().ssh ?: throw IllegalStateException("SSH 未连接")
        val q = "'" + path.replace("'", "'\\''") + "'"
        exec(client, "cd $q 2>/dev/null && ls -1 -p . 2>/dev/null | grep '/$' | sed 's:/$::' || true")
            .trim().split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }
}
