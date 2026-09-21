package com.zssh.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.zssh.app.data.QuotaService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 全屏套餐额度页（PORTING-PLAN C1）：
 * HTTPS 直连业务域读 quota/limit，不依赖 SSH 连接；
 * 各窗口剩余量进度条（percentage 已用口径反转）+ 原始 JSON 折叠调试出口。
 * Key 来源是模型配置里的套餐供应商（「我的」页 OAuth 登录写入），缺失时引导去模型配置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaScreen(
    onBack: () -> Unit,
    onModelConfig: () -> Unit,
) {
    val ctx = LocalContext.current
    var providers by remember { mutableStateOf<List<QuotaService.QuotaProvider>?>(null) } // null = 解析中
    var selected by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<QuotaService.QuotaSnapshot?>(null) }
    var rawExpanded by remember { mutableStateOf(false) }

    // 套餐供应商只在本页进入时解析一次（EncryptedSharedPreferences 首开是重操作，放 IO 线程）
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { QuotaService.resolveProviders(ctx) }
        if (list.isEmpty()) loading = false   // 无可用 Key：停在引导态，不转圈
        providers = list
        if (selected == null) selected = list.firstOrNull()?.family
    }

    // 取数：切换供应商 / 手动刷新触发；selected 在上一effect落定后才会命中
    LaunchedEffect(selected, refreshKey) {
        val p = providers?.firstOrNull { it.family == selected } ?: return@LaunchedEffect
        loading = true; error = null
        try {
            snapshot = QuotaService.fetch(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snapshot = null
            error = "读取额度失败：${e.message?.take(300) ?: "未知错误"}"
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("套餐额度") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }, enabled = !loading && selected != null) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 供应商切换（两家套餐都有 Key 时才显示）----
            val list = providers
            if (list != null && list.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { p ->
                        FilterChip(
                            selected = selected == p.family,
                            onClick = { selected = p.family },
                            label = { Text(p.label) },
                        )
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                // ---- 引导：没有套餐 Key ----
                list != null && list.isEmpty() -> Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.DataUsage, contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("还没有可用的套餐 API Key", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "在「我的」页完成套餐登录（自动获取 API Key），或到模型配置手动添加后返回本页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onModelConfig) { Text("去模型配置") }
                    }
                }
                // ---- 出错 ----
                error != null -> Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { refreshKey++ }) { Text("重试") }
                    }
                }
                // ---- 快照 ----
                snapshot != null -> {
                    val snap = snapshot!!
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(snap.providerLabel, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildString {
                                    append(snap.host).append("/api/monitor/usage/quota/limit")
                                    snap.level?.let { append(" · 套餐等级 ").append(it) }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (snap.skippedUnknown > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "有 ${snap.skippedUnknown} 个未知类型的额度条目已跳过（见下方原始 JSON）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (snap.rows.isEmpty()) {
                        Text(
                            "响应里没有识别出的额度窗口",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    snap.rows.forEach { row -> QuotaRowCard(row) }

                    // ---- 原始 JSON 调试出口（折叠展示）----
                    Card(
                        onClick = { rawExpanded = !rawExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Terminal, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "原始响应 JSON（调试）",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (rawExpanded) "收起" else "展开",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (rawExpanded) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SelectionContainer {
                                    Text(
                                        snap.rawJson,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单个额度窗口：标题 + 剩余百分比 + 进度条 + 剩余/已用/重置时间 */
@Composable
private fun QuotaRowCard(row: QuotaService.QuotaLimitRow) {
    val pct = row.remainingPercent
    // 剩余两成以下转红色提示，其余用主色
    val barColor = if (pct != null && pct <= 20.0) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    formatPercent(pct),
                    style = MaterialTheme.typography.titleSmall,
                    color = barColor,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (pct ?: 0.0).toFloat() / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = barColor,
            )
            Spacer(Modifier.height(6.dp))
            val amounts = buildString {
                append("剩余 ").append(fmtAmount(row.remaining))
                append(" · 已用 ").append(fmtAmount(row.used))
                row.total?.let { append(" · 共 ").append(fmtAmount(it)) }
            }
            Text(amounts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.nextResetTime?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    "重置于 ${formatResetTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 数值格式：够大取整加千分位，不足一位小数；缺失显示 -- */
private fun fmtAmount(v: Double?): String = when {
    v == null -> "--"
    v >= 100.0 -> String.format(Locale.getDefault(), "%,.0f", v)
    else -> String.format(Locale.getDefault(), "%.1f", v)
}

/** 剩余百分比：≥10% 取整、<10% 留一位小数（对齐桌面端 formatQuotaRemainingPercentage 口径） */
private fun formatPercent(pct: Double?): String =
    if (pct == null) "--%" else String.format(
        Locale.getDefault(),
        if (pct >= 10.0) "%.0f%%" else "%.1f%%",
        pct,
    )

/** 重置时间自适应：当日只显 HH:mm（时刻才可行动），非当日只显日期 */
private fun formatResetTime(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { time = Date(ms) }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "M月d日", Locale.getDefault()).format(Date(ms))
}
