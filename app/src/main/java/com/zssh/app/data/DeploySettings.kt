package com.zssh.app.data

import android.content.Context

/**
 * 部署设置（非敏感信息，普通 SharedPreferences）：
 * M2-A 远端自下载模式使用的 CDN 基础地址。
 * 生产地址不在开源仓库里（桌面端运行时经 ZCODE_REMOTE_ASSET_CDN_BASE_URL 注入），
 * 所以做成用户可配置项；版本与组件清单都从该地址自动发现。
 */
object DeploySettings {
    private const val PREFS = "zssh_deploy_prefs"
    private const val KEY_CDN_BASE = "cdn_base"

    fun cdnBase(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CDN_BASE, "") ?: ""

    /** 保存时去掉首尾空白与结尾斜杠，拼接 URL 时不再处理 */
    fun saveCdnBase(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CDN_BASE, url.trim().trimEnd('/'))
            .apply()
    }
}
