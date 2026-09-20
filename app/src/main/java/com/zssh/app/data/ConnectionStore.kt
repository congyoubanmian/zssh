package com.zssh.app.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * 连接配置持久化：EncryptedSharedPreferences（AES-256，密钥在 Android Keystore），
 * 密码/私钥/口令不明文落盘 —— 对齐桌面端 credentials.json 的加密存储思路。
 */
object ConnectionStore {
    private const val PREFS = "zssh_secure_prefs"
    private const val KEY_CONNECTIONS = "connections"
    private const val KEY_PROVIDERS = "model_providers"
    private const val TAG = "ConnectionStore"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, PREFS,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ---- 供应商列表（模型配置） ----
    fun getProviders(ctx: Context): List<ModelProvider> =
        runCatching { ModelProvider.providersFromJson(prefs(ctx).getString(KEY_PROVIDERS, "[]") ?: "[]") }.getOrDefault(emptyList())

    fun saveProviders(ctx: Context, list: List<ModelProvider>) {
        prefs(ctx).edit().putString(KEY_PROVIDERS, ModelProvider.providersToJson(list)).apply()
    }

    fun list(ctx: Context): List<ConnectionConfig> = try {
        connectionsFromJson(prefs(ctx).getString(KEY_CONNECTIONS, "[]") ?: "[]")
    } catch (e: Exception) {
        Log.e(TAG, "读取连接失败", e); emptyList()
    }

    fun save(ctx: Context, c: ConnectionConfig) {
        val cur = list(ctx).toMutableList()
        val i = cur.indexOfFirst { it.id == c.id }
        if (i >= 0) cur[i] = c else cur.add(c)
        persist(ctx, cur)
    }

    fun delete(ctx: Context, id: String) = persist(ctx, list(ctx).filterNot { it.id == id })

    fun get(ctx: Context, id: String): ConnectionConfig? = list(ctx).find { it.id == id }

    private fun persist(ctx: Context, list: List<ConnectionConfig>) {
        prefs(ctx).edit().putString(KEY_CONNECTIONS, connectionsToJson(list)).apply()
    }
}
