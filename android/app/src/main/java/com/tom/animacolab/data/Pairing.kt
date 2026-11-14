package com.tom.animacolab.data

import org.json.JSONObject

/** 解析 Colab 笔记本打印的配对信息 */
object Pairing {
    data class Pair(val baseUrl: String, val token: String)

    /**
     * 支持这些形式（可整段粘贴）：
     *  - `PAIR: https://xxx.trycloudflare.com|token`
     *  - `https://xxx.trycloudflare.com|token`
     *  - 两行：第一行 URL，第二行 token
     *  - JSON：{"url":"https://...","token":"..."}
     */
    fun parse(raw: String): Pair? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        parseJson(text)?.let { return it }

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2 && lines[0].startsWith("http") && !lines[1].startsWith("http")) {
            return Pair(normalizeUrl(lines[0]), lines[1])
        }

        // 任意位置找 URL
        val urlMatch = Regex("https?://[^\\s|　]+").find(text) ?: return null
        val url = normalizeUrl(urlMatch.value)
        var token = ""
        val rest = text.removePrefix(urlMatch.value)
        token = when {
            rest.contains("|") -> rest.substringAfter("|").trim().substringBefore(" ").substringBefore("\n")
            rest.contains("token") -> rest.substringAfter("token").trimStart(':', '=', ' ', '"').trim().trim('"')
            lines.size >= 2 -> lines.last()
            else -> ""
        }
        token = token.trim().trim('"').trim()
        if (token.isBlank()) return null
        return Pair(url, token)
    }

    private fun parseJson(text: String): Pair? = runCatching {
        val obj = JSONObject(text)
        val url = obj.optString("url").ifBlank { obj.optString("baseUrl") }
        val token = obj.optString("token")
        if (url.isNotBlank() && token.isNotBlank()) Pair(normalizeUrl(url), token) else null
    }.getOrNull()

    private fun normalizeUrl(url: String): String =
        url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
}
