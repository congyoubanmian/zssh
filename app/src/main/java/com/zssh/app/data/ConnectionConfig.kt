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
        fun fromJson(o: JSONObject) = ConnectionConfig(
            id = o.getString("id"), name = o.getString("name"),
            host = o.getString("host"), port = o.optInt("port", 22),
            username = o.getString("username"),
            authType = AuthType.valueOf(o.optString("authType", "PRIVATE_KEY")),
            password = o.optStringOrNull("password"),
            privateKeyPem = o.optStringOrNull("privateKeyPem"),
            passphrase = o.optStringOrNull("passphrase"),
        )

        private fun JSONObject.optStringOrNull(k: String): String? =
            if (has(k) && !isNull(k)) getString(k) else null
    }
}

fun connectionsToJson(list: List<ConnectionConfig>): String =
    JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

fun connectionsFromJson(s: String): List<ConnectionConfig> =
    JSONArray(s).let { arr -> (0 until arr.length()).map { ConnectionConfig.fromJson(arr.getJSONObject(it)) } }
