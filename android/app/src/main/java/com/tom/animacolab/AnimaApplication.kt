package com.tom.animacolab

import android.app.Application
import com.tom.animacolab.colab.ColabSessionManager
import com.tom.animacolab.colab.ColabSessionStore
import com.tom.animacolab.colab.GoogleAuth
import com.tom.animacolab.colab.GoogleAuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级容器：Colab 会话管理器放在 Application 里，这样退到后台/界面重建时
 * 前台保活服务和会话状态都不受影响。
 */
class AnimaApplication : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var authStore: GoogleAuthStore
        private set
    lateinit var auth: GoogleAuth
        private set
    lateinit var colabSessionStore: ColabSessionStore
        private set
    lateinit var sessionManager: ColabSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        authStore = GoogleAuthStore(this)
        auth = GoogleAuth(authStore)
        colabSessionStore = ColabSessionStore(this)
        sessionManager = ColabSessionManager(this, auth, colabSessionStore, appScope)
        sessionManager.keepaliveEnabled = getSharedPreferences("anima_config", MODE_PRIVATE)
            .getBoolean("background_keepalive", true)
        // 进程被系统回收/重建后，若本地还有会话与配对，直接恢复保活循环（不依赖界面是否打开）
        runCatching { sessionManager.resumeIfSessionExists() }
    }

    companion object {
        fun from(context: android.content.Context): AnimaApplication =
            context.applicationContext as AnimaApplication
    }
}
