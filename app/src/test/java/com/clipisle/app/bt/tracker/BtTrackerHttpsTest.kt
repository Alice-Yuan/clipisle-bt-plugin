package com.clipisle.app.bt.tracker

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Real HTTPS over a local socket; only injected test dependencies allow the loopback destination. */
class BtTrackerHttpsTest {
    private fun fixture(test: (MockWebServer, OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("catalog.example")
            .addSubjectAlternativeName("catalog.example").addSubjectAlternativeName("other.example").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start(InetAddress.getLoopbackAddress(), 0)
        val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        try { test(server, http) } finally { server.shutdown() }
    }
    private val localDns = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }
    private fun client(http: OkHttpClient, dns: Dns = localDns) = BtTrackerSourceClient(http, dns) { it.isLoopbackAddress }
    private fun url(server: MockWebServer) = "https://catalog.example:${server.port}/list"

    @Test fun `real tls response and conditional header are accepted`() = fixture { server, http ->
        server.enqueue(MockResponse().setBody("udp://tracker.example:6969/announce\n").setHeader("ETag", "\"version1\""))
        val client = client(http)
        try {
            val result = client.fetch(url(server), "\"old\"").get(3, TimeUnit.SECONDS) as BtTrackerSourceClient.EndpointResult.Ok
            assertEquals("\"version1\"", result.etag)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("\"old\"", request.getHeader("If-None-Match"))
            assertNull(request.getHeader("Cookie"))
        } finally { client.shutdown() }
    }

    @Test fun `gzip is bounded after decompression on the wire`() = fixture { server, http ->
        val bytes = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(ByteArray(128 * 1024 + 1) { 65 }) } }.toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(bytes)))
        val client = client(http)
        try { assertEquals("body_too_large", (client.fetch(url(server)).get(3, TimeUnit.SECONDS) as BtTrackerSourceClient.EndpointResult.Fail).reason) }
        finally { client.shutdown() }
    }

    @Test fun `resolved private address is rejected before connecting`() = fixture { server, http ->
        val client = BtTrackerSourceClient(http, localDns) // no injected loopback permission
        try {
            assertTrue(client.fetch(url(server)).get(3, TimeUnit.SECONDS) is BtTrackerSourceClient.EndpointResult.Fail)
            assertEquals(0, server.requestCount)
        } finally { client.shutdown() }
    }

    @Test fun `injected trust does not disable tls hostname verification`() = fixture { server, http ->
        val client = client(http)
        try {
            assertTrue(client.fetch("https://wrong.example:${server.port}/list").get(3, TimeUnit.SECONDS) is BtTrackerSourceClient.EndpointResult.Fail)
            assertEquals(0, server.requestCount)
        } finally { client.shutdown() }
    }

    @Test fun `redirect dns is checked again and credentials are not forwarded`() = fixture { server, http ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example:${server.port}/list"))
        server.enqueue(MockResponse().setBody("https://tracker.example/announce"))
        val client = client(http)
        try {
            assertTrue(client.fetch(url(server), "secret-validator").get(3, TimeUnit.SECONDS) is BtTrackerSourceClient.EndpointResult.Ok)
            assertEquals("secret-validator", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("If-None-Match"))
            assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("If-None-Match"))
        } finally { client.shutdown() }
    }

    @Test fun `redirect to a hostname resolving privately never opens that target`() = fixture { server, http ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://private.example:${server.port}/list"))
        val dns = object : Dns {
            override fun lookup(hostname: String) = if (hostname == "private.example") listOf(InetAddress.getByName("10.1.2.3")) else localDns.lookup(hostname)
        }
        val client = client(http, dns)
        try {
            assertTrue(client.fetch(url(server)).get(3, TimeUnit.SECONDS) is BtTrackerSourceClient.EndpointResult.Fail)
            assertEquals(1, server.requestCount)
        } finally { client.shutdown() }
    }

    @Test fun `cancel aborts an actual waiting socket`() = fixture { server, http ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val failed = CountDownLatch(1)
        val client = client(http.newBuilder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
        }).build())
        try {
            val future = client.fetch(url(server))
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            future.cancel(true)
            assertTrue(failed.await(2, TimeUnit.SECONDS)); assertTrue(future.isCancelled)
        } finally { client.shutdown() }
    }

    @Test fun `total endpoint deadline closes stalled response`() = fixture { server, http ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = client(http)
        try {
            val started = System.nanoTime()
            val result = client.fetch(url(server), timeoutMs = 350).get(2, TimeUnit.SECONDS)
            assertTrue(result is BtTrackerSourceClient.EndpointResult.Fail)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
        } finally { client.shutdown() }
    }

    @Test fun `subscription content dns drops private endpoints and reports exact rejection count`() = fixture { server, http ->
        val dns = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName(when (hostname) {
                "catalog.example" -> "127.0.0.1"
                "blocked.example" -> "10.0.0.1"
                else -> "8.8.8.8"
            }))
        }
        val client = BtTrackerSourceClient(http, dns) { it.isLoopbackAddress || BtTrackerUrl.isPublicAddress(it) }
        try {
            server.enqueue(MockResponse().setBody((1..4).joinToString("\n") { "udp://good$it.example:80/a" } +
                (1..3).joinToString("") { "\nhttps://blocked.example/a$it" }))
            val result = client.fetch(url(server)).get(3, TimeUnit.SECONDS) as BtTrackerSourceClient.EndpointResult.Ok
            assertEquals(4, result.urls.size); assertEquals(3, result.droppedLines)
            assertFalse(result.urls.any { it.contains("blocked") })
            server.enqueue(MockResponse().setBody("https://blocked.example/a"))
            val rejected = client.fetch(url(server)).get(3, TimeUnit.SECONDS) as BtTrackerSourceClient.EndpointResult.Fail
            assertEquals("non_public_target", rejected.reason); assertEquals(1, rejected.rejectedCount)
        } finally { client.shutdown() }
    }
}
