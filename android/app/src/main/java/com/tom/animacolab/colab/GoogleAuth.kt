package com.tom.animacolab.colab

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Google OAuth token 的本地存储（App 私有 SharedPreferences） */
class GoogleAuthStore(context: Context) {
    private val sp = context.getSharedPreferences("colab_google_auth", Context.MODE_PRIVATE)

    var refreshToken: String
        get() = sp.getString("refresh_token", "").orEmpty()
        set(v) = sp.edit().putString("refresh_token", v).apply()

    var accessToken: String
        get() = sp.getString("access_token", "").orEmpty()
        set(v) = sp.edit().putString("access_token", v).apply()

    var accessExpiresAtMs: Long
        get() = sp.getLong("access_expires_at", 0L)
        set(v) = sp.edit().putLong("access_expires_at", v).apply()

    var account: String
        get() = sp.getString("account", "").orEmpty()
        set(v) = sp.edit().putString("account", v).apply()

    var pendingVerifier: String
        get() = sp.getString("pending_verifier", "").orEmpty()
        set(v) = sp.edit().putString("pending_verifier", v).apply()

    var pendingState: String
        get() = sp.getString("pending_state", "").orEmpty()
        set(v) = sp.edit().putString("pending_state", v).apply()

    val loggedIn: Boolean get() = refreshToken.isNotBlank()

    fun saveTokens(access: String, refresh: String?, expiresInSeconds: Long) {
        sp.edit().apply {
            putString("access_token", access)
            putLong("access_expires_at", System.currentTimeMillis() + expiresInSeconds * 1000)
            if (!refresh.isNullOrBlank()) putString("refresh_token", refresh)
        }.apply()
    }

    fun clear() = sp.edit().clear().apply()
}

class GoogleAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Google OAuth（沿用 google-colab-cli 的 installed-app client 与 remote copy-paste 授权流程）。
 *
 * 流程：buildAuthUrl() 生成带 PKCE 的授权链接 → 用户在浏览器批准 →
 * Google 在 sdk.cloud.google.com 落地页显示授权码 → 用户复制回 App →
 * [exchangeCode] 换取 access/refresh token → 之后 [accessToken] 自动刷新。
 */
class GoogleAuth(private val store: GoogleAuthStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isLoggedIn(): Boolean = store.loggedIn

    /** 生成授权 URL，同时把 PKCE verifier/state 存起来给 exchangeCode 用 */
    fun buildAuthUrl(): String {
        val verifier = randomVerifier()
        val challenge = codeChallenge(verifier)
        val state = randomState()
        store.pendingVerifier = verifier
        store.pendingState = state
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES.joinToString(" "),
            "access_type" to "offline",
            "include_granted_scopes" to "true",
            "prompt" to "consent",
            "token_usage" to "remote",
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        return AUTH_URI + "?" + params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    /** 用授权码换 token；成功返回 true */
    suspend fun exchangeCode(rawCode: String): Boolean = withContext(Dispatchers.IO) {
        val code = extractCode(rawCode)
        if (code.isBlank()) throw GoogleAuthException("没有找到授权码，请粘贴 Google 页面上显示的完整代码")
        val verifier = store.pendingVerifier
        if (verifier.isBlank()) throw GoogleAuthException("登录会话已过期，请重新点「登录 Google」")
        val body = FormBody.Builder()
            .add("code", code)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("redirect_uri", REDIRECT_URI)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder().url(TOKEN_URI).post(body).build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw GoogleAuthException("授权失败（HTTP ${resp.code}）：${oauthError(text)}")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token")
                if (access.isBlank()) throw GoogleAuthException("授权失败：响应里没有 access_token")
                store.saveTokens(access, json.optString("refresh_token").ifBlank { null }, json.optLong("expires_in", 3600))
                store.pendingVerifier = ""
                store.pendingState = ""
                true
            }
        } catch (e: GoogleAuthException) {
            throw e
        } catch (e: Exception) {
            throw GoogleAuthException("无法连接 Google 授权服务：${e.message ?: "网络错误"}（需要能访问 Google 的网络/代理）", e)
        }
    }

    /** 取一个可用的 access token（过期前自动刷新） */
    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (store.accessToken.isNotBlank() && store.accessExpiresAtMs - now > 2 * 60 * 1000) {
            return@withContext store.accessToken
        }
        val refresh = store.refreshToken
        if (refresh.isBlank()) throw GoogleAuthException("还没有登录 Google 账号")
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url(TOKEN_URI).post(body).build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (text.contains("invalid_grant")) {
                        store.clear()
                        throw GoogleAuthException("Google 登录已失效，请重新登录")
                    }
                    throw GoogleAuthException("刷新 Google 登录失败（HTTP ${resp.code}）：${oauthError(text)}")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token")
                if (access.isBlank()) throw GoogleAuthException("刷新登录失败：没有 access_token")
                store.saveTokens(access, null, json.optLong("expires_in", 3600))
                access
            }
        } catch (e: GoogleAuthException) {
            throw e
        } catch (e: Exception) {
            throw GoogleAuthException("无法连接 Google（需要能访问 Google 的网络/代理）：${e.message ?: "网络错误"}", e)
        }
    }

    fun logout() = store.clear()

    private fun oauthError(text: String): String = runCatching {
        val o = JSONObject(text)
        listOf(o.optString("error"), o.optString("error_description")).filter { it.isNotBlank() }.joinToString(" - ")
    }.getOrDefault(text.take(200))

    companion object {
        // google-colab-cli 内置的公开 installed-app OAuth client（与 CLI 完全一致）
        const val CLIENT_ID = "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com"
        const val CLIENT_SECRET = "d-FL95Q19q7MQmFpd7hHD0Ty"
        const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
        const val TOKEN_URI = "https://oauth2.googleapis.com/token"
        const val REDIRECT_URI = "https://sdk.cloud.google.com/applicationdefaultauthcode.html"

        val SCOPES = listOf(
            "openid",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/colaboratory",
            "https://www.googleapis.com/auth/drive.file",
        )

        private const val VERIFIER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        fun randomVerifier(): String {
            val random = SecureRandom()
            return buildString { repeat(96) { append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.length)]) } }
        }

        fun codeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun randomState(): String = java.util.UUID.randomUUID().toString().replace("-", "")

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** 用户可能把带说明文字的一段内容都复制回来，尽量提取 code 参数或裸授权码 */
        fun extractCode(raw: String): String {
            val text = raw.trim()
            if (text.isEmpty()) return ""
            val m = Regex("[?&]code=([^&\\s]+)").find(text)
            if (m != null) return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
            val candidate = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .firstOrNull { it.length in 10..2048 && !it.contains(" ") && !it.startsWith("http") && !it.startsWith("PAIR") }
            return candidate.orEmpty()
        }

        fun verifyRedirect(url: String): Boolean = url.startsWith(REDIRECT_URI)
    }
}
