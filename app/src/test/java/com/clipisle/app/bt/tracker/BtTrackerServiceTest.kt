package com.clipisle.app.bt.tracker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BtTrackerServiceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun config(count: Int = 1) = BtTrackerStore.Config.defaults().copy(sources = (1..count).map {
        BtTrackerStore.SourceConfig("source$it", "Source $it", true, listOf("https://source$it.example/list"))
    })
    private fun ok(url: String = "udp://tracker.example:6969/a") = BtTrackerSourceClient.EndpointResult.Ok(listOf(url), "etag", null, 1, 0)
    private class Client(val response: (String, String?, String?) -> BtTrackerSourceClient.EndpointResult) : BtTrackerSourceClient() {
        val calls = AtomicInteger()
        override fun fetch(endpoint: String, etag: String?, lastModified: String?, timeoutMs: Long): Future<EndpointResult> {
            calls.incrementAndGet()
            return CompletableFuture.completedFuture(response(endpoint, etag, lastModified))
        }
    }
    private fun await(service: BtTrackerService, operation: Map<String, Any>): Map<String, Any> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val state = service.syncState(operation["operationId"] as String)
            if (state["running"] == false) return state
            Thread.sleep(5)
        }
        error("Synchronization did not finish")
    }

    @Test fun `async network success commits to disk and invokes completion before finished`() {
        val store = BtTrackerStore(temp.newFolder()); val client = Client { _, _, _ -> ok() }
        val service = BtTrackerService(store, client); val result = AtomicReference<BtTrackerStore.Catalog>()
        try {
            val state = await(service, service.sync(config(), null, false) { result.set(it) })
            assertEquals("completed", state["state"]); assertNotNull(result.get())
            assertEquals(ok().urls, (store.loadCatalog() as BtTrackerStore.LoadResult.Ok).value.pool)
        } finally { service.shutdown() }
    }

    @Test fun `late completed request cannot restore changed config`() {
        val store = BtTrackerStore(temp.newFolder()); val client = Client { _, _, _ -> ok() }
        val service = BtTrackerService(store, client); val current = AtomicReference(config())
        val dispatched = AtomicReference<() -> Unit>(); val waiting = CountDownLatch(1)
        service.bind({ current.get() }, { dispatched.set(it); waiting.countDown() }, { true })
        try {
            val op = service.sync(current.get(), null, false) { fail("Stale update published") }
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            current.set(current.get().copy(revision = 2, sources = emptyList()))
            dispatched.get().invoke()
            assertEquals("canceled", service.syncState(op["operationId"] as String)["state"])
            assertEquals(BtTrackerStore.LoadResult.Absent, store.loadCatalog())
        } finally { service.shutdown() }
    }

    @Test fun `cancel reaches outstanding future and cannot commit`() {
        val requested = CountDownLatch(1); val pending = CompletableFuture<BtTrackerSourceClient.EndpointResult>()
        val client = object : BtTrackerSourceClient() {
            override fun fetch(endpoint: String, etag: String?, lastModified: String?, timeoutMs: Long): Future<EndpointResult> {
                requested.countDown(); return pending
            }
        }
        val store = BtTrackerStore(temp.newFolder()); val service = BtTrackerService(store, client)
        try {
            val op = service.sync(config(), null, false) { fail("Canceled request published") }
            assertTrue(requested.await(3, TimeUnit.SECONDS))
            service.cancel(op["operationId"] as String)
            assertEquals("canceled", await(service, op)["state"])
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!pending.isCancelled && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(pending.isCancelled)
            assertEquals(BtTrackerStore.LoadResult.Absent, store.loadCatalog())
        } finally { service.shutdown() }
    }

    @Test fun `missing body 304 retries same endpoint unconditionally before accepting`() {
        val requests = AtomicInteger()
        val client = Client { _, etag, _ ->
            assertNull(etag)
            if (requests.getAndIncrement() == 0) BtTrackerSourceClient.EndpointResult.NotModified else ok()
        }
        val service = BtTrackerService(BtTrackerStore(temp.newFolder()), client)
        try {
            assertEquals("completed", await(service, service.sync(config(), null, false) {})["state"])
            assertEquals(2, client.calls.get())
        } finally { service.shutdown() }
    }

    @Test fun `304 from a different endpoint cannot reuse another endpoints body`() {
        val source = config().sources[0].copy(endpoints = listOf("https://first.example/list", "https://second.example/list"))
        val cfg = config().copy(sources = listOf(source)); val now = System.currentTimeMillis()
        val old = BtTrackerStore.SourceCache(source.id, source.endpoints[0], ok().urls, "first-etag", null, now, now, now, sourceFingerprint = source.fingerprint(), dnsValidated = true)
        val previous = BtTrackerStore.Catalog(mapOf(source.id to old), old.urls, now)
        val client = Client { endpoint, etag, _ ->
            if (endpoint == source.endpoints[0]) { assertEquals("first-etag", etag); BtTrackerSourceClient.EndpointResult.Fail("network") }
            else { assertNull(etag); BtTrackerSourceClient.EndpointResult.NotModified }
        }
        val service = BtTrackerService(BtTrackerStore(temp.newFolder()), client); val latest = AtomicReference<BtTrackerStore.Catalog>()
        try {
            val state = await(service, service.sync(cfg, previous, true) { latest.set(it) })
            assertEquals("failed", state["state"]); assertEquals(3, client.calls.get())
            assertEquals(now, latest.get().sources.getValue(source.id).lastSuccessfulCheckAt)
            assertEquals(old.urls, latest.get().pool)
        } finally { service.shutdown() }
    }

    @Test fun `retry after prevents manual retry and skipped source does not extend it`() {
        val cfg = config(); val source = cfg.sources[0]; val now = System.currentTimeMillis()
        val old = BtTrackerStore.SourceCache(source.id, source.endpoints[0], ok().urls, null, null, now, now, now,
            lastError = "rate_limited", nextAttemptAt = now + 5000, retryAfterUntil = now + 5000, sourceFingerprint = source.fingerprint(), dnsValidated = true)
        val previous = BtTrackerStore.Catalog(mapOf(source.id to old), old.urls, now)
        val client = Client { _, _, _ -> error("Must not fetch during server cooldown") }
        val service = BtTrackerService(BtTrackerStore(temp.newFolder()), client); val latest = AtomicReference<BtTrackerStore.Catalog>()
        try {
            await(service, service.sync(cfg, previous, true) { latest.set(it) })
            assertEquals(0, client.calls.get()); assertEquals(old, latest.get().sources.getValue(source.id))
        } finally { service.shutdown() }
    }

    @Test fun `legacy unvalidated cache does not skip automatic refresh and 304 revalidates before publication`() {
        val cfg = config(); val source = cfg.sources[0]; val now = System.currentTimeMillis()
        val old = BtTrackerStore.SourceCache(source.id, source.endpoints[0], ok().urls, "etag", null, now, now, now,
            sourceFingerprint = source.fingerprint(), dnsValidated = false)
        val checks = AtomicInteger()
        val client = object : BtTrackerSourceClient() {
            override fun fetch(endpoint: String, etag: String?, lastModified: String?, timeoutMs: Long): Future<EndpointResult> =
                CompletableFuture.completedFuture(EndpointResult.NotModified)
            override fun validatePublicTrackers(urls: List<String>, timeoutMs: Long): Future<PublicValidation> {
                checks.incrementAndGet(); return CompletableFuture.completedFuture(PublicValidation(urls, emptyList()))
            }
        }
        val store = BtTrackerStore(temp.newFolder()); val service = BtTrackerService(store, client)
        val latest = AtomicReference<BtTrackerStore.Catalog>()
        try {
            val state = await(service, service.sync(cfg, BtTrackerStore.Catalog(mapOf(source.id to old), emptyList(), now), false) { latest.set(it) })
            assertEquals("completed", state["state"]); assertEquals(1, checks.get())
            assertTrue(latest.get().sources.getValue(source.id).dnsValidated)
            assertTrue((store.loadCatalog() as BtTrackerStore.LoadResult.Ok).value.sources.getValue(source.id).dnsValidated)
        } finally { service.shutdown() }
    }

    @Test fun `cold failures persist backoff but a skipped attempt does not increment failures`() {
        val client = Client { _, _, _ -> BtTrackerSourceClient.EndpointResult.Fail("network") }
        val store = BtTrackerStore(temp.newFolder()); val service = BtTrackerService(store, client)
        val latest = AtomicReference<BtTrackerStore.Catalog>()
        try {
            await(service, service.sync(config(), null, false) { latest.set(it) })
            val original = latest.get().sources.getValue("source1")
            assertEquals(1, original.consecutiveFailures); assertTrue(original.nextAttemptAt > original.lastAttemptAt)
            await(service, service.sync(config(), latest.get(), false) { latest.set(it) })
            assertEquals(original, latest.get().sources.getValue("source1")); assertEquals(1, client.calls.get())
        } finally { service.shutdown() }
    }

    @Test fun `disabled and expired cache never enter committed pool`() {
        val cfg = config(2).let { it.copy(sources = it.sources.mapIndexed { i, s -> s.copy(enabled = i == 1) }) }
        val now = System.currentTimeMillis(); val source = cfg.sources[0]
        val disabled = BtTrackerStore.SourceCache(source.id, source.endpoints[0], listOf("udp://disabled.example:80/a"), null, null, now, now, now, sourceFingerprint = source.fingerprint())
        val previous = BtTrackerStore.Catalog(mapOf(source.id to disabled), disabled.urls, now)
        val client = Client { _, _, _ -> ok() }; val service = BtTrackerService(BtTrackerStore(temp.newFolder()), client)
        val updated = AtomicReference<BtTrackerStore.Catalog>()
        try {
            await(service, service.sync(cfg, previous, false) { updated.set(it) })
            assertEquals(ok().urls, updated.get().pool); assertEquals(1, client.calls.get())
        } finally { service.shutdown() }
    }

    @Test fun `source cap rejection keeps other successful source commits`() {
        val client = Client { endpoint, _, _ ->
            val group = endpoint.substringAfter("source").substringBefore('.')
            val count = if (group == "3") 1 else 500
            val urls = (1..count).map { "udp://g$group-t$it.example:80/a" }
            BtTrackerSourceClient.EndpointResult.Ok(urls, null, null, urls.size, 0)
        }
        val service = BtTrackerService(BtTrackerStore(temp.newFolder()), client); val updated = AtomicReference<BtTrackerStore.Catalog>()
        try {
            val state = await(service, service.sync(config(3), null, false) { updated.set(it) })
            assertEquals(1000, updated.get().pool.size); assertEquals(1, state["failedSources"])
            assertEquals("pool_limit", updated.get().sources.getValue("source3").lastError)
        } finally { service.shutdown() }
    }
}
