package com.zssh.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zssh.app.data.ConnectionStore
import com.zssh.app.data.LoginService
import com.zssh.app.data.ModelProvider
import com.zssh.app.ssh.AppSession
import com.zssh.app.ui.notifyLoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 模型配置页：套餐账号登录 + 供应商列表（从远端拉取同步 / 本地编辑 / 推送回远端） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(ConnectionStore.getProviders(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ModelProvider?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var syncBusy by remember { mutableStateOf(false) }

    // ---- 套餐账号登录（OAuth → 自动换取 Coding Plan API Key）----
    var loginBusy by remember { mutableStateOf(false) }
    var loginAuthUrl by remember { mutableStateOf<String?>(null) }
    var loginStatus by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }

    // 前后台标记：登录在浏览器授权期间完成时，只在后台才发通知拉回
    var resumed by remember { mutableStateOf(true) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            resumed = e == androidx.lifecycle.Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    fun openInBrowser(url: String) {
        runCatching {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

    fun copyLink(url: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("authorize_url", url))
        status = "授权链接已复制"
    }

    fun startLogin(providerId: String) {
        loginBusy = true; loginAuthUrl = null; loginStatus = null; status = null
        // Android 13+ 通知需运行时授权；拒绝也不影响登录，只是少了"完成拉回"
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        loginJob = scope.launch {
            try {
                val result = LoginService.login(
                    providerId,
                    onAuthorizeUrl = { url -> loginAuthUrl = url; openInBrowser(url) },
                    onStatus = { msg ->
                        // 官方 CLI 流程无回跳：授权后浏览器停在 callback 页属预期，完成靠本 App 轮询
                        loginStatus = if (msg.contains("等待浏览器"))
                            "授权完成后浏览器会停在 zcode.z.ai 的 callback 页，无需理会，直接返回本 App 即可"
                        else msg
                    },
                )
                // 轮询到 ready：尽力拉回前台（被系统限制时静默失败，由通知兜底）
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(ctx, com.zssh.app.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                val provider = LoginService.toProvider(result)
                val cur = providers.toMutableList()
                val i = cur.indexOfFirst { it.name == provider.name }
                if (i >= 0) cur[i] = provider else cur.add(provider)
                providers = cur
                ConnectionStore.saveProviders(ctx, providers)
                var msg = "已登录 ${LoginService.userLabel(result.user)}，套餐供应商已保存（密钥加密存储）"
                runCatching {
                    if (AppSession.pushProviderConfig(cur)) msg += "；已推送远端 provider_config.json"
                }
                status = msg
                if (!resumed) notifyLoginResult(ctx, ok = true, detail = msg)
            } catch (e: CancellationException) {
                status = "已取消登录"
            } catch (e: Exception) {
                status = "登录失败：${e.message?.take(250)}"
                if (!resumed) notifyLoginResult(ctx, ok = false, detail = status ?: "登录失败")
            } finally {
                loginBusy = false; loginAuthUrl = null; loginStatus = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型配置") },
                navigationIcon = { TextButton(onClick = onDone) { Text("完成") } },
                actions = {
                    TextButton(
                        enabled = !syncBusy,
                        onClick = {
                            syncBusy = true; status = "从远端拉取…"
                            scope.launch {
                                try {
                                    val n = AppSession.pullProviderConfig(ctx)
                                    providers = ConnectionStore.getProviders(ctx)
                                    status = if (n > 0) "已从远端同步 $n 个供应商（含密钥）" else "远端暂无供应商配置"
                                } catch (e: Exception) { status = "同步失败: ${e.message?.take(120)}" }
                                finally { syncBusy = false }
                            }
                        },
                    ) { Text("从远端同步") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = ModelProvider(name = "", apiType = "anthropic-messages", baseURL = "", apiKey = "", models = emptyList()); showEditor = true }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            Text(
                "供应商列表会推送到远端 provider_config.json（同名更新，不影响远端其他供应商）。从远端同步可把电脑端配置的模型和密钥拉到手机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp)) }
            // ---- 登录卡片 ----
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("套餐账号登录", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "登录 Z.ai / BigModel 账号，自动获取 Coding Plan 专用 API Key 并生成供应商条目，无需手动填 Key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (loginBusy) {
                        loginAuthUrl?.let { url ->
                            Text(
                                "若浏览器没有自动打开，点下方按钮完成授权：",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { openInBrowser(url) }) { Text("打开授权页面") }
                                TextButton(onClick = { copyLink(url) }) { Text("复制链接") }
                                TextButton(onClick = { loginJob?.cancel() }) { Text("取消") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                loginStatus ?: "登录中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { startLogin("zai") }) { Text("登录 Z.ai") }
                            OutlinedButton(onClick = { startLogin("bigmodel") }) { Text("登录 BigModel") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(providers, key = { it.name }) { p ->
                    Surface(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { editing = p; showEditor = true }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(p.baseURL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "模型: ${p.models.joinToString(", ")} · 密钥: ${if (p.apiKey.isBlank()) "未设置" else "已设置(${p.apiKey.take(5)}…)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditor && editing != null) {
        ProviderEditorDialog(
            initial = editing!!,
            existingNames = providers.filter { it.name != editing?.name }.map { it.name },
            onDismiss = { showEditor = false },
            onSave = { p ->
                val cur = providers.toMutableList()
                // 优先按 providerId 匹配（重命名也能替换旧条目），无 id 的新条目回退按名字
                val i = cur.indexOfFirst { existing ->
                    (p.providerId != null && existing.providerId == p.providerId) ||
                        (p.providerId == null && existing.name == p.name)
                }
                if (i >= 0) cur[i] = p else cur.add(p)
                providers = cur
                ConnectionStore.saveProviders(ctx, providers)
                showEditor = false
                status = "已保存本地。连接状态下可在会话列表页再次进入本页推送远端。"
            },
            onDelete = {
                providers = providers.filterNot { it.name == editing!!.name }
                ConnectionStore.saveProviders(ctx, providers)
                showEditor = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditorDialog(
    initial: ModelProvider,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (ModelProvider) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var apiType by remember { mutableStateOf(initial.apiType) }
    var baseURL by remember { mutableStateOf(initial.baseURL) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var models by remember { mutableStateOf(initial.models.joinToString(", ")) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "添加供应商" else "编辑 ${initial.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = apiType == "anthropic-messages", onClick = { apiType = "anthropic-messages" }, label = { Text("Anthropic") })
                    FilterChip(selected = apiType == "openai-chat-completions", onClick = { apiType = "openai-chat-completions" }, label = { Text("OpenAI") })
                }
                OutlinedTextField(baseURL, { baseURL = it }, label = { Text("请求地址") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(models, { models = it }, label = { Text("模型（逗号分隔，如 glm-5.3,glm-5.3-flash）") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val trimmed = name.trim()
                when {
                    trimmed.isBlank() || baseURL.isBlank() || apiKey.isBlank() || models.isBlank() ->
                        error = "除类型外全部必填"
                    trimmed in existingNames ->
                        error = "已存在同名供应商（LazyColumn key 冲突会导致崩溃）"
                    else -> onSave(
                        ModelProvider(
                            trimmed, apiType, baseURL.trim(), apiKey.trim(),
                            models.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
                            providerId = initial.providerId,  // 编辑保留原始 providerId
                        )
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial.name.isNotBlank()) TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

