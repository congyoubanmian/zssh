package com.zssh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zssh.app.data.AppSettings
import com.zssh.app.data.ConnectionConfig
import com.zssh.app.ssh.AppSession
import com.zssh.app.ui.screens.ChatScreen
import com.zssh.app.ui.screens.ConnectionEditScreen
import com.zssh.app.ui.screens.ConnectionsScreen
import com.zssh.app.ui.screens.DetectScreen
import com.zssh.app.ui.screens.LogsScreen
import com.zssh.app.ui.screens.ModelConfigScreen
import com.zssh.app.ui.screens.ProfileScreen
import com.zssh.app.ui.screens.QuotaScreen
import com.zssh.app.ui.screens.SessionsScreen
import com.zssh.app.ui.screens.ToolsScreen
import com.zssh.app.ui.theme.ZSshTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("tab/ssh", "SSH", Icons.Filled.Terminal),
    Tab("tab/sessions", "会话", Icons.AutoMirrored.Filled.Chat),
    Tab("tab/tools", "工具", Icons.Filled.Build),
    Tab("tab/profile", "我的", Icons.Filled.AccountCircle),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        enableEdgeToEdge()
        setContent {
            val themeMode by AppSettings.themeMode.collectAsState()
            ZSshTheme(themeMode) {
                val nav = rememberNavController()
                var detectTarget by remember { mutableStateOf<ConnectionConfig?>(null) }
                val backStackEntry by nav.currentBackStackEntryAsState()
                val route = backStackEntry?.destination?.route
                    // 底栏只在 4 个 tab 页显示；detect/edit/chat/modelcfg/quota/logs 为全屏页
                val showBottomBar = route?.startsWith("tab/") == true

                // tab 间切换：回到各自保存的状态；从任意 tab/全屏页回 SSH tab
                fun goTab(tabRoute: String) {
                    nav.navigate(tabRoute) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = route == tab.route,
                                        onClick = { goTab(tab.route) },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { pad ->
                    NavHost(
                        navController = nav,
                        startDestination = "tab/ssh",
                        modifier = Modifier.padding(pad),
                    ) {
                        composable("tab/ssh") {
                            ConnectionsScreen(
                                onAdd = { nav.navigate("edit") },
                                onEdit = { nav.navigate("edit/${it.id}") },
                                onConnect = { c ->
                                    // 已存活的连接：秒切（只改 active 指针），不再绕检测页；
                                    // 未连接过的才走 detect 全流程
                                    if (AppSession.switchTo(c)) {
                                        goTab("tab/sessions")
                                    } else {
                                        detectTarget = c
                                        nav.navigate("detect")
                                    }
                                },
                            )
                        }
                        composable("tab/sessions") {
                            SessionsScreen(
                                onOpen = { id -> nav.navigate("chat/$id") },
                                onNewSession = { dir ->
                                    nav.navigate("chat/new?dir=" + android.net.Uri.encode(dir))
                                },
                                onGoConnect = { goTab("tab/ssh") },
                            )
                        }
                        composable("tab/tools") {
                            ToolsScreen(
                                onModelConfig = { nav.navigate("modelcfg") },
                                onQuota = { nav.navigate("quota") },
                                onLogs = { nav.navigate("logs") },
                                onGoConnect = { goTab("tab/ssh") },
                            )
                        }
                        composable("tab/profile") {
                            ProfileScreen(
                                onModelConfig = { nav.navigate("modelcfg") },
                                onGoConnect = { goTab("tab/ssh") },
                            )
                        }
                        // ---- 全屏页（无底栏） ----
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
                                    onSessions = { goTab("tab/sessions") },
                                    onBack = { nav.popBackStack() },
                                )
                            }
                        }
                        composable("modelcfg") { ModelConfigScreen(onDone = { nav.popBackStack() }) }
                        composable("quota") {
                            QuotaScreen(
                                onBack = { nav.popBackStack() },
                                onModelConfig = { nav.navigate("modelcfg") },
                            )
                        }
                        composable("logs") { LogsScreen(onBack = { nav.popBackStack() }) }
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
}
