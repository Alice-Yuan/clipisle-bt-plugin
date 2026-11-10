package com.clipisle.app.bt.tracker

import android.content.Context
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Bounded I/O plus a serialized authority callback. A completed fetch is not a committed catalog. */
class BtTrackerService(private val store: BtTrackerStore, private val client: BtTrackerSourceClient) {
    constructor(context: Context, store: BtTrackerStore, client: BtTrackerSourceClient) : this(store, client)
    companion object {
        const val MANUAL_COOLDOWN_MS = 60_000L
        const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000
        val BACKOFF_STEPS_MS = longArrayOf(300_000, 1_800_000, 7_200_000, 43_200_000)
        const val BACKOFF_MAX_MS = 86_400_000L
    }
    private val lock = Any()
    private val coordinator = Executors.newSingleThreadExecutor { r -> Thread(r, "bt-tracker-sync").apply { isDaemon = true } }
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "bt-tracker-source").apply { isDaemon = true } }
    private var activeOperation: Operation? = null
    private var lastManualAt = 0L
    private var generation = 0L
    private var currentConfig: (() -> BtTrackerStore.Config)? = null
    private var dispatch: ((() -> Unit) -> Unit) = { it() }
    private var canSync: () -> Boolean = { true }

    /** Bind from Runtime once; commit and config access then occur only on its serial executor. */
    fun bind(currentConfig: () -> BtTrackerStore.Config, dispatch: ((() -> Unit) -> Unit), canSync: () -> Boolean) {
        synchronized(lock) { this.currentConfig = currentConfig; this.dispatch = dispatch; this.canSync = canSync }
    }
    fun sync(config: BtTrackerStore.Config, catalog: BtTrackerStore.Catalog?, manual: Boolean,
             onComplete: (BtTrackerStore.Catalog) -> Unit): Map<String, Any> = synchronized(lock) {
        activeOperation?.takeIf { it.state == "running" }?.let { return@synchronized state(it) }
        BtTrackerStore.validateConfig(config)
        if (!canSync()) return@synchronized error("WAITING_NETWORK")
        if (!manual && (!config.supplementsEnabled || !config.autoUpdateEnabled)) return@synchronized error("TRACKER_AUTO_DISABLED")
        val now = System.currentTimeMillis()
        if (manual && now >= lastManualAt && now - lastManualAt < MANUAL_COOLDOWN_MS)
            return@synchronized error("TRACKER_COOLDOWN") + mapOf("retryAfterMs" to MANUAL_COOLDOWN_MS - (now - lastManualAt))
        if (manual) lastManualAt = now
        val op = Operation("sync-${UUID.randomUUID()}", generation, config, catalog, now)
        activeOperation = op
        val enabled = config.sources.filter { it.enabled }
        val due = enabled.filter { source ->
            val cached = compatible(source, catalog?.sources?.get(source.id))
            val status = when {
                cached != null && now < cached.retryAfterUntil -> "backoff"
                !manual && cached != null && now < cached.nextAttemptAt -> "backoff"
                !manual && cached != null && cached.dnsValidated && cached.lastError == null && cached.lastSuccessfulCheckAt > 0 &&
                    now >= cached.lastSuccessfulCheckAt && now - cached.lastSuccessfulCheckAt < AUTO_INTERVAL_MS -> "fresh"
                else -> null
            }
            if (status != null) op.results[source.id] = Outcome(source.id, status, cached)
            status == null
        }
        op.pending = due.size
        due.forEach { op.results[it.id] = Outcome(it.id, "pending", compatible(it, catalog?.sources?.get(it.id))) }
        val roundDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BtTrackerSourceClient.ROUND_TIMEOUT_MS.toLong())
        op.futures.add(coordinator.submit {
            try {
                val jobs = due.map { source -> source to workers.submit<Outcome> {
                    fetchSource(op, source, compatible(source, catalog?.sources?.get(source.id)), roundDeadline)
                }.also { track(op, it) } }
                for ((source, job) in jobs) {
                    if (cancelled(op)) break
                    val result = try {
                        job.get((roundDeadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    } catch (_: Exception) {
                        job.cancel(true)
                        Outcome(source.id, "failed", compatible(source, catalog?.sources?.get(source.id)), error = "timeout")
                    }
                    synchronized(lock) {
                        if (live(op)) { op.results[source.id] = result; op.pending-- }
                    }
                }
                dispatch { commit(op, onComplete) }
            } catch (_: Exception) {
                synchronized(lock) { if (live(op)) { op.state = "failed"; op.code = "TRACKER_SOURCE_UNREACHABLE"; op.pending = 0 } }
            }
        })
        state(op)
    }

    private fun compatible(source: BtTrackerStore.SourceConfig, cache: BtTrackerStore.SourceCache?): BtTrackerStore.SourceCache? =
        cache?.takeIf { it.sourceFingerprint == source.fingerprint() || it.sourceFingerprint.isEmpty() && it.endpoint in source.effectiveEndpoints() }

    private fun fetchSource(op: Operation, source: BtTrackerStore.SourceConfig, old: BtTrackerStore.SourceCache?, roundDeadline: Long): Outcome {
        val sourceDeadline = minOf(roundDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BtTrackerSourceClient.SOURCE_TIMEOUT_MS.toLong()))
        var failure = "unreachable"
        var retry = 0L
        var validCount = 0
        var rejectedCount = 0
        for (endpoint in source.effectiveEndpoints()) {
            if (cancelled(op)) throw CancellationException()
            val endpointDeadline = minOf(sourceDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BtTrackerSourceClient.ENDPOINT_TIMEOUT_MS.toLong()))
            val cached = old?.endpointCache(endpoint)
            var unconditionalRetry = false
            while (true) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(endpointDeadline - System.nanoTime())
                if (remaining <= 0) { failure = "timeout"; break }
                val request = client.fetch(endpoint, if (unconditionalRetry) null else cached?.etag,
                    if (unconditionalRetry) null else cached?.lastModified, remaining)
                track(op, request)
                val result = try { request.get(remaining, TimeUnit.MILLISECONDS) }
                    catch (_: Exception) { BtTrackerSourceClient.EndpointResult.Fail("timeout") }
                    finally { request.cancel(true) }
                if (cancelled(op)) throw CancellationException()
                when (result) {
                    is BtTrackerSourceClient.EndpointResult.Ok -> return Outcome(source.id, "ok", old, endpoint, result)
                    is BtTrackerSourceClient.EndpointResult.NotModified -> {
                        if (cached != null && cached.urls.isNotEmpty() && cached.successfulAt > 0) {
                            val left = TimeUnit.NANOSECONDS.toMillis(endpointDeadline - System.nanoTime())
                            if (left <= 0) { failure = "timeout"; break }
                            val validation = client.validatePublicTrackers(cached.urls, left)
                            track(op, validation)
                            val checked = try { validation.get(left, TimeUnit.MILLISECONDS) }
                                catch (_: Exception) { null } finally { validation.cancel(true) }
                            if (cancelled(op)) throw CancellationException()
                            if (checked != null && checked.accepted.isNotEmpty())
                                return Outcome(source.id, "notModified", old, endpoint,
                                    BtTrackerSourceClient.EndpointResult.Ok(checked.accepted, cached.etag, cached.lastModified, cached.urls.size, checked.rejected.size))
                            failure = if (checked == null) "timeout" else if (checked.rejected.any { it.reason == "non_public_target" }) "non_public_target" else "dns_no_valid_entries"
                            validCount = checked?.accepted?.size ?: 0
                            rejectedCount = checked?.rejected?.size ?: cached.urls.size
                            break
                        }
                        if (!unconditionalRetry) { unconditionalRetry = true; continue }
                        failure = "missing_cache"
                    }
                    is BtTrackerSourceClient.EndpointResult.Fail -> {
                        failure = result.reason; retry = maxOf(retry, result.retryAfterMs)
                        if (result.rejectedCount > 0) { rejectedCount = result.rejectedCount; validCount = result.validCount }
                    }
                }
                break
            }
            if (System.nanoTime() >= sourceDeadline) break
        }
        return Outcome(source.id, "failed", old, error = failure, retryAfterMs = retry, validCount = validCount, rejectedCount = rejectedCount)
    }

    private fun commit(op: Operation, onComplete: (BtTrackerStore.Catalog) -> Unit) = synchronized(lock) {
        if (!live(op)) return@synchronized
        val latest = currentConfig?.invoke() ?: op.config
        if (latest != op.config || !canSync()) { cancelLocked(op); return@synchronized }
        val now = System.currentTimeMillis()
        val sources = LinkedHashMap<String, BtTrackerStore.SourceCache>()
        latest.sources.forEach { src -> compatible(src, op.previous?.sources?.get(src.id))?.let { sources[src.id] = it } }
        // Fixed configuration order means concurrent fetch completion cannot decide which source wins the cap.
        latest.sources.filter { it.enabled }.forEach { src ->
            val outcome = op.results[src.id] ?: return@forEach
            if (outcome.status == "fresh" || outcome.status == "backoff") return@forEach
            val old = sources[src.id]
            var fresh = outcome.fresh
            if (fresh != null) {
                val candidate = successful(src, old, outcome.endpoint, fresh, now)
                val proposed = sources.toMutableMap().apply { put(src.id, candidate) }
                if (BtTrackerStore.pool(latest, proposed, now).size <= BtTrackerStore.MAX_POOL) sources[src.id] = candidate
                else {
                    fresh = null
                    op.results[src.id] = outcome.copy(status = "failed", fresh = null, error = "pool_limit")
                }
            }
            if (fresh == null) {
                val result = op.results[src.id] ?: outcome
                val failures = ((old?.consecutiveFailures ?: 0).coerceAtMost(1000) + 1)
                val delay = maxOf(BACKOFF_STEPS_MS[(failures - 1).coerceAtMost(BACKOFF_STEPS_MS.lastIndex)], result.retryAfterMs.coerceAtMost(BACKOFF_MAX_MS))
                val base = old ?: BtTrackerStore.SourceCache(src.id, "", emptyList(), null, null, 0, 0, 0, sourceFingerprint = src.fingerprint())
                sources[src.id] = base.copy(lastAttemptAt = now, lastError = result.error ?: "unreachable", consecutiveFailures = failures,
                    dnsValidated = base.dnsValidated && result.error != "non_public_target",
                    nextAttemptAt = now + delay, retryAfterUntil = maxOf(base.retryAfterUntil, if (result.retryAfterMs > 0) now + result.retryAfterMs.coerceAtMost(BACKOFF_MAX_MS) else 0))
            }
        }
        val catalog = BtTrackerStore.Catalog(sources, BtTrackerStore.pool(latest, sources, now), now, (op.previous?.revision ?: 0) + 1)
        try {
            store.saveCatalog(catalog)
            onComplete(catalog)
            op.poolSize = catalog.pool.size; op.pending = 0
            val failed = op.results.values.count { it.status == "failed" }
            val succeeded = op.results.values.any { it.status == "ok" || it.status == "notModified" }
            op.state = if (failed > 0 && !succeeded) "failed" else "completed"
            if (op.state == "failed") op.code = "TRACKER_SOURCE_UNREACHABLE"
        } catch (_: Exception) { op.state = "failed"; op.code = "STORAGE_UNAVAILABLE"; op.pending = 0 }
    }
    private fun successful(source: BtTrackerStore.SourceConfig, old: BtTrackerStore.SourceCache?, endpoint: String,
                           result: BtTrackerSourceClient.EndpointResult.Ok, now: Long): BtTrackerStore.SourceCache {
        val endpointCaches = old?.endpointCaches.orEmpty().filterKeys { it in source.effectiveEndpoints() }.toMutableMap()
        old?.let { c -> c.endpointCache(c.endpoint)?.let { endpointCaches.putIfAbsent(c.endpoint, it) } }
        endpointCaches[endpoint] = BtTrackerStore.EndpointCache(result.urls, result.etag, result.lastModified, now)
        return BtTrackerStore.SourceCache(source.id, endpoint, result.urls, result.etag, result.lastModified, now, now,
            if (old != null && old.urls == result.urls) old.contentUpdatedAt else now,
            sourceFingerprint = source.fingerprint(), endpointCaches = endpointCaches, dnsValidated = true)
    }
    private fun track(op: Operation, future: Future<*>) = synchronized(lock) { if (live(op)) op.futures.add(future) else future.cancel(true) }
    private fun cancelled(op: Operation) = synchronized(lock) { !live(op) } || Thread.currentThread().isInterrupted
    private fun live(op: Operation) = activeOperation === op && op.state == "running" && op.generation == generation
    private fun cancelLocked(op: Operation) {
        op.state = "canceled"; op.pending = 0
        op.futures.toList().forEach { it.cancel(true) }
        op.results.replaceAll { _, value -> if (value.status == "pending") value.copy(status = "canceled") else value }
    }
    fun syncState(operationId: String): Map<String, Any> = synchronized(lock) {
        activeOperation?.takeIf { it.id == operationId }?.let(::state) ?: error("TRACKER_PLAN_STALE")
    }
    fun cancel(operationId: String) = synchronized(lock) { activeOperation?.takeIf { it.id == operationId && it.state == "running" }?.let(::cancelLocked); Unit }
    fun invalidate() = synchronized(lock) { generation++; activeOperation?.takeIf { it.state == "running" }?.let(::cancelLocked); Unit }
    fun cancelAll() = invalidate()
    fun shutdown() { invalidate(); coordinator.shutdownNow(); workers.shutdownNow(); client.shutdown() }
    private fun state(op: Operation): Map<String, Any> {
        val failed = op.results.values.count { it.status == "failed" }
        return mapOf("ok" to (op.state != "failed"), "operationId" to op.id, "running" to (op.state == "running"),
            "done" to (op.state != "running"), "state" to op.state, "pending" to op.pending, "code" to op.code,
            "poolSize" to op.poolSize, "failedSources" to failed, "partial" to
                (op.results.values.any { (it.fresh?.droppedLines ?: 0) > 0 } || failed > 0 && op.results.values.any { it.status == "ok" || it.status == "notModified" }),
            "results" to op.results.values.map { r -> mapOf("sourceId" to r.id, "status" to r.status,
                "count" to (r.fresh?.urls?.size ?: r.old?.takeIf { it.dnsValidated && !it.isExpired() }?.urls?.size ?: 0),
                "validCount" to (r.fresh?.urls?.size ?: r.validCount), "rejectedCount" to (r.fresh?.droppedLines ?: r.rejectedCount),
                "error" to (r.error ?: ""), "usingCache" to (r.fresh == null && r.old?.dnsValidated == true && !r.old.isExpired())) })
    }
    private fun error(code: String): Map<String, Any> = mapOf("ok" to false, "code" to code, "running" to false, "state" to "failed")
    private data class Outcome(val id: String, val status: String, val old: BtTrackerStore.SourceCache?, val endpoint: String = "",
                               val fresh: BtTrackerSourceClient.EndpointResult.Ok? = null, val error: String? = null, val retryAfterMs: Long = 0,
                               val validCount: Int = 0, val rejectedCount: Int = 0)
    private class Operation(val id: String, val generation: Long, val config: BtTrackerStore.Config,
                            val previous: BtTrackerStore.Catalog?, val startedAt: Long) {
        val futures = ArrayList<Future<*>>()
        val results = LinkedHashMap<String, Outcome>()
        var state = "running"; var pending = 0; var poolSize = previous?.pool?.size ?: 0; var code = ""
    }
}
