package com.zssh.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 应用级偏好（非敏感）：主题模式等。改动经 StateFlow 即时生效，无需重启。 */
object AppSettings {
    private const val PREFS = "zssh_app_prefs"
    private const val KEY_THEME_MODE = "theme_mode"   // system | light | dark

    private val _themeMode = MutableStateFlow("system")
    val themeMode: StateFlow<String> = _themeMode

    fun init(ctx: Context) {
        _themeMode.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, "system") ?: "system"
    }

    fun setThemeMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_MODE, mode).apply()
        _themeMode.value = mode
    }
}
