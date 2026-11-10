package com.clipisle.btplugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PreviewHttpTransportTest {
    @Test fun `blocked platform DNS cannot keep the caller past its deadline`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                return listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }
        }).build()
        val transport = PreviewHttpTransport(client, 350, { it.toHttpUrlOrNull() })
        val started = System.nanoTime()
        try {
            val error = runCatching { transport.get("http://preview.test/", PreviewRequests.Session()) }.exceptionOrNull()
            assertEquals(0L, entered.count)
            assertEquals("预览请求超时，可以直接选择文件", error?.message)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
        } finally { release.countDown() }
    }
    private fun transport(budgetMs: Long = 2000, allow: (String) -> Boolean = { true }) = PreviewHttpTransport(
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(), budgetMs,
        { url -> url.toHttpUrlOrNull()?.takeIf { allow(url) } },
    )

    @Test fun `slow body cannot renew the total deadline with each read`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            val started = System.nanoTime()
            val error = runCatching { transport(350).get(server.url("/api").toString(), PreviewRequests.Session()) }.exceptionOrNull()
            assertTrue(error is PreviewTransportException)
            assertEquals("预览请求超时，可以直接选择文件", error!!.message)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
        }
    }

    @Test fun `cancel disconnects an in progress response without waiting for read timeout`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS))
            val session = PreviewRequests.Session()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<String> { transport(10_000).get(server.url("/api").toString(), session) }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                session.cancel()
                val error = runCatching { result.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
                assertTrue(error is java.util.concurrent.ExecutionException)
                assertEquals("操作已取消", error!!.cause!!.message)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `early cancellation makes no request`() {
        MockWebServer().use { server ->
            val session = PreviewRequests.Session().apply { cancel() }
            val error = runCatching { transport().get(server.url("/api").toString(), session) }.exceptionOrNull()
            assertEquals("操作已取消", error?.message)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `each redirect is validated before making another request`() {
        MockWebServer().use { first ->
            MockWebServer().use { forbidden ->
                first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", forbidden.url("/private")))
                val transport = transport { it.startsWith(first.url("/").toString()) }
                val error = runCatching { transport.get(first.url("/api").toString(), PreviewRequests.Session()) }.exceptionOrNull()
                assertEquals("预览服务地址无效", error?.message)
                assertEquals(0, forbidden.requestCount)
            }
        }
    }

    @Test fun `redirects share one total deadline`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next").setHeadersDelay(200, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val error = runCatching { transport(350).get(server.url("/api").toString(), PreviewRequests.Session()) }.exceptionOrNull()
            assertEquals("预览请求超时，可以直接选择文件", error?.message)
            assertTrue(server.requestCount <= 2)
        }
    }

    @Test fun `both declared and streaming oversized responses are rejected`() {
        for (chunked in listOf(false, true)) MockWebServer().use { server ->
            val oversized = "x".repeat(PreviewHttpTransport.MAX_BYTES + 1)
            val response = if (chunked) MockResponse().setChunkedBody(oversized, 8192) else MockResponse().setBody(oversized)
            server.enqueue(response)
            val error = runCatching { transport().get(server.url("/api").toString(), PreviewRequests.Session()) }.exceptionOrNull()
            assertEquals("预览服务返回的数据无效", error?.message)
        }
    }

    @Test fun `production origin rejects downgrade credentials ports and aliases`() {
        assertNotNull(PreviewLookup.allowedUrl("https://whatslink.info/api/v1/link?url=magnet"))
        for (url in listOf("http://whatslink.info/api", "https://whatslink.info:8443/api", "https://user:pass@whatslink.info/api", "https://whatslink.info.evil.test/api", "https://127.0.0.1/api", "https://whatslink.info/api#fragment")) {
            assertNull(url, PreviewLookup.allowedUrl(url))
        }
    }
}
