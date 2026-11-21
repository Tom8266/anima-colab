package com.tom.animacolab.colab

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Colab 协议层统一异常：HTTP 非 2xx、网络失败、JSON 结构不符等都会抛它。
 *
 * [statusCode] 是 HTTP 状态码（网络层失败时为 null），方便调用方区分
 * 401（需要重新 OAuth）和 412（同时分配的会话数已达上限）等情况。
 */
class ColabProtocolException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : RuntimeException(message, cause) {

    internal fun isRetryable(): Boolean =
        statusCode == null || statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504
}

/** Colab 运行时代理信息，用于直连该会话的 Jupyter kernel。 */
data class RuntimeProxyInfo(
    val url: String,
    val token: String,
    val tokenExpiresInSeconds: Int,
)

/** 一个已分配（或刚分配）的 Colab 会话。 */
data class AssignmentInfo(
    val endpoint: String,
    val accelerator: String,
    val variant: Int,
    val machineShape: Int,
    val runtimeProxy: RuntimeProxyInfo,
)

/**
 * Google Colab assignments API（`colab.research.google.com/tun/m`）的最小客户端。
 *
 * 纯 Kotlin/JVM 实现，只用 OkHttp + org.json + kotlinx-coroutines，**不依赖 Android API**，
 * 因此可以直接在 JVM 单测里跑。
 *
 * @param accessToken 每次请求时调用的 Google OAuth access token 提供者（由调用方负责刷新）。
 * @param baseUrl Colab 域名，测试时可替换。
 * @param httpClient 可选自定义 OkHttpClient（例如手机代理场景）。
 */
