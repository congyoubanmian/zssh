package com.zssh.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zssh.app.MainActivity

/** 登录在后台完成/失败时发本地通知：Android 10+ 禁止后台启动 Activity，通知是唯一可靠的「拉回」方式 */
fun notifyLoginResult(ctx: Context, ok: Boolean, detail: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) return
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
    val channelId = "login"
    nm.createNotificationChannel(
        NotificationChannel(channelId, "登录结果", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val pi = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notif = NotificationCompat.Builder(ctx, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(if (ok) "ZSsh 登录完成" else "ZSsh 登录失败")
        .setContentText(detail)
        .setAutoCancel(true)
        .setContentIntent(pi)
        .build()
    runCatching { nm.notify(1001, notif) }
}
