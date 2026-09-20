package com.zssh.app.ssh

import com.zssh.app.data.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient

/**
 * 跨界面的全局远程会话状态：保持 SSH 连接与引擎实例，
 * 检测页 → 会话列表 → 聊天页共用同一条 SSH 通道。
 */
object AppSession {
    var config: ConnectionConfig? = null
    var detect: DetectResult? = null
    private var ssh: SSHClient? = null
    private var agent: AgentSession? = null

    /** M2 版本检查结果（桌面端同款：远端 zcode-server --version） */
    var serverVersion: String? = null
        private set

    val homeDir: String get() = detect?.home?.ifBlank { "~" } ?: "~"

    suspend fun ensureConnected(cfg: ConnectionConfig): SSHClient = withContext(Dispatchers.IO) {
        config = cfg
        ssh?.takeIf { it.isConnected } ?: SshService.connect(cfg).also { ssh = it }
    }

    /** 桌面端同款幂等检查：远端 zcode-server --version；null 表示未部署 */
    suspend fun versionCheck(): String? = withContext(Dispatchers.IO) {
        val client = ssh ?: return@withContext null
        try {
            val s = client.startSession()
            val out = try {
                val cmd = s.exec("~/.zcode/server/node ~/.zcode/server/zcode-server.cjs --version 2>/dev/null")
                cmd.join(15, java.util.concurrent.TimeUnit.SECONDS)
                cmd.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            } finally { s.close() }
            serverVersion = out.ifBlank { null }
            serverVersion
        } catch (e: Exception) {
            serverVersion = null
            null
        }
    }

    /** 启动引擎（复用存活实例；已断线的实例丢弃重建，避免拿死通道挂住调用方） */
    suspend fun ensureAgent(workspacePath: String): AgentSession {
        val client = ssh ?: throw IllegalStateException("SSH 未连接")
        agent?.let { existing ->
            if (!existing.isClosed) return existing
            runCatching { existing.close() }
            agent = null
        }
        return AgentSession(client, homeDir, workspacePath).also {
            it.start()
            agent = it
        }
    }

    /** 断线重连：丢弃死引擎、启动新实例（会话由调用方 resume 恢复） */
    suspend fun reconnectAgent(workspacePath: String): AgentSession = ensureAgent(workspacePath)

    fun agentOrNull(): AgentSession? = agent

    /** 从远端 provider_config.json 拉取供应商列表（含密钥）→ 手机本地存储。返回条数。 */
    suspend fun pullProviderConfig(ctx: android.content.Context): Int = withContext(Dispatchers.IO) {
        val client = ssh ?: throw IllegalStateException("未连接远端，请先在连接列表建立连接")
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
        val client = ssh ?: return@withContext false
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

    private suspend fun exec(client: SSHClient, cmd: String): String = withContext(Dispatchers.IO) {
        val s = client.startSession()
        try {
            val c = s.exec(cmd)
            c.join(20, java.util.concurrent.TimeUnit.SECONDS)
            c.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally { s.close() }
    }

    /** 列出远端目录的子目录（新会话选工作目录用，对应 CLI --cwd） */
    suspend fun listRemoteDir(path: String): List<String> = withContext(Dispatchers.IO) {
        val client = ssh ?: throw IllegalStateException("SSH 未连接")
        val q = "'" + path.replace("'", "'\\''") + "'"
        exec(client, "cd $q 2>/dev/null && ls -1 -p . 2>/dev/null | grep '/$' | sed 's:/$::' || true")
            .trim().split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun closeAll() {
        runCatching { agent?.close() }
        runCatching { ssh?.disconnect() }
        agent = null; ssh = null; detect = null; config = null; serverVersion = null
    }
}
