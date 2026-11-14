package com.tom.animacolab.colab

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tom.animacolab.Notifications
import com.tom.animacolab.data.Pairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 「一键模式」会话管理器：登录 Google 后，自动复用/申请 Colab T4 会话 →
 * 创建 Jupyter kernel → 在 kernel 里执行内置部署脚本 → 解析 PAIR → 自动完成 App 配对。
 *
 * 部署脚本 assets/colab_deploy.py 与 Colab 笔记本是同一份逻辑（幂等）：
 * 已部署过的会话几秒内就能返回 PAIR；新会话首次约 3-5 分钟。
 */
class ColabSessionManager(
    private val context: Context,
    private val auth: GoogleAuth,
    private val store: ColabSessionStore,
    private val scope: CoroutineScope,
) {

    sealed interface State {
        data object Off : State
        data object Starting : State
        data class Deploying(val stage: String, val logLines: Int) : State
        data class Ready(val device: String, val startedAtMs: Long) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _pair = MutableStateFlow<Pairing.Pair?>(null)
    val pair: StateFlow<Pairing.Pair?> = _pair.asStateFlow()

    private var startJob: Job? = null
    private var keepaliveJob: Job? = null
    private var lastError: String? = null
    private var keepaliveServiceStarted = false
    private val logLock = Any()

    /** 后台保活（前台服务 + 4 分钟心跳）是否开启，由设置页切换 */
    var keepaliveEnabled: Boolean = true

    init {
        store.existingPair()?.let { _pair.value = it }
    }

    val loggedIn: Boolean get() = auth.isLoggedIn()

    fun existingPair(): Pairing.Pair? = store.existingPair()

    val isBusy: Boolean
        get() = _state.value is State.Starting || _state.value is State.Deploying

    fun start() {
        if (startJob?.isActive == true) return
        startJob = scope.launch { runStart() }
    }

    fun cancelStart() {
        startJob?.cancel()
        startJob = null
        if (isBusy) _state.value = State.Off
    }

    fun forgetFailure() {
        if (_state.value is State.Failed) _state.value = State.Off
    }

    private suspend fun runStart() {
        if (!auth.isLoggedIn()) {
            _state.value = State.Failed("先登录 Google 账号，才能自动创建 Colab 会话")
            return
        }
        _state.value = State.Starting
        _logs.value = emptyList()
        lastError = null
        try {
            val api = ColabApi(accessToken = { auth.accessToken() })

            appendLog("检查账号下的 Colab 会话…")
            val assignments = api.listAssignments()
            var assignment = assignments.firstOrNull { store.endpoint.isNotBlank() && it.endpoint == store.endpoint }
                ?: assignments.firstOrNull { it.accelerator.equals("T4", true) && it.variant == 1 }
            if (assignment == null) {
                appendLog("没有现成 T4 会话，正在申请（免费版约 30-90 秒）…")
                _state.value = State.Deploying("申请 T4 会话…", _logs.value.size)
                assignment = api.assign(ColabApi.notebookHash(UUID.randomUUID()), "T4")
            } else {
                appendLog("复用已有会话：${assignment.endpoint}")
            }
            store.endpoint = assignment.endpoint
            rememberProxy(assignment.runtimeProxy)

            val proxy = ensureProxyFresh(api)
            appendLog("运行时已就绪：${proxy.url.substringAfter("://").substringBefore("/")}")

            val kernelClient = JupyterKernelClient(proxy.url, proxy.token)
            store.kernelId.takeIf { it.isNotBlank() }?.let { old ->
                runCatching { kernelClient.deleteKernel(old) }
            }
            appendLog("创建 Jupyter kernel…")
            val kernelId = kernelClient.createKernel()
            store.kernelId = kernelId
            appendLog("kernel 已创建：$kernelId")

            appendLog("开始部署 ComfyUI + Anima（首次约 3-5 分钟，已部署过会快很多）…")
            _state.value = State.Deploying("部署中…", _logs.value.size)

            val parser = LineBuffer()
            val ok = kernelClient.execute(
                kernelId = kernelId,
                code = readDeployScript(),
                timeoutMs = 20 * 60 * 1000L,
                onStream = { _, text ->
                    parser.feed(text).forEach { line -> handleDeployLine(line) }
                },
                onError = { msg ->
                    lastError = msg
                    appendLog("ERROR: $msg")
                },
            )
            if (!ok) {
                _state.value = State.Failed(lastError ?: "部署脚本执行失败，展开日志看看卡在哪一步")
                return
            }
            val found = (parser.finish() + _logs.value.reversed()).mapNotNull { parsePairLine(it) }.firstOrNull()
                ?: _pair.value
            if (found == null) {
                _state.value = State.Failed("部署跑完了，但输出里没找到 PAIR 行（日志最后几行有线索）")
                return
            }
            store.pairUrl = found.baseUrl
            store.pairToken = found.token
            store.startedAtMs = System.currentTimeMillis()
            _pair.value = found
            _state.value = State.Ready(deviceLabel(), store.startedAtMs)
            appendLog("✅ Colab 已就绪，PAIR 已自动填入：${found.baseUrl}")
            startKeepalive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = describeError(e)
            lastError = msg
            _state.value = State.Failed(msg)
            appendLog("ERROR: $msg")
        }
    }

    suspend fun stop() {
        startJob?.cancel()
        startJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        val endpoint = store.endpoint
        if (endpoint.isNotBlank() && auth.isLoggedIn()) {
            runCatching {
                val api = ColabApi(accessToken = { auth.accessToken() })
                store.kernelId.takeIf { it.isNotBlank() }?.let { kid ->
                    runCatching {
                        JupyterKernelClient(store.runtimeUrl, store.proxyToken).deleteKernel(kid)
                    }
                }
                api.unassign(endpoint)
                appendLog("已释放 Colab 会话")
            }.onFailure { appendLog("释放会话失败（可能已超时回收）：${it.message}") }
        }
        stopKeepaliveService()
        Notifications.cancelSession(context)
        store.clear()
        _pair.value = null
        _state.value = State.Off
    }

    /** 会话已就绪时的心跳：每 4 分钟在 kernel 里跑一行代码，防止 Colab 闲置回收 */
    private fun startKeepalive() {
        keepaliveEnabled.let { enabled ->
            if (enabled) startKeepaliveService() else stopKeepaliveService()
        }
        Notifications.showSessionReady(context)
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(4 * 60 * 1000L)
                try {
                    val api = ColabApi(accessToken = { auth.accessToken() })
                    val proxy = ensureProxyFresh(api)
                    val client = JupyterKernelClient(proxy.url, proxy.token)
                    val ok = client.execute(
                        kernelId = store.kernelId,
                        code = "print('[app] keepalive')",
                        timeoutMs = 90_000,
                        onStream = { _, _ -> },
                        onError = { appendLog("保活：$it") },
                    )
                    if (ok) {
                        appendLog("保活心跳 OK（${clockText()}）")
                    } else {
                        appendLog("保活失败，会话可能已断开")
                        if (_state.value is State.Ready) {
                            _state.value = State.Failed("Colab 会话好像断开了，点重新启动")
                        }
                        Notifications.showSessionLost(context, "心跳失败，会话可能已被回收")
                        stopKeepaliveService()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendLog("保活失败：${describeError(e)}")
                    if (_state.value is State.Ready) {
                        _state.value = State.Failed("Colab 会话好像断开了：${describeError(e)}")
                    }
                    Notifications.showSessionLost(context, "会话可能已被回收：${describeError(e)}")
                    stopKeepaliveService()
                }
            }
        }
    }

    /** 设置页切换后台保活 */
    fun setBackgroundKeepaliveEnabled(enabled: Boolean) {
        keepaliveEnabled = enabled
        if (enabled) {
            if (_state.value is State.Ready) startKeepalive()
        } else {
            keepaliveJob?.cancel()
            keepaliveJob = null
            stopKeepaliveService()
        }
    }

    /**
     * App 重启/界面重建后：如果本地还存着已配对的会话且 Google 仍登录，
     * 直接恢复 Ready 状态并重新启动保活（网关连接由 ViewModel 检测）。
     */
    fun resumeIfSessionExists(): Boolean {
        if (isBusy) return _state.value is State.Ready
        if (_state.value is State.Ready) return true
        if (!store.hasPair || store.endpoint.isBlank() || !auth.isLoggedIn()) return false
        _state.value = State.Ready("T4", store.startedAtMs)
        appendLog("恢复上次的 Colab 会话，已启动后台保活")
        startKeepalive()
        return true
    }

    private fun startKeepaliveService() {
        if (keepaliveServiceStarted) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, KeepaliveService::class.java))
            keepaliveServiceStarted = true
        }.onFailure { appendLog("前台保活服务启动失败：${it.message}") }
    }

    private fun stopKeepaliveService() {
        if (!keepaliveServiceStarted) return
        runCatching { context.stopService(Intent(context, KeepaliveService::class.java)) }
        keepaliveServiceStarted = false
    }

    // ---------------- 内部 ----------------

    private fun readDeployScript(): String =
        context.assets.open("colab_deploy.py").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun rememberProxy(proxy: RuntimeProxyInfo) {
        store.runtimeUrl = proxy.url
        store.proxyToken = proxy.token
        store.proxyExpiresAtMs = System.currentTimeMillis() + proxy.tokenExpiresInSeconds * 1000L
    }

    private suspend fun ensureProxyFresh(api: ColabApi): RuntimeProxyInfo {
        val now = System.currentTimeMillis()
        if (store.runtimeUrl.isNotBlank() && store.proxyToken.isNotBlank() && store.proxyExpiresAtMs - now > 5 * 60 * 1000L) {
            return RuntimeProxyInfo(store.runtimeUrl, store.proxyToken, ((store.proxyExpiresAtMs - now) / 1000).toInt())
        }
        val assignments = api.listAssignments()
        val assignment = assignments.firstOrNull { it.endpoint == store.endpoint }
            ?: throw ColabProtocolException("Colab 会话已被回收（超时或额度用尽），需要重新启动")
        rememberProxy(assignment.runtimeProxy)
        return assignment.runtimeProxy
    }

    private fun deviceLabel(): String = "T4"

    private fun handleDeployLine(rawLine: String) {
        val line = rawLine.trimEnd()
        if (line.isBlank()) return
        appendLog(if (line.length > 300) line.take(300) + "…" else line)
        parsePairLine(line)?.let { _pair.value = it }
        val current = _state.value
        if (current is State.Deploying && line.startsWith("[deploy]")) {
            val stage = line.removePrefix("[deploy]").trim().take(120)
            if (stage.isNotBlank()) {
                _state.value = State.Deploying(stage, _logs.value.size)
            }
        }
    }

    private fun parsePairLine(line: String): Pairing.Pair? {
        val idx = line.indexOf("PAIR:")
        if (idx < 0) return null
        val rest = line.substring(idx + 5).trim()
        if (rest.isBlank()) return null
        return Pairing.parse(rest)
    }

    private fun appendLog(line: String) {
        synchronized(logLock) {
            val current = _logs.value
            _logs.value = if (current.size >= 400) current.drop(150) + line else current + line
        }
    }

    private fun describeError(e: Exception): String = when (e) {
        is GoogleAuthException -> e.message ?: "Google 登录失效"
        is ColabProtocolException -> when (e.statusCode) {
            401 -> "Google 登录已过期，请重新登录"
            412 -> "Colab 提示同时会话数太多/额度不足：先停止旧会话再试"
            400 -> "Colab 拒绝了 T4 申请（可能没有免费额度），可稍后重试"
            else -> e.message ?: "Colab 请求失败"
        }
        else -> e.message ?: "未知错误"
    }

    private fun clockText(): String {
        val cal = java.util.Calendar.getInstance()
        return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    private class LineBuffer {
        private val buffer = StringBuilder()

        fun feed(text: String): List<String> {
            buffer.append(text)
            val lines = mutableListOf<String>()
            while (true) {
                val idx = buffer.indexOf("\n")
                if (idx < 0) break
                lines.add(buffer.substring(0, idx).trimEnd('\r'))
                buffer.delete(0, idx + 1)
            }
            if (buffer.length > 64 * 1024) {
                lines.add(buffer.toString())
                buffer.setLength(0)
            }
            return lines
        }

        fun finish(): List<String> {
            if (buffer.isEmpty()) return emptyList()
            val leftover = buffer.toString()
            buffer.setLength(0)
            return listOf(leftover)
        }
    }
}
