package com.zssh.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * 连接配置持久化：EncryptedSharedPreferences（AES-256，密钥在 Android Keystore），
 * 密码/私钥/口令不明文落盘 —— 对齐桌面端 credentials.json 的加密存储思路。
 *
 * 实例只创建一次并缓存（Keystore 校验 + 文件打开是重操作，官方要求复用）；
 * 读路径逐条容错（坏条目跳过），写路径异常不外抛（MasterKey 被系统撤销时只记日志）。
 */
object ConnectionStore {
    private const val PREFS = "zssh_secure_prefs"
    private const val KEY_CONNECTIONS = "connections"
    private const val KEY_PROVIDERS = "model_providers"
    private const val TAG = "ConnectionStore"

    @Volatile private var cachedPrefs: SharedPreferences? = null
    private val lock = Any()

    private fun prefs(ctx: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(lock) {
            cachedPrefs?.let { return it }
            val p = EncryptedSharedPreferences.create(
                ctx.applicationContext, PREFS,
                MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            cachedPrefs = p
            return p
        }
    }

    /** 读取失败（如 MasterKey 被撤销）时返回空列表但不覆盖存储；写操作也做了异常保护 */
    fun list(ctx: Context): List<ConnectionConfig> = try {
        connectionsFromJson(prefs(ctx).getString(KEY_CONNECTIONS, "[]") ?: "[]")
    } catch (e: Exception) {
        Log.e(TAG, "读取连接失败（保持存储不动）", e); emptyList()
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
        runCatching {
            prefs(ctx).edit().putString(KEY_CONNECTIONS, connectionsToJson(list)).apply()
        }.onFailure { Log.e(TAG, "写入连接失败", it) }
    }

    // ---- 供应商列表（模型配置） ----
    fun getProviders(ctx: Context): List<ModelProvider> = try {
        ModelProvider.providersFromJson(prefs(ctx).getString(KEY_PROVIDERS, "[]") ?: "[]")
    } catch (e: Exception) {
        Log.e(TAG, "读取供应商失败（保持存储不动）", e); emptyList()
    }

    fun saveProviders(ctx: Context, list: List<ModelProvider>) {
        runCatching {
            prefs(ctx).edit().putString(KEY_PROVIDERS, ModelProvider.providersToJson(list)).apply()
        }.onFailure { Log.e(TAG, "写入供应商失败", it) }
    }
}