class ColabApi(
    private val accessToken: suspend () -> String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = newHttpClient(),
) {

    /** GET /tun/m/assignments —— 列出当前账号下所有 Colab 会话。 */
    suspend fun listAssignments(): List<AssignmentInfo> {
        val root = JSONObject(getText(assignmentsUrl()))
        val assignments = root.optJSONArray("assignments") ?: return emptyList()
        return buildList {
            for (i in 0 until assignments.length()) {
                val item = assignments.optJSONObject(i) ?: continue
                add(parseAssignmentInfo(item))
            }
        }
    }

    /**
     * 分配一个 Colab 会话（已分配则直接返回已有的）。
     *
     * 流程与 Python 参考实现一致：先 GET `/tun/m/assign` 拿 XSRF token（或发现已分配），
     * 再 POST 同一 URL 并带 `X-Goog-Colab-Token` 头。慢分配可能持续几十秒，
     * 客户端 readTimeout 默认 180s。遇到网络错误/502/503/504/429 会重试，
     * 每次重试前先重新 GET 一次以对账（避免重复分配）；412 直接失败。
     *
     * @param notebookHash [notebookHash] 生成值（web-safe base64 UUID）。
     * @param accelerator 加速卡档位，默认 T4；TPU 会申请 `variant=TPU&accelerator=V5E1`。
     */
    suspend fun assign(
        notebookHash: String,
        accelerator: ColabAccelerator = ColabAccelerator.T4,
    ): AssignmentInfo {
        require(notebookHash.isNotBlank()) { "notebookHash 不能为空" }
        val url = assignUrl(notebookHash, accelerator)
        var attempt = 1
        while (attempt <= MAX_ASSIGN_ATTEMPTS) {
            try {
                // 重试/首次都先 GET：既拿 xsrf，也用于对账（可能上一次 POST 成功但响应超时）。
                val getResponse = JSONObject(getText(url))
                parseExistingAssignment(getResponse, fallbackAccelerator = accelerator.apiName)?.let { return it }

                val xsrfToken = getResponse.optString("token")
                if (xsrfToken.isBlank()) {
                    throw ColabProtocolException(
                        "分配 Colab 会话失败：GET $url 的响应既不是已分配的 Assignment，也没有 XSRF token。" +
                            "响应摘要：${getResponse.toString().take(300)}",
                    )
                }
                val postResponse = JSONObject(postText(url, xsrfToken))
                return parseAssignmentInfo(postResponse, fallbackAccelerator = accelerator.apiName)
            } catch (e: ColabProtocolException) {
                if (e.statusCode == 412) {
                    throw ColabProtocolException(
                        "Colab 同时分配的会话数已达上限（HTTP 412），请先释放一个会话后重试。",
                        e,
                        statusCode = 412,
                    )
                }
                if (!e.isRetryable() || attempt >= MAX_ASSIGN_ATTEMPTS) throw e
                delay(ASSIGN_RETRY_DELAY_MS * attempt)
                attempt++
            }
        }
        throw ColabProtocolException("分配 Colab 会话失败：重试 $MAX_ASSIGN_ATTEMPTS 次后仍未成功")
    }

    /** 释放会话：GET `/tun/m/unassign/<endpoint>` 拿 XSRF token，再 POST 同一 URL。 */
    suspend fun unassign(endpoint: String) {
        require(endpoint.isNotBlank()) { "endpoint 不能为空" }
        val url = unassignUrl(endpoint)
        val body = getText(url)
        val xsrfToken = runCatching { JSONObject(body).optString("token") }.getOrDefault("")
        if (xsrfToken.isBlank()) {
            throw ColabProtocolException("释放 Colab 会话失败：GET $url 未返回 XSRF token（响应摘要：${body.take(200)}）")
        }
        postText(url, xsrfToken)
    }

    // ---------------------------------------------------------------- URLs

    private val rootUrl: String get() = baseUrl.trimEnd('/')

    internal fun assignmentsUrl(): String = "$rootUrl/tun/m/assignments?authuser=0"

    internal fun assignUrl(
        notebookHash: String,
        accelerator: ColabAccelerator = ColabAccelerator.T4,
    ): String = "$rootUrl/tun/m/assign?nbh=$notebookHash" +
        "&variant=${accelerator.variant}&accelerator=${accelerator.apiName}&authuser=0"

    internal fun unassignUrl(endpoint: String): String =
        "$rootUrl/tun/m/unassign/$endpoint?authuser=0"

    // --------------------------------------------------------------- HTTP

    private suspend fun commonHeaders(): Headers {
        val token = accessToken()
        if (token.isBlank()) throw ColabProtocolException("缺少 Google access token，无法调用 Colab API")
        return Headers.Builder()
            .add("Authorization", "Bearer $token")
            .add("Accept", "application/json")
            .add("X-Colab-Client-Agent", "colab-cli")
            .build()
    }

    private suspend fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .headers(commonHeaders())
            .get()
            .build()
        return requestText(request)
    }

    private suspend fun postText(url: String, xsrfToken: String): String {
        val request = Request.Builder()
            .url(url)
            .headers(commonHeaders().newBuilder().add("X-Goog-Colab-Token", xsrfToken).build())
            .post(EMPTY_BODY)
            .build()
        return requestText(request)
    }

    private suspend fun requestText(request: Request): String {
        val response = httpClient.awaitResponse(request)
        response.use { resp ->
            val body = try {
                resp.body?.string().orEmpty()
            } catch (e: IOException) {
                throw ColabProtocolException("读取 Colab 响应失败：${request.method} ${request.url} — ${e.message}", e)
            }
            if (!resp.isSuccessful) {
                val snippet = if (body.isBlank()) "" else "；响应摘要：${body.take(400)}"
                throw ColabProtocolException(
                    "Colab 请求失败：${request.method} ${request.url} → HTTP ${resp.code} ${resp.message}$snippet",
                    statusCode = resp.code,
                )
            }
            return stripXssiPrefix(body)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://colab.research.google.com"

        private const val MAX_ASSIGN_ATTEMPTS = 3
        private const val ASSIGN_RETRY_DELAY_MS = 5_000L

        /**
         * Colab 的 notebook hash：uuid 字符串里 `-` 换成 `_`，再在尾部补 `.` 到长度 44
         * （等价 Python `colab_cli.client.uuid_to_web_safe_base64`）。
         */
        fun notebookHash(uuid: UUID): String {
            val transformed = uuid.toString().replace('-', '_')
            return transformed + ".".repeat(44 - transformed.length)
        }

        internal fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

// ------------------------------------------------------------------ 内部工具

private const val XSSI_LOOKS_LIKE = ")]}'"

private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

/**
 * 剥掉 Google XSSI 保护前缀（响应文本形如 `)]}'\n{"assignments":...}`）。
 * 没有前缀时原样返回；只有前缀没有换行时返回空串。
 */
internal fun stripXssiPrefix(body: String): String {
    if (!body.startsWith(XSSI_LOOKS_LIKE)) return body
    val newline = body.indexOf('\n')
    return if (newline >= 0) body.substring(newline + 1) else ""
}

/**
 * 解析 Assignment JSON（`/tun/m/assignments` 的元素 / assign POST 的响应 /
 * assign GET 的“已分配”响应）。缺少 runtimeProxyInfo 或关键字段时抛异常。
 */
internal fun parseAssignmentInfo(json: JSONObject, fallbackAccelerator: String = "T4"): AssignmentInfo {
    val proxyJson = json.optJSONObject("runtimeProxyInfo")
        ?: throw ColabProtocolException(
            "Colab 响应缺少 runtimeProxyInfo 字段：${json.toString().take(300)}",
        )
    val endpoint = json.optString("endpoint")
    val url = proxyJson.optString("url")
    val token = proxyJson.optString("token")
    if (endpoint.isBlank() || url.isBlank() || token.isBlank()) {
        throw ColabProtocolException(
            "Colab Assignment 关键字段为空（endpoint/url/token）：${json.toString().take(300)}",
        )
    }
    return AssignmentInfo(
        endpoint = endpoint,
        accelerator = json.optString("accelerator").ifBlank { fallbackAccelerator },
        variant = json.optInt("variant", 1),
        machineShape = json.optInt("machineShape", 0),
        runtimeProxy = RuntimeProxyInfo(
            url = url,
            token = token,
            tokenExpiresInSeconds = proxyJson.optInt("tokenExpiresInSeconds", 0),
        ),
    )
}

/** assign GET 若返回的是“已分配”的 Assignment（含 runtimeProxyInfo）则直接解析，否则返回 null。 */
private fun parseExistingAssignment(json: JSONObject, fallbackAccelerator: String): AssignmentInfo? =
    if (json.has("runtimeProxyInfo")) parseAssignmentInfo(json, fallbackAccelerator) else null

/**
 * 把 OkHttp 的异步请求挂到协程上：网络失败 → [ColabProtocolException]；
 * 协程取消 → `call.cancel()`。返回的 [Response] 由调用方负责 close。
 */
internal suspend fun OkHttpClient.awaitResponse(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call: Call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        ColabProtocolException("网络请求失败：${request.method} ${request.url} — ${e.message}", e),
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }
