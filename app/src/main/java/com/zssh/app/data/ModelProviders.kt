package com.zssh.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 一个模型供应商（对应远端 provider_config.json 里的一条 providerRule） */
data class ModelProvider(
    val name: String,                // providerName，如 kimi / bigmodel
    val apiType: String,             // anthropic-messages | openai-chat-completions
    val baseURL: String,
    val apiKey: String,
    val models: List<String>,        // personalModelIds
    val providerId: String? = null,  // 远端原始 providerId（uuid）；推送时保留，避免破坏会话的模型引用
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("apiType", apiType).put("baseURL", baseURL)
        .put("apiKey", apiKey).put("models", JSONArray(models))
        .put("providerId", providerId ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = ModelProvider(
            name = o.optString("name"),
            apiType = o.optString("apiType", "anthropic-messages"),
            baseURL = o.optString("baseURL"),
            apiKey = o.optString("apiKey"),
            models = o.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            providerId = o.optString("providerId").ifBlank { null },
        )

        fun providersToJson(list: List<ModelProvider>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun providersFromJson(s: String): List<ModelProvider> = try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                runCatching { ModelProvider.fromJson(a.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) { emptyList() }

        /** 从远端 provider_config.json 解析出全部供应商（密钥原样保留）；逐条容错，坏条目跳过 */
        fun parseRemoteConfig(content: String): List<ModelProvider> = try {
            val rules = JSONObject(content)
                .optJSONObject("config")?.optJSONObject("providerConfigRules")
                ?.optJSONArray("providerRules") ?: return emptyList()
            (0 until rules.length()).mapNotNull { i ->
                // 条目级容错：一条脏数据只丢自己，不影响其余供应商
                runCatching {
                    val r = rules.getJSONObject(i)
                    val cfg = r.optJSONObject("config") ?: return@runCatching null
                    val api = cfg.optJSONObject("api")
                    val baseURL = api?.optString("baseUrl") ?: ""
                    if (baseURL.isBlank()) return@runCatching null
                    val models = cfg.optJSONArray("personalModelIds")
                        ?.let { a -> (0 until a.length()).mapNotNull { j -> runCatching { a.getString(j) }.getOrNull() } }
                        ?: emptyList()
                    ModelProvider(
                        name = r.optString("providerName", "provider-${i + 1}"),
                        apiType = api?.optString("type")?.takeIf { it.isNotBlank() } ?: "anthropic-messages",
                        baseURL = baseURL,
                        apiKey = cfg.optJSONObject("access")?.optString("apiKey") ?: "",
                        models = models,
                        providerId = r.optString("providerId").ifBlank { null },
                    )
                }.getOrNull()
            }.filterNotNull()
        } catch (e: Exception) { emptyList() }
    }
}
