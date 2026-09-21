package com.zssh.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zssh.app.BuildConfig
import com.zssh.app.data.AppSettings
import com.zssh.app.data.ConnectionStore
import com.zssh.app.data.DeploySettings
import com.zssh.app.data.LoginService
import com.zssh.app.ssh.AppSession
import com.zssh.app.ui.notifyLoginResult
import com.zssh.app.ui.theme.AppSemantic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 个人中心（底部 Tab4）：
 * 连接状态卡（含去连接入口）、套餐账号 OAuth 登录（自动换取 API Key）、
 * 外观（主题模式切换）、设置入口（模型配置 / CDN 基础地址）与关于信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onModelConfig: () -> Unit,
    onGoConnect: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connState by AppSession.connState.collectAsState()
    val themeMode by AppSettings.themeMode.collectAsState()

    // ---- 套餐登录状态：busy 记录进行中的 providerId，同时只允许一个登录流程 ----
    var loginBusy by remember { mutableStateOf<String?>(null) }
    var loginStatus by remember { mutableStateOf<String?>(null) }
    var loginOk by remember { mutableStateOf<String?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }

    // 后台完成登录时靠通知拉回（Android 10+ 禁止后台启动 Activity）
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
        ActivityResultContracts.RequestPermission(),
    ) { }

    // ---- CDN 基础地址 ----
    var cdnCurrent by remember { mutableStateOf(DeploySettings.cdnBase(ctx)) }
    var showCdnDialog by remember { mutableStateOf(false) }

    fun startLogin(providerId: String) {
        if (loginBusy != null) return
        loginBusy = providerId; loginStatus = null; loginOk = null; loginError = null
        // Android 13+ 通知需运行时授权；拒绝也不影响登录，只是少了「完成拉回」
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        scope.launch {
            try {
                val result = LoginService.login(
                    providerId,
                    onAuthorizeUrl = { url ->
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    onStatus = { msg ->
                        // 官方 CLI 流程无回跳：授权后浏览器停在 callback 页是预期行为，
                        // 完成靠本 App 轮询，必须把「手动返回」这件事告诉用户
                        loginStatus = if (msg.contains("等待浏览器"))
                            "已打开浏览器授权页。授权完成后页面会停在 zcode.z.ai 的 callback，" +
                                "无需理会——直接返回本 App，登录会自动完成"
                        else msg
                    },
                )
                // 轮询到 ready：尽力把 App 拉回前台（被系统限制时静默失败，由通知兜底）
                runCatching {
                    ctx.startActivity(
                        Intent(ctx, com.zssh.app.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                // 登录成功：供应商按 name 替换或追加后保存
                val provider = LoginService.toProvider(result)
                val cur = ConnectionStore.getProviders(ctx).toMutableList()
                val i = cur.indexOfFirst { it.name == provider.name }
                if (i >= 0) cur[i] = provider else cur.add(provider)
                ConnectionStore.saveProviders(ctx, cur)
                loginOk = "✓ 已获取 ${LoginService.userLabel(result.user)} 的 API Key，已保存到模型配置"
                if (!resumed) notifyLoginResult(ctx, ok = true, detail = loginOk!!)
            } catch (e: CancellationException) {
                // 协程被取消（离开页面等）：静默结束
            } catch (e: Exception) {
                loginError = "登录失败：${e.message?.take(200) ?: "未知错误"}"
                if (!resumed) notifyLoginResult(ctx, ok = false, detail = loginError!!)
            } finally {
                loginBusy = null; loginStatus = null
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的") }) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 连接状态卡 ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = when (connState) {
                            is AppSession.ConnState.Connected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        when (val s = connState) {
                            is AppSession.ConnState.Connected -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(AppSemantic.success(), CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text("已连接", style = MaterialTheme.typography.titleMedium)
                                }
                                // 地址默认脱敏（与连接列表一致），点眼睛临时显示
                                var showAddress by remember { mutableStateOf(false) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (showAddress) s.label else maskAddressLabel(s.label),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    IconButton(onClick = { showAddress = !showAddress }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            if (showAddress) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                            contentDescription = if (showAddress) "隐藏地址" else "显示地址",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    AppSession.serverVersion?.let { "zcode v$it" } ?: "zcode 版本未知",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AppSession.ConnState.Idle -> {
                                Text("未连接", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "连接服务器后开始远程开发",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = onGoConnect) { Text("去连接") }
                            }
                        }
                    }
                }
            }

            // ---- 套餐登录 ----
            SectionTitle("套餐登录")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "登录套餐账号可自动获取 API Key（也可在模型配置里手动添加其他厂商 Key）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { startLogin("zai") },
                        enabled = loginBusy == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Z.ai 套餐登录")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { startLogin("bigmodel") },
                        enabled = loginBusy == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("BigModel 套餐登录")
                    }
                    if (loginBusy != null) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        loginStatus?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    loginOk?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = AppSemantic.success())
                    }
                    loginError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ---- 外观 ----
            SectionTitle("外观")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Palette,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("主题模式", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { AppSettings.setThemeMode(ctx, mode) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            // ---- 设置 ----
            SectionTitle("设置")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column {
                    SettingsRow(
                        icon = Icons.Filled.Settings,
                        title = "模型配置",
                        subtitle = "供应商、API Key、模型列表",
                        onClick = onModelConfig,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsRow(
                        icon = Icons.Filled.CloudQueue,
                        title = "CDN 基础地址",
                        subtitle = if (cdnCurrent.isBlank()) "远端部署资源下载源"
                        else "远端部署资源下载源 · $cdnCurrent",
                        onClick = { showCdnDialog = true },
                    )
                }
            }

            // ---- 关于 ----
            SectionTitle("关于")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ZSsh", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "版本 ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "非官方客户端，仅供学习研究；ZCode 为其各自所有者的商标/产品",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // ---- CDN 基础地址编辑对话框 ----
    if (showCdnDialog) {
        var input by remember { mutableStateOf(cdnCurrent) }
        AlertDialog(
            onDismissRequest = { showCdnDialog = false },
            title = { Text("CDN 基础地址") },
            text = {
                Column {
                    Text(
                        "远端部署资源下载源",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DeploySettings.saveCdnBase(ctx, input)
                    cdnCurrent = DeploySettings.cdnBase(ctx)
                    showCdnDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showCdnDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 分组小标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 设置分组里的列表项：图标 + 标题/副标题，整行可点 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 连接标签脱敏：user@1**.***.***.***:22（与连接列表同款规则） */
private fun maskAddressLabel(label: String): String {
    val at = label.indexOf('@')
    val colon = label.lastIndexOf(':')
    if (at <= 0 || colon <= at) return label.take(3) + "***"
    val user = label.substring(0, at)
    val host = label.substring(at + 1, colon)
    val port = label.substring(colon)
    val maskedHost = when {
        host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' } ->
            host.substringBefore('.') + ".***.***.***"
        host.length > 4 -> host.take(2) + "***" + host.takeLast(2)
        else -> "***"
    }
    return "$user@$maskedHost:***"
}
