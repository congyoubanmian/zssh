package com.zssh.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AuthType { PASSWORD, PRIVATE_KEY }

/** 一条 SSH 远程连接配置（对应桌面端向导第 2 步的表单） */
data class ConnectionConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,                       // 别名，如 "txqly"
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PRIVATE_KEY,
    val password: String? = null,
    val privateKeyPem: String? = null,      // 私钥 PEM 内容（SAF 导入或粘贴）
    val passphrase: String? = null,         // 私钥口令（可选）
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port)
        put("username", username); put("authType", authType.name)
        password?.let { put("password", it) }
        privateKeyPem?.let { put("privateKeyPem", it) }
        passphrase?.let { put("passphrase", it) }
    }

    companion object {
        /** 逐条容错：字段缺失/枚举未知时回退默认值，绝不抛异常（单条脏数据只影响自身） */
        fun fromJson(o: JSONObject): ConnectionConfig? {
            val name = o.optString("name")
            val host = o.optString("host")
            val username = o.optString("username")
            if (host.isBlank() || username.isBlank()) return null
            val authType = runCatching { AuthType.valueOf(o.optString("authType", "PRIVATE_KEY")) }
                .getOrDefault(AuthType.PRIVATE_KEY)
            fun optStr(k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k)?.takeIf { it.isNotBlank() } else null
            return ConnectionConfig(
                id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = name.ifBlank { host },
                host = host,
                port = o.optInt("port", 22).takeIf { it in 1..65535 } ?: 22,
                username = username,
                authType = authType,
                password = optStr("password"),
                privateKeyPem = optStr("privateKeyPem"),
                passphrase = optStr("passphrase"),
            )
        }
    }
}

/** 逐条解析并跳过坏条目：一条脏数据不再导致整个列表变空 */
fun connectionsFromJson(s: String): List<ConnectionConfig> = try {
    val arr = JSONArray(s)
    (0 until arr.length()).mapNotNull { i ->
        runCatching { arr.getJSONObject(i)?.let { ConnectionConfig.fromJson(it) } }.getOrNull()
    }
} catch (e: Exception) { emptyList() }

fun connectionsToJson(list: List<ConnectionConfig>): String =
    JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
