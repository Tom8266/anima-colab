package com.tom.animacolab.colab

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.tom.animacolab.AnimaApplication
import com.tom.animacolab.Notifications
import kotlinx.coroutines.launch

/**
 * 前台保活服务：Colab 会话运行期间常驻通知栏，让进程不被系统回收，
 * 从而让 [ColabSessionManager] 的心跳（每 4 分钟一次 kernel 执行）持续生效。
 * 服务本身不跑循环，心跳在 Application 级协程里。
 */
class KeepaliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                val app = AnimaApplication.from(this)
                app.appScope.launch { app.sessionManager.stop() }
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForegroundCompat()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, Notifications.SESSION_NOTIF_ID, Notifications.sessionNotification(this), type)
        }
    }

    companion object {
        const val ACTION_STOP_SESSION = "com.tom.animacolab.action.STOP_COLAB_SESSION"
        const val ACTION_START = "com.tom.animacolab.action.START_KEEPALIVE"
    }
}
