package com.tom.animacolab.colab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Colab 运行时（`*.colab.dev`）上的 Jupyter kernel 客户端。
 *
 * - REST 走 OkHttp 标准 HTTP：列出 / 创建 / 删除 kernel。
 * - 代码执行走 Jupyter 默认 WebSocket 协议（纯 JSON 文本帧），URL 形如
 *   `wss://<runtime>/api/kernels/<kid>/channels?session_id=<hex32>&token=<proxyToken>`。
 *
 * 纯 Kotlin/JVM 实现，不依赖 Android API。
 *
 * @param runtimeUrl 会话的 `runtimeProxyInfo.url`，例如 `https://8080-xxx.colab.dev`。
 * @param proxyToken `runtimeProxyInfo.token`。
 * @param httpClient 可选自定义 OkHttpClient（例如手机代理场景）。
 */
class JupyterKernelClient(
    runtimeUrl: String,
    proxyToken: String,
    private val httpClient: OkHttpClient = newHttpClient(),
) {

    private val runtimeUrl: String = runtimeUrl.trim().trimEnd('/')
    private val proxyToken: String = proxyToken.trim()

    init {
        require(this.runtimeUrl.isNotBlank()) { "runtimeUrl 不能为空" }
        require(this.proxyToken.isNotBlank()) { "proxyToken 不能为空" }
    }

    // -------------------------------------------------------------- REST API

    /** GET /api/kernels → 当前运行中的 kernel id 列表。 */
    suspend fun listKernels(): List<String> {
        val (status, body) = executeHttp(
            Request.Builder().url(kernelsUrl()).headers(httpHeaders()).get().build(),
        )
        if (status !in 200..299) {
            throw ColabProtocolException("获取 kernel 列表失败：HTTP $status${bodySnippet(body)}", statusCode = status)
        }
        val array = runCatching { JSONArray(body) }.getOrElse {
            throw ColabProtocolException("解析 kernel 列表失败：${body.take(300)}", it)
        }
        return buildList {
            for (i in 0 until array.length()) {
                val id = array.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(id)
            }
        }
    }

    /** POST /api/kernels → 新建 kernel 并返回其 id。 */
    suspend fun createKernel(name: String = "python3"): String {
        val payload = JSONObject()
            .put("name", name)
            .put("path", JSONObject.NULL)
            .toString()
        val (status, body) = executeHttp(
            Request.Builder()
                .url(kernelsUrl())
                .headers(httpHeaders())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        if (status !in 200..299) {
            throw ColabProtocolException("创建 kernel 失败：HTTP $status${bodySnippet(body)}", statusCode = status)
        }
        val id = runCatching { JSONObject(body).optString("id") }.getOrNull().orEmpty()
        if (id.isBlank()) {
            throw ColabProtocolException("创建 kernel 失败：响应里没有 id：${body.take(300)}")
        }
        return id
    }

    /** DELETE /api/kernels/{id}。404/410 视为已不存在（幂等删除）。 */
    suspend fun deleteKernel(kernelId: String) {
        val (status, body) = executeHttp(
            Request.Builder().url(kernelUrl(kernelId)).headers(httpHeaders()).delete().build(),
        )
        if (status in 200..299 || status == 404 || status == 410) return
        throw ColabProtocolException("删除 kernel 失败：HTTP $status${bodySnippet(body)}", statusCode = status)
    }

    // -------------------------------------------------------------- Execute

    /**
     * 在指定 kernel 里执行一段代码，直到同时收到 shell/execute_reply 和 iopub/status=idle。
     *
     * - 执行期间 iopub `stream` 消息（stdout/stderr）会实时回调 [onStream]（name/text）。
     * - iopub `error` 消息或 execute_reply.status=error 会回调 [onError]（格式化后的 traceback）。
     * - 返回 `true` 仅当 execute_reply.status == "ok"；执行报错返回 `false`（同时已回调 onError）。
     * - 超时（[timeoutMs]，覆盖建连+执行全过程）会关闭 WebSocket、回调 onError("执行超时…") 并返回 `false`。
     * - WebSocket 连接/中断失败抛 [ColabProtocolException]；协程取消会抛 [CancellationException]
     *   （finally 里关闭 socket）。
     *
     * 注意：回调在 OkHttp 的接收线程/协程上下文里执行，调用方如需更新 UI 请自行切线程。
     */
    suspend fun execute(
        kernelId: String,
        code: String,
        timeoutMs: Long = DEFAULT_EXECUTE_TIMEOUT_MS,
        onStream: (name: String, text: String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        require(kernelId.isNotBlank()) { "kernelId 不能为空" }
        require(timeoutMs > 0) { "timeoutMs 必须大于 0" }

        val sessionId = newSessionId()
        val requestMsgId = newMessageId()
        val payload = buildExecuteRequest(code, requestMsgId, sessionId)
        var connection: WsConnection? = null
        try {
            // 超时覆盖“建连 + 发送 + 等待结果”全过程。
            val replyOk = withTimeoutOrNull(timeoutMs) {
                val conn = connect(channelsWebSocketUrl(kernelId, sessionId))
                connection = conn
                if (!conn.socket.send(payload)) {
                    throw ColabProtocolException("发送 execute_request 失败：WebSocket 已关闭")
                }
                awaitExecutionCompleted(conn, requestMsgId, onStream, onError)
            }
            if (replyOk == null) {
                onError("执行超时（${timeoutMs / 1000} 秒）：未在超时内完成建连并收到 execute_reply + iopub idle。")
                return false
            }
            return replyOk
        } finally {
            connection?.close()
        }
    }

    /** 消费 WS 事件直到 execute_reply 与 iopub idle 都到齐；返回 execute_reply.status == ok。 */
    private suspend fun awaitExecutionCompleted(
        connection: WsConnection,
        requestMsgId: String,
        onStream: (name: String, text: String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        var replyReceived = false
        var idleReceived = false
        var replyOk = false
        var errorReported = false

        while (!(replyReceived && idleReceived)) {
            when (val event = connection.events.receive()) {
                is WsEvent.Opened -> Unit

                is WsEvent.Text -> {
                    val message = parseWireMessage(event.text) ?: continue
                    if (message.parentMsgId != requestMsgId) continue
                    when (message.msgType) {
                        "stream" -> {
                            val name = message.content.optString("name", "stdout")
                            val text = message.content.optString("text", "")
                            if (text.isNotEmpty()) onStream(name, text)
                        }

                        "error" -> {
                            onError(formatKernelError(message.content))
                            errorReported = true
                        }

                        "status" -> {
                            if (message.content.optString("execution_state") == "idle") idleReceived = true
                        }

                        "execute_reply" -> {
                            replyReceived = true
                            replyOk = message.content.optString("status") == "ok"
                            if (!replyOk && !errorReported) onError(formatExecuteReplyError(message.content))
                        }

                        else -> Unit // execute_input/display_data/execute_result 等暂不需要
                    }
                }

                is WsEvent.Failure -> throw ColabProtocolException(
                    "Kernel WebSocket 连接失败：${event.error.message}",
                    event.error,
                )

                is WsEvent.Closed -> throw ColabProtocolException(
                    "Kernel WebSocket 提前关闭（code=${event.code}，reason=${event.reason}），执行结果未知。",
                )
            }
        }
        return replyOk
    }

    // ------------------------------------------------------------- WebSocket

    private class WsConnection(val socket: WebSocket, val events: Channel<WsEvent>) {
        fun close() {
            runCatching { socket.cancel() }
            events.close()
        }
    }

    private sealed class WsEvent {
        object Opened : WsEvent()
        data class Text(val text: String) : WsEvent()
        data class Failure(val error: Throwable) : WsEvent()
        data class Closed(val code: Int, val reason: String) : WsEvent()
    }

    /**
     * 建连并等 onOpen。Colab runtime proxy 在 kernel 刚建好时偶尔会拒绝/502，
     * 因此最多重试 [WS_CONNECT_ATTEMPTS] 次（只在 onOpen 之前失败时重试；
     * 已连上后断开不重试，避免重复执行）。
     */
    private suspend fun connect(url: String): WsConnection {
        var lastError: Throwable? = null
        var attempt = 1
        while (attempt <= WS_CONNECT_ATTEMPTS) {
            val events = Channel<WsEvent>(Channel.UNLIMITED)
            val request = Request.Builder().url(url).headers(wsHeaders()).build()
            val socket = httpClient.newWebSocket(request, socketListener(events))
            val opened = try {
                withTimeoutOrNull(WS_CONNECT_TIMEOUT_MS) { events.receive() }
            } catch (e: CancellationException) {
                // 外层 execute 超时/调用方取消：把还在建的 socket 一起关掉，避免泄漏。
                socket.cancel()
                events.close()
                throw e
            }
            when (opened) {
                is WsEvent.Opened -> return WsConnection(socket, events)
                is WsEvent.Failure -> {
                    socket.cancel()
                    events.close()
                    lastError = opened.error
                }

                is WsEvent.Closed -> {
                    socket.cancel()
                    events.close()
                    lastError = ColabProtocolException("WebSocket 在打开前被关闭（code=${opened.code}）")
                }

                else -> {
                    socket.cancel()
                    events.close()
                    lastError = ColabProtocolException(
                        if (opened == null) "WebSocket 连接超时（${WS_CONNECT_TIMEOUT_MS / 1000}s）" else "WebSocket 状态异常",
                    )
                }
            }
            attempt++
            if (attempt <= WS_CONNECT_ATTEMPTS) delay(WS_CONNECT_RETRY_DELAY_MS * (attempt - 1))
        }
        throw ColabProtocolException(
            "无法连接 Kernel WebSocket（已尝试 $WS_CONNECT_ATTEMPTS 次）：${lastError?.message}",
            lastError,
        )
    }

    private fun socketListener(events: Channel<WsEvent>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            events.trySend(WsEvent.Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(WsEvent.Text(text))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            events.trySend(WsEvent.Failure(t))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(WsEvent.Closed(code, reason))
        }
    }

    // ----------------------------------------------------------- 内部/测试可见

    internal fun kernelsUrl(): HttpUrl = runtimeUrl.toHttpUrl().newBuilder()
        .addPathSegments("api/kernels")
        .build()

    internal fun kernelUrl(kernelId: String): HttpUrl = runtimeUrl.toHttpUrl().newBuilder()
        .addPathSegments("api/kernels/$kernelId")
        .build()

    /** kernel channels 的 https URL（OkHttp 的 HttpUrl 不支持 ws/wss scheme）。 */
    internal fun channelsWsUrl(kernelId: String, sessionId: String): HttpUrl =
        runtimeUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kernels/$kernelId/channels")
            .addQueryParameter("session_id", sessionId)
            .addQueryParameter("token", proxyToken)
            .build()

    /**
     * WebSocket 连接用的 `ws(s)://` URL 字符串。OkHttp 的 `Request.Builder().url(String)`
     * 会把 `ws:`/`wss:` 自动映射成 `http:`/`https:`（见 OkHttp 4.x 实现）。
     */
    internal fun channelsWebSocketUrl(kernelId: String, sessionId: String): String {
        val httpUrl = channelsWsUrl(kernelId, sessionId)
        return httpUrl.toString().replaceFirst("https:", "wss:").replaceFirst("http:", "ws:")
    }

    /** 构造 Jupyter execute_request（整条 JSON 文本），字段与 Python 参考实现一致。 */
    internal fun buildExecuteRequest(
        code: String,
        msgId: String,
        sessionId: String,
        username: String = USERNAME,
    ): String {
        val header = JSONObject()
            .put("msg_id", msgId)
            .put("username", username)
            .put("session", sessionId)
            .put("date", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            .put("msg_type", "execute_request")
            .put("version", PROTOCOL_VERSION)
        val content = JSONObject()
            .put("code", code)
            .put("silent", false)
            .put("store_history", true)
            .put("user_expressions", JSONObject())
            .put("allow_stdin", false)
            .put("stop_on_error", true)
        return JSONObject()
            .put("header", header)
            .put("parent_header", JSONObject())
            .put("metadata", JSONObject())
            .put("content", content)
            .put("channel", "shell")
            .put("buffers", JSONArray())
            .toString()
    }

    // ------------------------------------------------------------------ HTTP

    private fun httpHeaders(): Headers = Headers.Builder()
        .add("Authorization", "Bearer $proxyToken")
        .add("Accept", "application/json")
        .add("X-Colab-Client-Agent", "colab-cli")
        .add("X-Colab-Runtime-Proxy-Token", proxyToken)
        .build()

    private fun wsHeaders(): Headers = Headers.Builder()
        .add("X-Colab-Client-Agent", "colab-cli")
        .add("X-Colab-Runtime-Proxy-Token", proxyToken)
        .build()

    private suspend fun executeHttp(request: Request): Pair<Int, String> {
        val response = httpClient.awaitResponse(request)
        response.use { resp ->
            val body = try {
                resp.body?.string().orEmpty()
            } catch (e: java.io.IOException) {
                throw ColabProtocolException("读取 runtime 响应失败：${request.method} ${request.url} — ${e.message}", e)
            }
            return resp.code to body
        }
    }

    companion object {
        internal const val DEFAULT_EXECUTE_TIMEOUT_MS = 10 * 60 * 1000L
        internal const val PROTOCOL_VERSION = "5.3"
        internal const val USERNAME = "anima"

        private const val WS_CONNECT_ATTEMPTS = 3
        private const val WS_CONNECT_TIMEOUT_MS = 30_000L
        private const val WS_CONNECT_RETRY_DELAY_MS = 1_000L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        internal fun newSessionId(): String = UUID.randomUUID().toString().replace("-", "")

        internal fun newMessageId(): String = UUID.randomUUID().toString().replace("-", "")

        internal fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS) // WS 保活：部署脚本可能几分钟无输出
            .build()
    }
}

// ---------------------------------------------------------------- 消息解析

/** WS 收到的一条 Jupyter 消息（只保留 execute 需要的字段）。 */
internal data class KernelWireMessage(
    val channel: String,
    val msgType: String,
    val parentMsgId: String,
    val content: JSONObject,
)

/**
 * 解析 Jupyter WebSocket 默认协议的 JSON 文本帧。
 * 非 JSON / 缺 header / 缺 msg_type 时返回 null（未知消息直接忽略）。
 */
internal fun parseWireMessage(text: String): KernelWireMessage? {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val header = root.optJSONObject("header") ?: return null
    val msgType = header.optString("msg_type")
    if (msgType.isBlank()) return null
    val parentMsgId = root.optJSONObject("parent_header")?.optString("msg_id").orEmpty()
    val content = root.optJSONObject("content") ?: JSONObject()
    val channel = root.optString("channel").ifBlank { inferChannel(msgType) }
    return KernelWireMessage(
        channel = channel,
        msgType = msgType,
        parentMsgId = parentMsgId,
        content = content,
    )
}

/** 默认协议下服务端不一定带 channel 字段，按 msg_type 推断（shell 的 *_reply vs iopub）。 */
internal fun inferChannel(msgType: String): String = when (msgType) {
    "execute_reply",
    "kernel_info_reply",
    "complete_reply",
    "inspect_reply",
    "history_reply",
    "is_complete_reply",
    "comm_info_reply",
    "shutdown_reply",
    -> "shell"

    else -> "iopub"
}

private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*m")

private fun formatKernelError(content: JSONObject): String {
    val ename = content.optString("ename", "Error")
    val evalue = content.optString("evalue")
    val traceback = content.optJSONArray("traceback")
    val tracebackText = buildString {
        if (traceback != null) {
            for (i in 0 until traceback.length()) {
                val line = traceback.optString(i).replace(ANSI_ESCAPE, "")
                if (line.isNotBlank()) append(line).append('\n')
            }
        }
    }.trim()
    return buildString {
        append(ename)
        if (evalue.isNotBlank()) append(": ").append(evalue)
        if (tracebackText.isNotBlank()) {
            append('\n').append(tracebackText)
        }
    }
}

private fun formatExecuteReplyError(content: JSONObject): String {
    val ename = content.optString("ename")
    val evalue = content.optString("evalue")
    return when {
        ename.isNotBlank() && evalue.isNotBlank() -> "执行失败：$ename: $evalue"
        ename.isNotBlank() -> "执行失败：$ename"
        else -> "执行失败：kernel 返回 execute_reply.status=${content.optString("status", "error")}"
    }
}

private fun bodySnippet(body: String): String =
    if (body.isBlank()) "" else "；响应摘要：${body.take(400)}"
