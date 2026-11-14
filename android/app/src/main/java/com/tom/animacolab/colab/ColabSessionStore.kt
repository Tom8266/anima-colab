package com.tom.animacolab.colab

import android.content.Context
import com.tom.animacolab.data.Pairing

/** 一键模式持久化的 Colab 会话状态（App 重启后可复用） */
class ColabSessionStore(context: Context) {
    private val sp = context.getSharedPreferences("colab_session", Context.MODE_PRIVATE)

    var endpoint: String
        get() = sp.getString("endpoint", "").orEmpty()
        set(v) = sp.edit().putString("endpoint", v).apply()

    var kernelId: String
        get() = sp.getString("kernel_id", "").orEmpty()
        set(v) = sp.edit().putString("kernel_id", v).apply()

    var runtimeUrl: String
        get() = sp.getString("runtime_url", "").orEmpty()
        set(v) = sp.edit().putString("runtime_url", v).apply()

    var proxyToken: String
        get() = sp.getString("proxy_token", "").orEmpty()
        set(v) = sp.edit().putString("proxy_token", v).apply()

    var proxyExpiresAtMs: Long
        get() = sp.getLong("proxy_expires_at", 0L)
        set(v) = sp.edit().putLong("proxy_expires_at", v).apply()

    var pairUrl: String
        get() = sp.getString("pair_url", "").orEmpty()
        set(v) = sp.edit().putString("pair_url", v).apply()

    var pairToken: String
        get() = sp.getString("pair_token", "").orEmpty()
        set(v) = sp.edit().putString("pair_token", v).apply()

    var startedAtMs: Long
        get() = sp.getLong("started_at", 0L)
        set(v) = sp.edit().putLong("started_at", v).apply()

    val hasSession: Boolean get() = endpoint.isNotBlank()

    val hasPair: Boolean get() = pairUrl.isNotBlank() && pairToken.isNotBlank()

    fun existingPair(): Pairing.Pair? =
        if (hasPair) Pairing.Pair(pairUrl, pairToken) else null

    fun clear() = sp.edit().clear().apply()
}
