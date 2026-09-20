package com.zssh.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zssh.app.ssh.AppSession

/** 远程目录选择器（新会话选工作目录）：上级导航 + ls 浏览 + 手动输入 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirPickerDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var browsePath by remember { mutableStateOf(initial) }
    var dirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var dirError by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(initial) }

    LaunchedEffect(browsePath) {
        dirError = null
        runCatching { AppSession.listRemoteDir(browsePath) }
            .onSuccess { dirs = it }
            .onFailure { dirs = emptyList(); dirError = it.message?.take(120) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择工作目录", maxLines = 1) },
        text = {
            Column {
                Text(browsePath, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        val parent = java.io.File(browsePath).parent ?: "/"
                        browsePath = if (parent.isBlank()) "/" else parent
                    }) { Text("⬆ 上级") }
                    OutlinedTextField(
                        manual, { manual = it },
                        label = { Text("手动输入路径") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    TextButton(onClick = { if (manual.isNotBlank()) browsePath = manual.trim() }) { Text("转到") }
                }
                dirError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(6.dp))
                if (dirs.isEmpty() && dirError == null) Text("(无子目录)", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(dirs) { d ->
                        TextButton(onClick = {
                            browsePath = if (browsePath == "/") "/$d" else "$browsePath/$d"
                            manual = browsePath
                        }) { Text("📁 $d") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(browsePath) }) { Text("用此目录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
