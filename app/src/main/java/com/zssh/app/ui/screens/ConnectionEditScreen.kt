package com.zssh.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zssh.app.data.AuthType
import com.zssh.app.data.ConnectionConfig
import com.zssh.app.data.ConnectionStore

/** 连接编辑页（对应桌面端向导第 2 步"填写配置"） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditScreen(
    editingId: String?,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val existing = editingId?.let { ConnectionStore.get(ctx, it) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var authType by remember { mutableStateOf(existing?.authType ?: AuthType.PRIVATE_KEY) }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var pem by remember { mutableStateOf(existing?.privateKeyPem ?: "") }
    var passphrase by remember { mutableStateOf(existing?.passphrase ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    // SAF 导入私钥文件
    val keyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                ctx.contentResolver.openInputStream(it)?.buffered()?.readBytes()
                    ?.toString(Charsets.UTF_8)
            }.onSuccess { pem = it ?: "" }.onFailure { error = "读取私钥失败: ${it.message}" }
        }
    }

    fun validate(): String? = when {
        name.isBlank() -> "请填写别名"
        host.isBlank() -> "请填写主机"
        username.isBlank() -> "请填写用户名"
        authType == AuthType.PASSWORD && password.isBlank() -> "请填写密码"
        authType == AuthType.PRIVATE_KEY && pem.isBlank() -> "请导入私钥"
        else -> null
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (editingId == null) "新建连接" else "编辑连接") }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("别名") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("主机") }, modifier = Modifier.weight(1f))
                OutlinedTextField(port, { port = it }, label = { Text("端口") }, modifier = Modifier.width(100.dp))
            }
            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("认证方式", style = MaterialTheme.typography.labelLarge)
                FilterChip(
                    selected = authType == AuthType.PASSWORD,
                    onClick = { authType = AuthType.PASSWORD }, label = { Text("密码") },
                )
                FilterChip(
                    selected = authType == AuthType.PRIVATE_KEY,
                    onClick = { authType = AuthType.PRIVATE_KEY }, label = { Text("私钥") },
                )
            }

            if (authType == AuthType.PASSWORD) {
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { keyPicker.launch(arrayOf("*/*")) }) { Text("导入私钥文件") }
                    if (pem.isNotBlank()) Text("已导入 ✓", color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    pem, { pem = it },
                    label = { Text("私钥 PEM 内容（也可直接粘贴）") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
                OutlinedTextField(
                    passphrase, { passphrase = it },
                    label = { Text("私钥口令（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    validate()?.let { error = it } ?: run {
                        val cfg = (existing ?: ConnectionConfig(name = "", host = "", username = "")).copy(
                            name = name.trim(), host = host.trim(), port = port.toIntOrNull() ?: 22,
                            username = username.trim(), authType = authType,
                            password = password.takeIf { authType == AuthType.PASSWORD },
                            privateKeyPem = pem.takeIf { authType == AuthType.PRIVATE_KEY },
                            passphrase = passphrase.takeIf { it.isNotBlank() },
                        )
                        ConnectionStore.save(ctx, cfg)
                        onDone()
                    }
                }) { Text("保存") }
                OutlinedButton(onClick = onDone) { Text("取消") }
            }
        }
    }
}
