package com.zssh.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zssh.app.data.ConnectionConfig
import com.zssh.app.ssh.AppSession
import com.zssh.app.ssh.DetectResult
import com.zssh.app.ssh.SshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 连接检测页（对应桌面端向导第 3 步"连接中"）。
 * 复刻桌面 detect 顺序：连接 → 认证 → uname -s/-m/ostype/$HOME，结果即 CDN manifest 的 platformArch。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectScreen(config: com.zssh.app.data.ConnectionConfig, onSessions: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val steps = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DetectResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        steps.clear(); result = null; error = null; running = true
        scope.launch {
            try {
                val r = SshService.detect(config) { s -> steps.add(s) }
                result = r
                // connectTo：建立（或复用）这条连接的槽位并切换为活动连接；旧连接保持后台存活
                AppSession.connectTo(config)
                AppSession.detect = r
            } catch (e: IllegalStateException) {
                error = e.message
                steps.forEach { } // 保持已显示步骤
            } catch (e: Exception) {
                // message 可能为 null（如 NetworkOnMainThreadException），带上异常类名便于定位
                android.util.Log.e("DetectScreen", "检测/连接流程失败 ${config.username}@${config.host}:${config.port}", e)
                error = e.message ?: "未知错误（${e.javaClass.simpleName}）"
            } finally {
                running = false
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("连接中 · ${config.name}") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!running && result == null && error == null) {
                Button(onClick = { start() }) { Text("开始连接") }
            }
            steps.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary)
                    // 步骤文本可能含 host:port（"建立 SSH 连接 x.x.x.x:22 …"），按当前连接的 config 脱敏
                    Text(maskStepText(s, config))
                }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())

            result?.let { r ->
                HorizontalDivider()
                Text("检测结果", style = MaterialTheme.typography.titleMedium)
                KeyValue("平台架构(manifest platformArch)", r.manifestArch)
                KeyValue("raw uname -s", r.rawPlatform)
                KeyValue("raw uname -m", r.rawArch)
                KeyValue("ostype", r.ostype.ifBlank { "(空)" })
                KeyValue("HOME", r.home)
                Text(
                    "✓ 检测通过。进入会话列表可继续远端已有会话或新建会话。",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            error?.let {
                HorizontalDivider()
                Text("连接失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (result != null) Button(onClick = onSessions) { Text("查看会话列表") }
                if (result != null) OutlinedButton(onClick = { start() }) { Text("重新检测") }
                OutlinedButton(onClick = onBack) { Text("返回") }
            }
        }
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Column {
        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 步骤文本脱敏：把 config 的 host:port 替换成打星形式（"建立 SSH 连接 1.2.3.4:22 …" → "… 1**.***.***.***:22 …"） */
private fun maskStepText(text: String, config: com.zssh.app.data.ConnectionConfig): String {
    val host = config.host
    val maskedHost = when {
        host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' } ->
            host.substringBefore('.') + ".***.***.***"
        host.length > 4 -> host.take(2) + "***" + host.takeLast(2)
        else -> "***"
    }
    return text.replace("$host:${config.port}", "$maskedHost:***")
        .replace(host, maskedHost)
}
