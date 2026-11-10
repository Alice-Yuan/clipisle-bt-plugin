package com.clipisle.app.bt.tracker

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BtTrackerPublicDnsTest {
    @Test fun `custom preflight preserves order rejects every nonpublic answer and resolves a host once`() {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                counts.computeIfAbsent(hostname) { AtomicInteger() }.incrementAndGet()
                return when (hostname) {
                    "bad.example" -> listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"))
                    "missing.example" -> throw UnknownHostException()
                    else -> listOf(InetAddress.getByName("8.8.8.8"))
                }
            }
        }
        val client = BtTrackerSourceClient(resolver = resolver)
        try {
            val urls = listOf("udp://good.example:80/a", "https://bad.example/a", "https://missing.example/a", "https://good.example/b")
            val result = client.validatePublicTrackers(urls).get(2, TimeUnit.SECONDS)
            assertEquals(listOf(urls[0], urls[3]), result.accepted)
            assertEquals(listOf(1, 2), result.rejected.map { it.index })
            assertEquals(listOf("non_public_target", "dns"), result.rejected.map { it.reason })
            assertEquals(1, counts.getValue("good.example").get())
        } finally { client.shutdown() }
    }

    @Test fun `preflight applies one total deadline and cancels outstanding dns work`() {
        val started = CountDownLatch(1); val canceled = CountDownLatch(1)
        val resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                started.countDown()
                try { Thread.sleep(5000); return listOf(InetAddress.getByName("8.8.8.8")) }
                finally { canceled.countDown() }
            }
        }
        val client = BtTrackerSourceClient(resolver = resolver)
        try {
            val before = System.nanoTime()
            val result = client.validatePublicTrackers((1..20).map { "https://t$it.example/a" }, 100).get(1, TimeUnit.SECONDS)
            assertTrue(started.await(1, TimeUnit.SECONDS)); assertTrue(canceled.await(1, TimeUnit.SECONDS))
            assertTrue(result.accepted.isEmpty()); assertEquals(20, result.rejected.size)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1000)
        } finally { client.shutdown() }
    }

    @Test fun `canceling custom validation interrupts dns wait without saving anything`() {
        val started = CountDownLatch(1); val stopped = CountDownLatch(1)
        val client = BtTrackerSourceClient(resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                started.countDown()
                try { Thread.sleep(5000); return emptyList() } finally { stopped.countDown() }
            }
        })
        try {
            val pending = client.validatePublicTrackers(listOf("https://wait.example/a"))
            assertTrue(started.await(1, TimeUnit.SECONDS)); pending.cancel(true)
            assertTrue(pending.isCancelled); assertTrue(stopped.await(1, TimeUnit.SECONDS))
        } finally { client.shutdown() }
    }
}
