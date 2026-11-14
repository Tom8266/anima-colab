package com.tom.animacolab.colab

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 真实环境联调测试（默认跳过，不阻塞 CI）。
 *
 * 需要环境变量：
 *  - `COLAB_RUNTIME_URL`   会话 runtimeProxyInfo.url，如 https://8080-xxx.colab.dev
 *  - `COLAB_RUNTIME_TOKEN` 会话 runtimeProxyInfo.token
 *  - `COLAB_PROXY`         可选，HTTP 代理如 http://127.0.0.1:7890
 *  - `COLAB_ACCESS_TOKEN`  可选，Google OAuth access token，提供后额外跑 assignments API
 *
 * 跑法（注意用 --no-daemon，保证 test JVM 继承到当前 shell 的环境变量）：
 * ```
 * COLAB_RUNTIME_URL=... COLAB_RUNTIME_TOKEN=... ./gradlew --no-daemon \
 *   :app:testDebugUnitTest --tests 'com.tom.animacolab.colab.ColabProtocolLiveTest'
 * ```
 *
 * 本测试会自己 POST /api/kernels 建新 kernel（避开已有会话），结束时删除。
 */
class ColabProtocolLiveTest {

    private val runtimeUrl: String? = System.getenv("COLAB_RUNTIME_URL")
    private val runtimeToken: String? = System.getenv("COLAB_RUNTIME_TOKEN")
    private val accessToken: String? = System.getenv("COLAB_ACCESS_TOKEN")
    private val proxySpec: String? = System.getenv("COLAB_PROXY")

    private fun newHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
        proxySpec?.takeIf { it.isNotBlank() }?.let { spec ->
            val uri = URI(if (spec.contains("://")) spec else "http://$spec")
            val port = if (uri.port > 0) uri.port else 7890
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, port)))
        }
        return builder.build()
    }

    @Test
    fun executesPrintOnRealKernel() {
        assumeTrue(
            "需要 COLAB_RUNTIME_URL + COLAB_RUNTIME_TOKEN 环境变量，跳过 live kernel 测试",
            !runtimeUrl.isNullOrBlank() && !runtimeToken.isNullOrBlank(),
        )
        val http = newHttpClient()
        try {
            val kernel = JupyterKernelClient(runtimeUrl!!, runtimeToken!!, http)
            runBlocking {
                withTimeout(180_000L) {
                    val kernelId = kernel.createKernel()
                    println("[live] created kernel: $kernelId")
                    try {
                        assertTrue("listKernels 应包含刚建的 kernel", kernel.listKernels().contains(kernelId))

                        // 1) stdout 实时回调 + execute_reply ok
                        val stdout = StringBuilder()
                        val errors = StringBuilder()
                        val ok = kernel.execute(
                            kernelId = kernelId,
                            code = "print('HELLO123')",
                            onStream = { name, text ->
                                println("[live][$name] ${text.trimEnd()}")
                                stdout.append(text)
                            },
                            onError = { message -> errors.append(message) },
                        )
                        assertTrue("execute 应返回 true（execute_reply.status=ok）", ok)
                        assertTrue("应收到 HELLO123 输出，实际=$stdout", stdout.toString().contains("HELLO123"))

                        // 2) 运行时报错 → 返回 false 且 onError 拿到 traceback
                        val errorOk = kernel.execute(
                            kernelId = kernelId,
                            code = "raise RuntimeError('live-boom')",
                            onStream = { _, text -> stdout.append(text) },
                            onError = { message -> errors.append(message) },
                        )
                        assertTrue("执行报错时 execute 应返回 false", !errorOk)
                        assertTrue(
                            "onError 应包含 RuntimeError/live-boom，实际=$errors",
                            errors.toString().contains("RuntimeError") || errors.toString().contains("live-boom"),
                        )
                    } finally {
                        runCatching { kernel.deleteKernel(kernelId) }
                    }
                }
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun listsRealAssignments() {
        assumeTrue(
            "需要 COLAB_ACCESS_TOKEN 环境变量（且通常需要 COLAB_PROXY），跳过 live assignments 测试",
            !accessToken.isNullOrBlank(),
        )
        val http = newHttpClient()
        try {
            val api = ColabApi(accessToken = { accessToken!! }, httpClient = http)
            runBlocking {
                withTimeout(90_000L) {
                    val assignments = api.listAssignments()
                    println("[live] assignments=${assignments.map { it.endpoint }}")
                    assignments.forEach {
                        println(
                            "[live] endpoint=${it.endpoint} accelerator=${it.accelerator} " +
                                "variant=${it.variant} machineShape=${it.machineShape} " +
                                "url=${it.runtimeProxy.url} tokenExpiresIn=${it.runtimeProxy.tokenExpiresInSeconds}",
                        )
                    }
                }
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
