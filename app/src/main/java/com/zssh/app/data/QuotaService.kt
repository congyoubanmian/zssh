package com.zssh.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 套餐额度查询（PORTING-PLAN C1）。
 *
 * 接口：GET {业务域}/api/monitor/usage/quota/limit
 *  - zai 家族业务域 https://api.z.ai，bigmodel 家族 https://bigmodel.cn
 *  - 鉴权：monitor 接口 Authorization 头直传 API Key 原文，禁止 Bearer 前缀
 *    （对齐桌面端 createBigModelUsageHeaders 的契约，批次二注意事项）
 *
 * 响应解析宽容（业务后端私有 API 无版本承诺）：
 *  - 信封 {code, msg, success, data}：code 缺失/null/0/"0"/200/"200" 且 success 非 false 才算成功
 *  - limits[] 窗口识别：5 小时窗 = (TOKENS_LIMIT|CREDIT_LIMIT) unit=3 number=5；每周 = unit=6；
 *    工具 = TIME_LIMIT；MCP = MCP_USAGE_LIMIT（桌面端 mcpQuota.aggregate 的合成 type）。
 *    percentage 字段是「已用占比」，展示剩余必须反转（100-percentage）；
 *    未知 unit/type 跳过不炸，条数计入 skippedUnknown，原文保留在 rawJson 调试出口
 *  - CREDIT_LIMIT 与 TOKENS_LIMIT 语义等价（zai/bigmodel 后端枚举命名不同），同窗口处理
 *
 * MCP 额度（GET /api/v1/mcp/usage）需要 ZCode JWT + MaaS 登录 JWT（桌面端
 * officialMcpCredentials 五个身份头），手机端未持久化这两类 token（待 C6 OAuth 分支接入），
 * 这里仅在响应 data 内嵌 mcpQuota 字段时宽容解析，取不到就静默缺省该行。
 */
object QuotaService {

    private const val ZAI_API_HOST = "https://api.z.ai"
    private const val BIGMODEL_API_HOST = "https://bigmodel.cn"
    private const val QUOTA_PATH = "/api/monitor/usage/quota/limit"
    private const val RAW_JSON_CAP = 8000   // 原始 JSON 调试展示上限，防止超长响应拖垮布局

    /** 套餐供应商名（与 LoginService.toProvider 生成的 builtin 模板名一致） */
    private const val ZAI_PLAN_NAME = "Z.ai 套餐"
    private const val BIGMODEL_PLAN_NAME = "BigModel 套餐"

    /** Token/Credit 类配额的等价 type 集合：zai 后端叫 CREDIT_LIMIT，bigmodel 叫 TOKENS_LIMIT */
    private val TOKEN_LIMIT_TYPES = setOf("TOKENS_LIMIT", "CREDIT_LIMIT")

    /** 一个可用额度来源：套餐供应商（family 决定业务域与展示名） */
    data class QuotaProvider(
        val family: String,     // "zai" | "bigmodel"
        val label: String,      // 展示名（Z.ai 套餐 / BigModel 套餐）
        val apiKey: String,
        val host: String,       // 业务域
    )

    /** 一条额度窗口（已归一为「剩余」口径；数值缺失的字段为 null，由 UI 兜底展示 --） */
    data class QuotaLimitRow(
        val type: String,               // 原始 type（TOKENS_LIMIT / TIME_LIMIT / …）
        val label: String,              // 中文窗口名
        val used: Double?,              // usage / currentValue（已用）
        val remaining: Double?,         // remaining（剩余）
        val total: Double?,             // used+remaining 推导（两者齐才有）
        val remainingPercent: Double?,  // 剩余百分比 0..100（percentage 反转或 remaining/total 推导）
        val nextResetTime: Long?,       // 重置时间（毫秒）
    )

    data class QuotaSnapshot(
        val providerLabel: String,
        val host: String,
        val level: String?,             // 套餐等级（data.level），如 Lite/Max
        val rows: List<QuotaLimitRow>,  // 已知窗口（固定顺序：5 小时 → 每周 → 工具 → MCP）
        val skippedUnknown: Int,        // 因未知 unit/type 跳过的条数（原文见 rawJson）
        val rawJson: String,            // 原始响应（缩进美化，超长截断）
    )

    /** 从模型配置里找套餐供应商（apiKey 非空才算可用）；都不缺时返回多个供页面切换 */
    fun resolveProviders(ctx: Context): List<QuotaProvider> =
        ConnectionStore.getProviders(ctx)
            .filter { it.apiKey.isNotBlank() && (it.name == ZAI_PLAN_NAME || it.name == BIGMODEL_PLAN_NAME) }
            .map { p ->
                if (p.name == ZAI_PLAN_NAME) QuotaProvider("zai", p.name, p.apiKey, ZAI_API_HOST)
                else QuotaProvider("bigmodel", p.name, p.apiKey, BIGMODEL_API_HOST)
            }

