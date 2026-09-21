package com.zssh.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * 套餐账号登录（路线 2：手机端复刻 CLI 的 OAuth + API Key 解析链）。
 *
 * 流程与 CLI（auth-login.ts / cli-oauth.ts / coding-plan-api-key.ts）逐请求一致：
 *  1. POST zcode.z.ai/api/v1/oauth/cli/init（Bearer pollToken）→ authorize_url + flow_id
 *  2. 用户在浏览器完成授权；轮询 /oauth/cli/poll/{flow_id} 到 ready
 *  3. zai 家族：accessToken 换 api.z.ai 业务 token → getCustomerInfo 定位机构/项目
 *     → 找/建名为 zcode-api-key 的 Key → /copy/{key} 取 secret → "apiKey.secretKey"
 *     bigmodel 家族：accessToken 直接作 Authorization 查 bigmodel.cn（无 secret，返回 apiKey）
 *  4. 结果映射为套餐供应商（builtin 模板同款端点与模型），密钥进 ConnectionStore 加密存储
 */
object LoginService {

    private const val OAUTH_BASE = "https://zcode.z.ai/api/v1"
    private const val ZAI_API_HOST = "https://api.z.ai"
    private const val BIGMODEL_API_HOST = "https://bigmodel.cn"
    private const val KEY_NAME = "zcode-api-key"
    private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L

    data class LoginResult(
        val providerId: String,   // "zai" | "bigmodel"
        val user: JSONObject,     // user_id / email? / name? / avatar?
        val apiKey: String,
    )

    suspend fun login(
        providerId: String,
        onAuthorizeUrl: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): LoginResult = withContext(Dispatchers.IO) {
        require(providerId == "zai" || providerId == "bigmodel") { "provider 必须是 zai 或 bigmodel" }

        // ---- OAuth init ----
        val pollToken = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val init = httpJson(
            "POST", "$OAUTH_BASE/oauth/cli/init", "Bearer $pollToken",
            JSONObject().put("provider", providerId),
        )
        checkOAuthEnvelope(init)
        val initData = init.optJSONObject("data")
            ?: throw IllegalStateException("OAuth init 响应缺少 data")
        val flowId = initData.optString("flow_id")
        val authorizeUrl = initData.optString("authorize_url")
        if (flowId.isBlank() || !authorizeUrl.startsWith("https://"))
            throw IllegalStateException("OAuth init 响应不完整")
        val pollIntervalSec = initData.optLong("poll_interval_sec", 2L).coerceAtLeast(1)
        val expiresAtMs = initData.optLong("expires_at", 0L) * 1000
        val deadline = minOf(System.currentTimeMillis() + LOGIN_TIMEOUT_MS, if (expiresAtMs > 0) expiresAtMs else Long.MAX_VALUE)

        onAuthorizeUrl(authorizeUrl)
        onStatus("等待浏览器完成授权…")

        // ---- 轮询到 ready ----
        var ready: JSONObject? = null
        while (ready == null && System.currentTimeMillis() < deadline) {
            val poll = httpJson("GET", "$OAUTH_BASE/oauth/cli/poll/$flowId", "Bearer $pollToken", null)
            checkOAuthEnvelope(poll)
            val d = poll.optJSONObject("data") ?: JSONObject()
            when (d.optString("status")) {
                "ready" -> ready = d
                "failed" -> throw IllegalStateException("授权失败（被拒绝或已过期），请重试")
                else -> delay(TimeUnit.SECONDS.toMillis(pollIntervalSec))
            }
        }
        val r = ready ?: throw IllegalStateException("授权超时（5 分钟内未完成）")
        val user = r.optJSONObject("user") ?: JSONObject()
        val accessToken = r.optJSONObject(providerId)?.optString("access_token")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("授权响应缺少 access_token")

        // ---- 换取套餐 API Key ----
        onStatus("授权成功，正在获取套餐 API Key…")
        val apiKey = if (providerId == "bigmodel") {
            resolveBizApiKey(BIGMODEL_API_HOST, accessToken, requireSecretKey = false)
        } else {
            val zLogin = httpJson(
                "POST", "$ZAI_API_HOST/api/auth/z/login", null,
                JSONObject().put("token", accessToken),
            )
            val bizToken = bizData(zLogin)?.let { (it as? JSONObject)?.optString("access_token") }
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("z.ai 业务登录响应缺少 access_token")
            resolveBizApiKey(ZAI_API_HOST, "Bearer $bizToken", requireSecretKey = true)
        }
        LoginResult(providerId = providerId, user = user, apiKey = apiKey)
    }

