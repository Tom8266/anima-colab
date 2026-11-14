package com.tom.animacolab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

/** ComfyUI 网关 HTTP/WS 客户端（接口契约 §4） */
class ComfyClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---------------- HTTP ----------------

    suspend fun health(baseUrl: String, token: String): Health = request(baseUrl, token, "GET", "/anima/v1/health", null) { parseHealth(it) }

    suspend fun info(baseUrl: String, token: String): ServerInfo = request(baseUrl, token, "GET", "/anima/v1/info", null) { parseInfo(it) }

    suspend fun submit(baseUrl: String, token: String, graph: JSONObject, clientId: String): String {
        val body = JSONObject().put("prompt", graph).put("client_id", clientId)
        val resp = request(baseUrl, token, "POST", "/prompt", body.toString()) { it }
        val pid = resp.optString("prompt_id")
        if (pid.isBlank()) throw ApiException("提交失败：服务器没有返回 prompt_id")
        return pid
    }

    suspend fun queue(baseUrl: String, token: String): QueueStatus =
        request(baseUrl, token, "GET", "/anima/v1/queue", null) {
            QueueStatus(it.optJSONArray("running")?.length() ?: 0, it.optJSONArray("pending")?.length() ?: 0)
        }

    suspend fun clearQueue(baseUrl: String, token: String) {
        request(baseUrl, token, "POST", "/anima/v1/queue/clear", "{}") { it }
    }

    suspend fun interrupt(baseUrl: String, token: String) {
        request(baseUrl, token, "POST", "/anima/v1/interrupt", "{}") { it }
    }

    suspend fun history(baseUrl: String, token: String, limit: Int = 50): List<HistoryItem> =
        request(baseUrl, token, "GET", "/anima/v1/history?limit=$limit", null) { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseHistoryItem(it) } }
        }

    suspend fun historyItem(baseUrl: String, token: String, promptId: String): HistoryItem? =
        try {
            request(baseUrl, token, "GET", "/anima/v1/history/$promptId", null) { parseHistoryItem(it) }
        } catch (e: ApiException) {
            null
        }

    suspend fun imageBytes(baseUrl: String, token: String, ref: ImageRef): ByteArray =
        getBytes(viewUrl(baseUrl, ref), token)

    suspend fun getBytes(absoluteUrl: String, token: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(absoluteUrl)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("图片下载失败 HTTP ${resp.code}")
                resp.body?.bytes() ?: throw ApiException("图片下载失败：响应为空")
            }
        } catch (e: IOException) {
            throw ApiException("图片下载失败：${e.message ?: "网络错误"}", e)
        }
    }

    // ---------------- WebSocket ----------------

    fun openSocket(baseUrl: String, token: String, clientId: String, onEvent: (JSONObject) -> Unit, onClosed: (String?) -> Unit): WebSocket {
        val wsUrl = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> "wss://$baseUrl"
        } + "/ws?clientId=" + URLEncoder.encode(clientId, "UTF-8")
        val req = Request.Builder().url(wsUrl)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        return http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.getOrNull()?.let(onEvent)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onClosed("连接关闭")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                onClosed(t.message ?: "WebSocket 连接失败")
            }
        })
    }

    // ---------------- 解析 ----------------

    private suspend fun <T> request(
        baseUrl: String,
        token: String,
        method: String,
        path: String,
        body: String?,
        parse: (JSONObject) -> T,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + path)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMedia))
            else -> builder.method(method, null)
        }
        try {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw buildError(resp.code, text)
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw ApiException("服务器返回了无法解析的内容", null)
                parse(obj)
            }
        } catch (e: IOException) {
            throw ApiException("连不上 Colab 服务：${e.message ?: "网络错误"}", e)
        }
    }

    private fun buildError(code: Int, text: String): ApiException {
        val detail = runCatching {
            val o = JSONObject(text)
            when {
                o.has("node_errors") && o.getJSONObject("node_errors").length() > 0 -> {
                    val keys = o.getJSONObject("node_errors").keys().asSequence().joinToString(",")
                    "节点校验失败：$keys"
                }
                o.optString("error").isNotBlank() -> o.optString("error")
                else -> text.take(200)
            }
        }.getOrElse { text.take(200) }
        val hint = when (code) {
            401 -> "token 无效或已过期，请重新粘贴 Colab 的配对信息"
            404 -> "接口不存在（服务端版本不匹配？）"
            502 -> "Colab 里的 ComfyUI 不可达：可能还在启动或会话已断开"
            else -> "HTTP $code"
        }
        return ApiException("$hint（$detail）")
    }

    private fun parseHealth(o: JSONObject): Health = Health(
        ok = o.optBoolean("ok", false),
        comfy = o.optBoolean("comfy", false),
        running = queueCount(o.optJSONObject("queue"), "running"),
        pending = queueCount(o.optJSONObject("queue"), "pending"),
        device = o.optString("device").ifBlank { null },
        vramTotalGb = o.optDouble("vram_total_gb", 0.0).takeIf { it > 0 },
    )

    /** 队列字段兼容整数与 prompt_id 数组两种形态 */
    private fun queueCount(queue: JSONObject?, key: String): Int {
        val value = queue?.opt(key) ?: return 0
        return when (value) {
            is JSONArray -> value.length()
            is Number -> value.toInt()
            else -> 0
        }
    }

    private fun parseInfo(o: JSONObject): ServerInfo {
        fun names(arrName: String): List<String> {
            val arr = o.optJSONArray(arrName) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }
        fun strings(arrName: String): List<String> {
            val arr = o.optJSONArray(arrName) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.opt(i)?.toString()?.takeIf { it.isNotBlank() } }
        }
        val defaults = o.optJSONObject("defaults")
        val health = o.optJSONObject("health")
        return ServerInfo(
            unets = names("unets"),
            loras = names("loras"),
            vaes = names("vaes"),
            textEncoders = names("text_encoders"),
            samplers = strings("samplers").ifEmpty { listOf("er_sde", "euler") },
            schedulers = strings("schedulers").ifEmpty { listOf("simple") },
            defaultUnet = defaults?.optString("unet")?.ifBlank { null },
            defaultVae = defaults?.optString("vae")?.ifBlank { null },
            defaultTextEncoder = defaults?.optString("text_encoder")?.ifBlank { null },
            device = o.optString("device").ifBlank { null },
            vramTotalGb = o.optDouble("vram_total_gb", 0.0).takeIf { it > 0 },
            queue = QueueStatus(health?.optInt("running", 0) ?: 0, health?.optInt("pending", 0) ?: 0),
        )
    }

    private fun parseHistoryItem(o: JSONObject): HistoryItem {
        val images = o.optJSONArray("images")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    ImageRef(
                        filename = it.optString("filename"),
                        subfolder = it.optString("subfolder"),
                        type = it.optString("type", "output"),
                    )
                }
            }
        } ?: emptyList()
        val p = o.optJSONObject("params")?.let {
            HistoryParams(
                model = it.optString("model"),
                positive = it.optString("positive"),
                negative = it.optString("negative"),
                seed = it.optLong("seed", 0L),
                steps = it.optInt("steps", 0),
                cfg = it.optDouble("cfg", 0.0),
                sampler = it.optString("sampler"),
                scheduler = it.optString("scheduler"),
                width = it.optInt("width", 0),
                height = it.optInt("height", 0),
                batch = it.optInt("batch", 1),
            )
        }
        return HistoryItem(
            promptId = o.optString("prompt_id"),
            images = images,
            created = o.optLong("created", 0L),
            params = p,
        )
    }

    companion object {
        fun viewUrl(baseUrl: String, ref: ImageRef): String {
            val base = baseUrl.trimEnd('/')
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            return "$base/view?filename=${enc(ref.filename)}&subfolder=${enc(ref.subfolder)}&type=${enc(ref.type)}"
        }
    }
}