    /** 拉取并解析额度快照；网络/信封错误抛异常（页面负责展示与重试） */
    suspend fun fetch(provider: QuotaProvider): QuotaSnapshot = withContext(Dispatchers.IO) {
        val text = httpGetJson("${provider.host}$QUOTA_PATH", provider.apiKey)
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw IllegalStateException("响应不是合法 JSON：${text.take(120)}")
        checkEnvelope(root)
        val data = root.optJSONObject("data")

        val rows = mutableListOf<QuotaLimitRow>()
        var skipped = 0
        val limits = data?.optJSONArray("limits") ?: JSONArray()
        for (i in 0 until limits.length()) {
            val o = limits.optJSONObject(i) ?: continue   // 坏条目只丢自己
            val type = o.optString("type").trim()
            if (type.isBlank()) { skipped++; continue }
            val label = windowLabel(type, o.optLongOrNull("unit"), o.optLongOrNull("number"))
            if (label == null) { skipped++; continue }    // 未知 unit/type：跳过不炸
            rows.add(toRow(o, type, label))
        }
        // 宽容：若响应内嵌 mcpQuota.aggregate（桌面快照的 MCP 汇总位），转成一条等价行
        data?.optJSONObject("mcpQuota")?.optJSONObject("aggregate")?.let { agg ->
            toRow(agg, "MCP_USAGE_LIMIT", "MCP 调用").takeIf { it.used != null || it.remaining != null }
                ?.let { rows.add(it) }
        }

        QuotaSnapshot(
            providerLabel = provider.label,
            host = provider.host,
            level = data?.optString("level")?.trim()?.takeIf { it.isNotBlank() },
            rows = sortRows(rows),
            skippedUnknown = skipped,
            rawJson = prettyJson(root),
        )
    }

    // ---- 解析 ----

    /** 窗口识别：返回中文标签；未知组合返回 null（调用方跳过） */
    private fun windowLabel(type: String, unit: Long?, number: Long?): String? = when {
        type == "MCP_USAGE_LIMIT" -> "MCP 调用"
        type == "TIME_LIMIT" -> "工具调用（每月）"
        type in TOKEN_LIMIT_TYPES && unit == 3L && number == 5L -> "5 小时窗口"
        type in TOKEN_LIMIT_TYPES && unit == 6L -> "每周额度"
        else -> null
    }

    private fun toRow(o: JSONObject, type: String, label: String): QuotaLimitRow {
        val used = o.optDoubleOrNull("usage") ?: o.optDoubleOrNull("currentValue")
        val remaining = o.optDoubleOrNull("remaining")
        val total = if (used != null && remaining != null) used + remaining else null
        val usedPct = o.optDoubleOrNull("percentage")   // 接口口径：已用占比
        val remainingPercent = when {
            usedPct != null -> (100.0 - usedPct).coerceIn(0.0, 100.0)
            remaining != null && total != null && total > 0 -> (remaining / total * 100.0).coerceIn(0.0, 100.0)
            else -> null
        }
        return QuotaLimitRow(
            type = type,
            label = label,
            used = used?.takeIf { it >= 0 },
            remaining = remaining?.takeIf { it >= 0 },
            total = total?.takeIf { it > 0 },
            remainingPercent = remainingPercent,
            nextResetTime = o.optDoubleOrNull("nextResetTime")?.toLong()?.takeIf { it > 0 },
        )
    }

    /** 固定展示顺序：5 小时 → 每周 → 工具 → MCP → 其他已知类型按原样 */
    private fun sortRows(rows: List<QuotaLimitRow>): List<QuotaLimitRow> {
        val order = listOf("5 小时窗口", "每周额度", "工具调用（每月）", "MCP 调用")
        return rows.sortedBy { order.indexOf(it.label).let { i -> if (i >= 0) i else order.size } }
    }

    /** monitor 信封：success 显式为 false，或 code 非缺失/0/200（含字符串形态）即业务错误 */
    private fun checkEnvelope(root: JSONObject) {
        val successBad = root.has("success") && !root.isNull("success") && !root.optBoolean("success", true)
        val codeRaw = if (root.has("code") && !root.isNull("code")) root.opt("code") else null
        val codeOk = codeRaw == null ||
            (codeRaw is Int && (codeRaw == 0 || codeRaw == 200)) ||
            (codeRaw is String && (codeRaw == "0" || codeRaw == "200"))
        if (successBad || !codeOk) {
            val msg = root.optString("msg").ifBlank { root.optString("message") }
            throw IllegalStateException(msg.ifBlank { "业务错误 code=$codeRaw" })
        }
    }

    private fun prettyJson(root: JSONObject): String = runCatching { root.toString(2) }
        .getOrElse { root.toString() }
        .let { if (it.length > RAW_JSON_CAP) it.take(RAW_JSON_CAP) + "\n…（超长已截断）" else it }

    // ---- org.json 宽容取值：缺失/类型不符/NaN 一律 null ----
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        optDoubleOrNull(key)?.let { if (it == Math.floor(it)) it.toLong() else null }

    // ---- HTTP：复刻 LoginService.httpJson 风格（GET 无 body）----
    private fun httpGetJson(url: String, apiKey: String): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "zssh-android")
            // monitor 接口契约：API Key 原文直传，禁止 Bearer 前缀
            conn.setRequestProperty("Authorization", apiKey)
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code：${text.take(200)}")
            text
        } finally {
            conn.disconnect()
        }
    }
}
