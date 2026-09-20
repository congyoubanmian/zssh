package com.zssh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zssh.app.ssh.AppSession
import com.zssh.app.ui.screens.ChatScreen
import com.zssh.app.ui.screens.ConnectionEditScreen
import com.zssh.app.ui.screens.ConnectionsScreen
import com.zssh.app.ui.screens.DetectScreen
import com.zssh.app.ui.screens.ModelConfigScreen
import com.zssh.app.ui.screens.SessionsScreen
import com.zssh.app.ui.theme.ZSshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZSshTheme {
                val nav = rememberNavController()
                var detectTarget by remember { mutableStateOf<com.zssh.app.data.ConnectionConfig?>(null) }

                NavHost(nav, startDestination = "list") {
                    composable("list") {
                        ConnectionsScreen(
                            onAdd = { nav.navigate("edit") },
                            onEdit = { nav.navigate("edit/${it.id}") },
                            onConnect = { c ->
                                AppSession.closeAll()          // 新连接前清掉上一次的通道
                                detectTarget = c
                                nav.navigate("detect")
                            },
                        )
                    }
                    composable("edit") { ConnectionEditScreen(editingId = null, onDone = { nav.popBackStack() }) }
                    composable("edit/{id}") { entry ->
                        ConnectionEditScreen(
                            editingId = entry.arguments?.getString("id"),
                            onDone = { nav.popBackStack() },
                        )
                    }
                    composable("detect") {
                        detectTarget?.let { c ->
                            DetectScreen(
                                config = c,
                                onSessions = {
                                    nav.navigate("sessions") { popUpTo("list") }
                                },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                    composable("sessions") {
                        SessionsScreen(
                            onOpen = { id -> nav.navigate("chat/$id") },
                            onNewSession = { dir ->
                                nav.navigate("chat/new?dir=" + android.net.Uri.encode(dir))
                            },
                            onModelConfig = { nav.navigate("modelcfg") },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("modelcfg") { ModelConfigScreen(onDone = { nav.popBackStack() }) }
                    composable(
                        "chat/{sessionId}?dir={dir}",
                        arguments = listOf(androidx.navigation.navArgument("dir") { defaultValue = "" }),
                    ) { entry ->
                        val sid = entry.arguments?.getString("sessionId")
                        val dir = entry.arguments?.getString("dir")?.takeIf { it.isNotBlank() }
                        ChatScreen(sessionId = sid?.takeIf { it != "new" }, dir = dir, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
