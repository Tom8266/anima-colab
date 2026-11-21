package com.tom.animacolab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tom.animacolab.colab.KeepaliveService

/** 通知：Colab 会话常驻保活 + 生成完成提醒 */
object Notifications {

    private const val CHANNEL_SESSION = "colab_session"
    private const val CHANNEL_GENERATION = "generation"
    const val SESSION_NOTIF_ID = 1001
    const val DONE_NOTIF_ID = 1002
    const val LOST_NOTIF_ID = 1003

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, "Colab 会话保活", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Colab 会话运行期间保持前台，防止被系统回收"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_GENERATION, "生成完成提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Anima 出图完成/失败通知"
            },
        )
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopSessionPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepaliveService::class.java).setAction(KeepaliveService.ACTION_STOP_SESSION)
        return PendingIntent.getService(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 前台保活服务的常驻通知 */
    fun sessionNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Anima Colab 会话运行中")
            .setContentText("Colab 保活中，生成图片不受影响")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPendingIntent(context))
            .addAction(0, "打开 App", openAppPendingIntent(context))
            .addAction(0, "停止会话", stopSessionPendingIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun showSessionReady(context: Context) {
        if (!canNotify(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(
                LOST_NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_GENERATION)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Colab 已就绪")
                    .setContentText("会话已连接，可以开始生成图片")
                    .setAutoCancel(true)
                    .setContentIntent(openAppPendingIntent(context))
                    .build(),
            )
        }
    }

    fun showSessionLost(context: Context, message: String) {
        if (!canNotify(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(
                LOST_NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_GENERATION)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Colab 会话断开")
                    .setContentText(message.take(120))
                    .setAutoCancel(true)
                    .setContentIntent(openAppPendingIntent(context))
                    .build(),
            )
        }
    }

    fun showGenerationDone(context: Context, count: Int, downloaded: Int) {
        if (!canNotify(context)) return
        val text = when {
            downloaded > 0 -> "已生成 $count 张并自动下载到本地；点「保存」才会存进系统相册"
            count > 1 -> "已生成 $count 张，点击打开 App 查看"
            else -> "已生成 1 张，点击打开 App 查看"
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(
                DONE_NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_GENERATION)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Anima 出图完成")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openAppPendingIntent(context))
                    .build(),
            )
        }
    }

    fun showGenerationFailed(context: Context, message: String) {
        if (!canNotify(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(
                DONE_NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_GENERATION)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Anima 生成失败")
                    .setContentText(message.take(120))
                    .setAutoCancel(true)
                    .setContentIntent(openAppPendingIntent(context))
                    .build(),
            )
        }
    }

    fun cancelSession(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(LOST_NOTIF_ID) }
    }
}
