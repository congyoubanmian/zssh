package com.zssh.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zssh.app.ssh.AppSession
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全屏远端日志页：分页 tail 当天 zcode CLI 的 JSONL 日志（每页 300 条，向上翻页加载更早的），
 *  防御式解析 + 级别过滤 + 手动刷新。避免一次读大文件超时。 */
private const val PAGE_SIZE = 300

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var noLog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    // 已加载页数；hasMore = 远端还有更早日志可取
    var pages by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 取第 page 页（从文件尾起算：page=1 是最新 PAGE_SIZE 条）。返回 条目 + 是否可能还有更早的
    suspend fun fetchPage(page: Int): Pair<List<LogEntry>, Boolean> {
        val n = page * PAGE_SIZE
        val out = AppSession.execRemote(
            "tail -n $n \$HOME/.zcode/cli/log/zcode-\$(date +%F).jsonl 2>/dev/null | head -n $PAGE_SIZE",
            timeoutSec = 45,
        ).trim()
        if (out.isEmpty()) return emptyList<LogEntry>() to false
        val list = out.lines().filter { it.isNotBlank() }.map { parseLogLine(it) }
        val lineCount = AppSession.execRemote(
            "wc -l < \$HOME/.zcode/cli/log/zcode-\$(date +%F).jsonl 2>/dev/null || echo 0",
            timeoutSec = 30,
        ).trim().toLongOrNull() ?: 0L
        return list to (lineCount > n)
    }

    LaunchedEffect(refreshKey) {
        loading = true; error = null; noLog = false; pages = 1
        try {
            val (list, more) = fetchPage(1)
            entries = list; hasMore = more
            if (list.isEmpty()) noLog = true
        } catch (e: IllegalStateException) {
            entries = emptyList()
            error = "尚未建立 SSH 连接，请先在 SSH 页建立连接后再查看日志"
        } catch (e: Exception) {
            entries = emptyList()
            error = "读取日志失败：${e.message?.take(300)}"
        } finally {
            loading = false
        }
    }

    // 向上翻页：滚到顶部附近时加载更早一页，加载后平移视口位置不跳动
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx <= 2 && hasMore && !loadingMore && !loading) {
                    loadingMore = true
                    try {
                        val nextPage = pages + 1
                        val (list, more) = fetchPage(nextPage)
                        if (list.isNotEmpty()) {
                            entries = list + entries
                            pages = nextPage
                            hasMore = more
                            listState.scrollToItem(list.size + 2)
                        } else {
                            hasMore = false
                        }
                    } catch (e: Exception) {
                        error = "加载更早日志失败：${e.message?.take(200)}"
                    } finally {
                        loadingMore = false
                    }
                }
            }
    }

    val shown = entries.filter { e ->
        when (filter) {
            LogFilter.ALL -> true   // DEBUG 等低级别仅在「全部」显示
            LogFilter.ERROR -> e.level == LogLevel.ERROR
            LogFilter.WARN -> e.level == LogLevel.WARN
            LogFilter.INFO -> e.level == LogLevel.INFO
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("远端日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!loading && error == null && entries.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${shown.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { refreshKey++ }) { Text("重试") }
                    }
                }
                noLog || shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Article, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (noLog) "今天还没有日志" else "当前级别没有日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (loadingMore) {
                        item(key = "loading_more") {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
                        }
                    } else if (hasMore) {
                        item(key = "has_more") {
                            Text(
                                "↑ 上滑加载更早日志",
                                Modifier.fillMaxWidth().padding(8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(shown) { e ->
                        LogRow(e)
                        HorizontalDivider(
                            Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private enum class LogLevel { ERROR, WARN, INFO, DEBUG, OTHER }

private enum class LogFilter(val label: String) {
    ALL("全部"), ERROR("ERROR"), WARN("WARN"), INFO("INFO")
}

private data class LogEntry(
    val level: LogLevel,
    val time: String?,   // HH:mm:ss；提取失败为 null（该行省略时间）
    val msg: String,
)

/** 级别彩色小标签 + 时间 + 等宽消息体 */
@Composable
private fun LogRow(e: LogEntry) {
    val levelColor = when (e.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> Color(0xFFFBBF24)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Surface(color = levelColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
            Text(
                e.level.name,
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                color = levelColor,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            e.time?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(e.msg, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 防御式解析一行 JSONL：不是合法 JSON 就原样展示 */
private fun parseLogLine(line: String): LogEntry {
    val o = runCatching { JSONObject(line) }.getOrNull()
        ?: return LogEntry(LogLevel.OTHER, null, line)
    return LogEntry(
        level = parseLevel(o.opt("level")),
        time = extractTime(o),
        msg = o.optString("msg").ifBlank { o.optString("message") }.ifBlank { line },
    )
}

/** level 兼容字符串与数字（pino 风格 50/40/30；>50 归 ERROR，<30 归 DEBUG） */
private fun parseLevel(v: Any?): LogLevel = when (v) {
    is Number -> when {
        v.toInt() >= 50 -> LogLevel.ERROR
        v.toInt() >= 40 -> LogLevel.WARN
        v.toInt() >= 30 -> LogLevel.INFO
        else -> LogLevel.DEBUG
    }
    is String -> when (v.trim().uppercase()) {
        "ERROR", "FATAL" -> LogLevel.ERROR
        "WARN", "WARNING" -> LogLevel.WARN
        "INFO" -> LogLevel.INFO
        "DEBUG", "TRACE" -> LogLevel.DEBUG
        else -> LogLevel.OTHER
    }
    else -> LogLevel.OTHER
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val clockRegex = Regex("(\\d{2}:\\d{2}:\\d{2})")

/** time/timestamp 兼容 epoch 数字（或数字串）与含 HH:mm:ss 的文本（如 ISO 8601），提取失败返回 null */
private fun extractTime(o: JSONObject): String? {
    val v = o.opt("time") ?: o.opt("timestamp") ?: return null
    return when (v) {
        is Number -> runCatching { timeFmt.format(Date(v.toLong())) }.getOrNull()
        is String -> v.toLongOrNull()
            ?.let { runCatching { timeFmt.format(Date(it)) }.getOrNull() }
            ?: clockRegex.find(v)?.groupValues?.get(1)
        else -> null
    }
}