    /** 登录结果 → 供应商条目（端点/模型取自官方 zcode-builtin.json 的 Coding Plan 模板） */
    fun toProvider(result: LoginResult): ModelProvider = if (result.providerId == "bigmodel") {
        ModelProvider(
            name = "BigModel 套餐",
            apiType = "anthropic-messages",
            baseURL = "https://open.bigmodel.cn/api/anthropic",
            apiKey = result.apiKey,
            models = listOf("GLM-5.3", "GLM-5.3-Flash"),
        )
    } else {
        ModelProvider(
            name = "Z.ai 套餐",
            apiType = "anthropic-messages",
            baseURL = "https://api.z.ai/api/anthropic",
            apiKey = result.apiKey,
            models = listOf("GLM-5.3", "GLM-5.3-Flash"),
        )
    }

    fun userLabel(user: JSONObject): String =
        user.optString("email").ifBlank { user.optString("name") }.ifBlank { user.optString("user_id") }

    // ---- 业务侧换 key（机构/项目定位 → 找/建 zcode-api-key → 取 secret）----

    private fun resolveBizApiKey(host: String, authorization: String, requireSecretKey: Boolean): String {
        val customer = bizData(httpJson("GET", "$host/api/biz/customer/getCustomerInfo", authorization, null))
            ?: throw IllegalStateException("客户信息响应缺少 data")
        val orgs = (customer as? JSONObject)?.optJSONArray("organizations") ?: JSONArray()
        var org: JSONObject? = null
        for (i in 0 until orgs.length()) {
            val o = orgs.getJSONObject(i)
            if (o.optString("organizationName").contains("默认机构")) { org = o; break }
        }
        if (org == null) org = orgs.optJSONObject(0)
        val projects = org?.optJSONArray("projects") ?: JSONArray()
        var project: JSONObject? = null
        for (i in 0 until projects.length()) {
            val p = projects.getJSONObject(i)
            if (p.optString("projectName").contains("默认项目")) { project = p; break }
        }
        if (project == null) project = projects.optJSONObject(0)
        val orgId = org?.optString("organizationId").orEmpty()
        val projectId = project?.optString("projectId").orEmpty()
        if (orgId.isBlank() || projectId.isBlank())
            throw IllegalStateException("无法定位机构/项目（账号可能没有可用套餐）")

        val listUrl = "$host/api/biz/v1/organization/$orgId/projects/$projectId/api_keys"
        val keys = (bizData(httpJson("GET", listUrl, authorization, null)) as? JSONArray) ?: JSONArray()
        var entry: JSONObject? = null
        for (i in 0 until keys.length()) {
            if (keys.getJSONObject(i).optString("name") == KEY_NAME) { entry = keys.getJSONObject(i); break }
        }
        if (entry == null) {
            entry = bizData(
                httpJson("POST", listUrl, authorization, JSONObject().put("name", KEY_NAME)),
            ) as? JSONObject
        }
        val apiKey = entry?.optString("apiKey")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("API Key 响应缺少 apiKey")
        val secret = bizData(
            httpJson(
                "GET", "$listUrl/copy/" + URLEncoder.encode(apiKey, "UTF-8"),
                authorization, null,
            ),
        ) as? JSONObject
        val secretKey = secret?.optString("secretKey")?.takeIf { it.isNotBlank() }.orEmpty()
        if (secretKey.isBlank()) {
            if (requireSecretKey) throw IllegalStateException("API Key 响应缺少 secretKey")
            return apiKey
        }
        return "$apiKey.$secretKey"
    }

    // ---- HTTP / 信封 ----

    /** zcode.z.ai OAuth 信封：{code:0, data, msg}，code 非 0 即业务错误 */
    private fun checkOAuthEnvelope(root: JSONObject) {
        val code = if (root.has("code") && !root.isNull("code")) root.optInt("code", -1) else 0
        if (code != 0) throw IllegalStateException(root.optString("msg").ifBlank { "OAuth 业务错误 code=$code" })
    }

    /** api.z.ai / bigmodel.cn 信封：code 缺失/0/200/"0"/"200" 均算成功，返回 data（对象或数组） */
    private fun bizData(root: JSONObject): Any? {
        if (root.has("code") && !root.isNull("code")) {
            val raw = root.get("code")
            val ok = raw is Int && (raw == 0 || raw == 200) ||
                raw is String && (raw == "0" || raw == "200")
            if (!ok) throw IllegalStateException(root.optString("msg").ifBlank { "业务错误 code=$raw" })
        }
        return if (root.has("data") && !root.isNull("data")) root.get("data") else null
    }

    private fun httpJson(method: String, url: String, authorization: String?, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "zssh-android")
            if (authorization != null) conn.setRequestProperty("Authorization", authorization)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code：${text.take(200)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
