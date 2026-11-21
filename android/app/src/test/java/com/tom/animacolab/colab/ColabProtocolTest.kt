package com.tom.animacolab.colab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * 协议层纯 JVM 单测：不联网、不碰 Android API。
 * 覆盖 notebookHash / XSSI 前缀 / Assignment JSON 解析 / URL 构造 /
 * Jupyter execute_request 构造 / WS 消息解析。
 */
class ColabProtocolTest {

    // ---------------------------------------------------------- notebookHash

    @Test
    fun notebookHashMatchesPythonRule() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val hash = ColabApi.notebookHash(uuid)
        assertEquals("123e4567_e89b_12d3_a456_426614174000........", hash)
        assertEquals(44, hash.length)
        assertFalse(hash.contains('-'))
        // 尾部补齐一定是 8 个 '.'
        assertEquals(8, hash.count { it == '.' })
    }

    @Test
    fun notebookHashIsStableAndUnique() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertEquals(ColabApi.notebookHash(a), ColabApi.notebookHash(a))
        assertEquals(44, ColabApi.notebookHash(b).length)
        assertFalse(ColabApi.notebookHash(a) == ColabApi.notebookHash(b))
    }

    // ------------------------------------------------------------- XSSI

    @Test
    fun stripsXssiPrefix() {
        val body = ")]}'\n{\"assignments\":[]}"
        assertEquals("{\"assignments\":[]}", stripXssiPrefix(body))
    }

    @Test
    fun keepsBodyWithoutXssiPrefix() {
        val body = "{\"ok\":true}"
        assertEquals(body, stripXssiPrefix(body))
    }

    @Test
    fun handlesEmptyAndPrefixOnlyBodies() {
        assertEquals("", stripXssiPrefix(""))
        assertEquals("", stripXssiPrefix(")]}'"))
        assertEquals("", stripXssiPrefix(")]}'\n"))
        // CRLF 变体也能处理
        assertEquals("{\"a\":1}", stripXssiPrefix(")]}'\r\n{\"a\":1}"))
    }

    // ------------------------------------------------ Assignment JSON 解析

    private val listedAssignmentJson = """
        {"assignments":[
          {"endpoint":"gpu-t4-s-usw1b2-abc","variant":1,"machineShape":0,"accelerator":"T4",
           "runtimeProxyInfo":{"token":"proxy-token-123","tokenExpiresInSeconds":3600,
             "url":"https://8080-gpu-t4-s-usw1b2-abc.us-west1.prod.r.colab.dev"}}
        ]}
    """.trimIndent()

    @Test
    fun parsesListedAssignment() {
        val root = JSONObject(listedAssignmentJson)
        val items = root.getJSONArray("assignments")
        assertEquals(1, items.length())
        val assignment = parseAssignmentInfo(items.getJSONObject(0))
        assertEquals("gpu-t4-s-usw1b2-abc", assignment.endpoint)
        assertEquals("T4", assignment.accelerator)
        assertEquals(1, assignment.variant)
        assertEquals(0, assignment.machineShape)
        assertEquals("proxy-token-123", assignment.runtimeProxy.token)
        assertEquals(3600, assignment.runtimeProxy.tokenExpiresInSeconds)
        assertEquals(
            "https://8080-gpu-t4-s-usw1b2-abc.us-west1.prod.r.colab.dev",
            assignment.runtimeProxy.url,
        )
    }

    @Test
    fun parsesPostAssignmentResponseWithoutMachineShape() {
        // assign POST 返回的 Assignment 可能不带 machineShape/accelerator，需要回退值
        val json = JSONObject(
            """
            {"endpoint":"gpu-t4-s-xyz","variant":1,
             "runtimeProxyInfo":{"token":"t0k","tokenExpiresInSeconds":3600,
               "url":"https://8080-gpu-t4-s-xyz.prod.r.colab.dev"}}
            """.trimIndent(),
        )
        val assignment = parseAssignmentInfo(json, fallbackAccelerator = "T4")
        assertEquals("T4", assignment.accelerator)
        assertEquals(0, assignment.machineShape)
        assertEquals(1, assignment.variant)
    }

    @Test
    fun rejectsAssignmentWithoutRuntimeProxy() {
        val json = JSONObject("""{"endpoint":"e","accelerator":"T4"}""")
        val error = runCatching { parseAssignmentInfo(json) }.exceptionOrNull()
        assertTrue(error is ColabProtocolException)
        assertTrue(error!!.message!!.contains("runtimeProxyInfo"))
    }

    // ----------------------------------------------------------- URL 构造

    @Test
    fun buildsAssignmentsUrl() {
        val api = ColabApi(accessToken = { "token" })
        assertEquals(
            "https://colab.research.google.com/tun/m/assignments?authuser=0",
            api.assignmentsUrl(),
        )
    }

    @Test
    fun buildsAssignUrlWithExpectedParams() {
        val api = ColabApi(accessToken = { "token" })
        val hash = ColabApi.notebookHash(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
        val url = api.assignUrl(hash)
        assertTrue(url.startsWith("https://colab.research.google.com/tun/m/assign?"))
        assertTrue(url.contains("nbh=$hash"))
        assertTrue(url.contains("variant=GPU"))
        assertTrue(url.contains("accelerator=T4"))
        assertTrue(url.endsWith("authuser=0"))
    }

    @Test
    fun buildsAssignUrlForTpu() {
        val api = ColabApi(accessToken = { "token" })
        val url = api.assignUrl("abc", ColabAccelerator.TPU_V5E1)
        assertTrue(url.contains("variant=TPU"))
        assertTrue(url.contains("accelerator=V5E1"))
    }

    @Test
    fun acceleratorMatchingUsesNameAndVariant() {
        assertTrue(ColabAccelerator.T4.matches("T4", 1))
        assertTrue(ColabAccelerator.T4.matches("t4", null))
        assertFalse(ColabAccelerator.T4.matches("V5E1", 2))
        assertTrue(ColabAccelerator.TPU_V5E1.matches("V5E1", 2))
        assertFalse(ColabAccelerator.TPU_V5E1.matches("V5E1", 1))
        assertEquals(ColabAccelerator.TPU_V5E1, ColabAccelerator.fromId("tpu-v5e1"))
        assertEquals(ColabAccelerator.T4, ColabAccelerator.fromId("???"))
        assertEquals(ColabAccelerator.TPU_V5E1, ColabAccelerator.fromApi("V5E1", 2))
        assertNull(ColabAccelerator.fromApi("T4", 2))
    }

    @Test
    fun buildsUnassignUrl() {
        val api = ColabApi(accessToken = { "token" })
        assertEquals(
            "https://colab.research.google.com/tun/m/unassign/gpu-t4-s-abc?authuser=0",
            api.unassignUrl("gpu-t4-s-abc"),
        )
    }

    @Test
    fun respectsCustomBaseUrl() {
        val api = ColabApi(accessToken = { "token" }, baseUrl = "https://colab.example.test/")
        assertEquals("https://colab.example.test/tun/m/assignments?authuser=0", api.assignmentsUrl())
    }

    // ------------------------------------------- Jupyter 客户端 URL 构造

    private fun kernelClient() = JupyterKernelClient(
        runtimeUrl = "https://8080-gpu-t4-s-abc-b.us-west1.prod.r.colab.dev/",
        proxyToken = "proxy.token-123",
    )

    @Test
    fun buildsHttpKernelUrls() {
        val client = kernelClient()
        assertEquals(
            "https://8080-gpu-t4-s-abc-b.us-west1.prod.r.colab.dev/api/kernels",
            client.kernelsUrl().toString(),
        )
        assertEquals(
            "https://8080-gpu-t4-s-abc-b.us-west1.prod.r.colab.dev/api/kernels/kid-1",
            client.kernelUrl("kid-1").toString(),
        )
    }

    @Test
    fun buildsWsChannelsUrl() {
        val client = kernelClient()
        val url = client.channelsWsUrl(kernelId = "kid-1", sessionId = "abc123")
        assertEquals("https", url.scheme)
        assertEquals("8080-gpu-t4-s-abc-b.us-west1.prod.r.colab.dev", url.host)
        assertEquals("/api/kernels/kid-1/channels", url.encodedPath)
        assertEquals("abc123", url.queryParameter("session_id"))
        assertEquals("proxy.token-123", url.queryParameter("token"))

        val wsUrl = client.channelsWebSocketUrl(kernelId = "kid-1", sessionId = "abc123")
        assertTrue(wsUrl.startsWith("wss://8080-gpu-t4-s-abc-b.us-west1.prod.r.colab.dev/api/kernels/kid-1/channels?"))
        assertTrue(wsUrl.contains("session_id=abc123"))
        assertTrue(wsUrl.contains("token=proxy.token-123"))
    }

    @Test
    fun wsUrlKeepsQueryTokenEncoded() {
        val client = JupyterKernelClient(
            runtimeUrl = "http://127.0.0.1:8080",
            proxyToken = "a/b+c=d e",
        )
        val wsUrl = client.channelsWebSocketUrl("k", "s")
        assertTrue(wsUrl.startsWith("ws://127.0.0.1:8080/api/kernels/k/channels?"))
        // token 里的特殊字符必须被 URL 编码，避免 query 被截断
        assertTrue(wsUrl.contains("token=a%2Fb%2Bc%3Dd%20e"))
        assertEquals("a/b+c=d e", client.channelsWsUrl("k", "s").queryParameter("token"))
    }

    // ------------------------------------------------- execute_request 构造

    @Test
    fun buildsExecuteRequestPerJupyterProtocol() {
        val client = kernelClient()
        val payload = client.buildExecuteRequest(
            code = "print('HELLO123')",
            msgId = "req-msg-id",
            sessionId = "session-123",
        )
        val json = JSONObject(payload)

        val header = json.getJSONObject("header")
        assertEquals("execute_request", header.getString("msg_type"))
        assertEquals("req-msg-id", header.getString("msg_id"))
        assertEquals("anima", header.getString("username"))
        assertEquals("session-123", header.getString("session"))
        assertEquals("5.3", header.getString("version"))
        // date 必须是能解析的 ISO8601
        assertNotNull(Instant.parse(header.getString("date")))

        assertTrue(json.getJSONObject("parent_header").length() == 0)
        assertTrue(json.getJSONObject("metadata").length() == 0)

        val content = json.getJSONObject("content")
        assertEquals("print('HELLO123')", content.getString("code"))
        assertFalse(content.getBoolean("silent"))
        assertTrue(content.getBoolean("store_history"))
        assertFalse(content.getBoolean("allow_stdin"))
        assertTrue(content.getBoolean("stop_on_error"))
        assertTrue(content.getJSONObject("user_expressions").length() == 0)

        assertEquals("shell", json.getString("channel"))
        assertEquals(0, json.getJSONArray("buffers").length())
    }

    // ------------------------------------------------- WS 消息解析

    @Test
    fun parsesStreamMessage() {
        val text = """
            {"header":{"msg_id":"m1","msg_type":"stream","version":"5.3"},
             "parent_header":{"msg_id":"req-1"},
             "metadata":{},
             "content":{"name":"stdout","text":"HELLO123\n"},
             "buffers":[],"channel":"iopub"}
        """.trimIndent()
        val message = parseWireMessage(text)!!
        assertEquals("stream", message.msgType)
        assertEquals("req-1", message.parentMsgId)
        assertEquals("iopub", message.channel)
        assertEquals("stdout", message.content.getString("name"))
        assertEquals("HELLO123\n", message.content.getString("text"))
    }

    @Test
    fun parsesStatusIdleAndInfersIopubChannel() {
        val text = """
            {"header":{"msg_id":"m2","msg_type":"status"},
             "parent_header":{"msg_id":"req-2"},
             "metadata":{},"content":{"execution_state":"idle"},"buffers":[]}
        """.trimIndent()
        val message = parseWireMessage(text)!!
        assertEquals("status", message.msgType)
        assertEquals("idle", message.content.getString("execution_state"))
        assertEquals("iopub", inferChannel(message.msgType))
    }

    @Test
    fun parsesExecuteReplyAndInfersShellChannel() {
        val text = """
            {"header":{"msg_id":"m3","msg_type":"execute_reply"},
             "parent_header":{"msg_id":"req-3"},
             "metadata":{},"content":{"status":"ok","execution_count":1},"buffers":[]}
        """.trimIndent()
        val message = parseWireMessage(text)!!
        assertEquals("execute_reply", message.msgType)
        assertEquals("ok", message.content.getString("status"))
        assertEquals("shell", inferChannel(message.msgType))
    }

    @Test
    fun parsesErrorOutputWithTraceback() {
        val text = """
            {"header":{"msg_id":"m4","msg_type":"error"},
             "parent_header":{"msg_id":"req-4"},
             "content":{"ename":"RuntimeError","evalue":"boom",
                        "traceback":["Traceback (most recent call last):","RuntimeError: boom"]},
             "buffers":[],"channel":"iopub"}
        """.trimIndent()
        val message = parseWireMessage(text)!!
        assertEquals("error", message.msgType)
        assertEquals("RuntimeError", message.content.getString("ename"))
        assertEquals(2, message.content.getJSONArray("traceback").length())
    }

    @Test
    fun ignoresMalformedWireMessages() {
        assertNull(parseWireMessage("not json"))
        assertNull(parseWireMessage("{}"))
        assertNull(parseWireMessage("""{"header":{}}"""))
        assertNull(parseWireMessage("""[1,2,3]"""))
    }

    @Test
    fun ignoresMessagesWithoutParentHeader() {
        // 服务端主动消息（如 kernel_info_reply）没有 parent header，parent_msg_id 为空
        val text = """{"header":{"msg_id":"x","msg_type":"kernel_info_reply"},"content":{}}"""
        val message = parseWireMessage(text)!!
        assertEquals("", message.parentMsgId)
    }

    @Test
    fun infersChannelsForKnownReplies() {
        assertEquals("shell", inferChannel("execute_reply"))
        assertEquals("shell", inferChannel("kernel_info_reply"))
        assertEquals("iopub", inferChannel("stream"))
        assertEquals("iopub", inferChannel("status"))
        assertEquals("iopub", inferChannel("display_data"))
        assertEquals("iopub", inferChannel("execute_input"))
    }

    // ------------------------------------------------------- 简单参数校验

    @Test
    fun rejectsBlankCredentials() {
        assertTrue(
            runCatching { JupyterKernelClient("", "token") }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { JupyterKernelClient("https://x.colab.dev", " ") }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun exceptionExposesStatusCode() {
        val e = ColabProtocolException("boom", statusCode = 412)
        assertEquals(412, e.statusCode)
        assertEquals(false, e.isRetryable())
        assertEquals(true, ColabProtocolException("net", statusCode = null).isRetryable())
        assertEquals(true, ColabProtocolException("busy", statusCode = 503).isRetryable())
    }
}
