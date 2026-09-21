package com.zssh.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 配色对齐桌面端 ZCode 的 Zai Dark / Zai Light（packages/ui/src/styles.css）：
 * 中性灰底 + 克制的单点强调色，卡片 #2b2b2b/#ffffff、边框 10% 透明度。
 * Android 上强调色用 sky 蓝（桌面默认主题的 brand 色族），深色下用于主操作/选中态。
 */
private val ZaiDark = darkColorScheme(
    primary = Color(0xFF38BDF8),            // sky-400，深色下对比更好
    onPrimary = Color(0xFF082F3F),
    primaryContainer = Color(0xFF1E3A4C),
    onPrimaryContainer = Color(0xFFB9E7FB),
    secondary = Color(0xFFA3A3A3),
    onSecondary = Color(0xFF161616),
    secondaryContainer = Color(0xFF2B2B2B),
    onSecondaryContainer = Color(0xFFD4D4D4),
    background = Color(0xFF161616),
    onBackground = Color(0xFFD4D4D4),
    surface = Color(0xFF161616),
    onSurface = Color(0xFFD4D4D4),
    surfaceVariant = Color(0xFF2B2B2B),     // 卡片
    onSurfaceVariant = Color(0xFFA3A3A3),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF202020),   // panel
    surfaceContainerHigh = Color(0xFF262626),
    outline = Color(0xFF404040),
    outlineVariant = Color(0xFF2E2E2E),
    error = Color(0xFFF87171),
    onError = Color(0xFF3F0A0A),
    errorContainer = Color(0xFF4A1D1D),
    onErrorContainer = Color(0xFFFECACA),
)

private val ZaiLight = lightColorScheme(
    primary = Color(0xFF0284C7),            // sky-600，浅底上保证按钮对比度
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = Color(0xFF525252),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF262626),
    background = Color(0xFFF8F8F8),
    onBackground = Color(0xFF262626),
    surface = Color(0xFFF8F8F8),
    onSurface = Color(0xFF262626),
    surfaceVariant = Color(0xFFFFFFFF),     // 卡片
    onSurfaceVariant = Color(0xFF737373),
    surfaceContainerLow = Color(0xFFF2F2F2),
    surfaceContainer = Color(0xFFFFFFFF),   // panel
    surfaceContainerHigh = Color(0xFFFAFAFA),
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFEBEBEB),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

/** 语义色（M3 colorScheme 没有的位置）：连接成功/在线状态、权限 diff 预览等 */
object AppSemantic {
    val SuccessDark = Color(0xFF46BF72)
    val SuccessLight = Color(0xFF1E8A3E)
    val WarningDark = Color(0xFFFBBF24)
    val WarningLight = Color(0xFFD97706)
    // 权限弹窗 diff 新增行（绿色系）；删除行直接用 errorContainer/onErrorContainer
    val DiffAddBgDark = Color(0xFF173622)
    val DiffAddBgLight = Color(0xFFE3F5E8)
    val DiffAddFgDark = Color(0xFF8CE0A8)
    val DiffAddFgLight = Color(0xFF1B6B36)

    @Composable
    fun success(): Color = if (isSystemInDarkTheme()) SuccessDark else SuccessLight

    @Composable
    fun warning(): Color = if (isSystemInDarkTheme()) WarningDark else WarningLight

    @Composable
    fun diffAddBg(): Color = if (isSystemInDarkTheme()) DiffAddBgDark else DiffAddBgLight

    @Composable
    fun diffAddFg(): Color = if (isSystemInDarkTheme()) DiffAddFgDark else DiffAddFgLight
}

@Composable
fun ZSshTheme(
    themeMode: String = "system",          // system | light | dark
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) ZaiDark else ZaiLight,
        content = content,
    )
}
