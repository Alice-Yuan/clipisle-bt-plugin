package com.clipisle.app.bt.tracker

import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** HTTPS subscription transport. DNS validation supplies the exact addresses OkHttp connects to. */
open class BtTrackerSourceClient(
    http: OkHttpClient = OkHttpClient(),
    private val resolver: Dns = Dns.SYSTEM,
    private val addressAllowed: (InetAddress) -> Boolean = { BtTrackerUrl.isPublicAddress(it) || BtTrackerUrl.isTestAddress(it) },
) {
    companion object {
        const val ENDPOINT_TIMEOUT_MS = 8_000
        const val SOURCE_TIMEOUT_MS = 18_000
        const val ROUND_TIMEOUT_MS = 45_000
        const val MAX_BODY_BYTES = 128 * 1024
        const val MAX_LINES = 500
        const val MAX_REDIRECTS = 2
        const val MAX_CONCURRENT = 2
        const val USER_AGENT = "ClipIsle/1.0 (Android; tracker-catalog)"

        internal fun decodeBody(bytes: ByteArray): EndpointResult {
            if (bytes.size > MAX_BODY_BYTES) return EndpointResult.Fail("body_too_large")
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: Exception) { return EndpointResult.Fail("invalid_utf8") }
            val cleaned = text.removePrefix("\uFEFF")
            if (cleaned.isBlank()) return EndpointResult.Fail("empty_body")
            if (cleaned.trimStart().startsWith('{') || cleaned.trimStart().startsWith('[') || cleaned.contains('<') || cleaned.contains('>'))
                return EndpointResult.Fail("invalid_body")
            val lines = cleaned.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
            if (lines.any { it.toByteArray(Charsets.UTF_8).size > BtTrackerUrl.MAX_LINE_BYTES }) return EndpointResult.Fail("line_too_long")
            val parsed = BtTrackerUrl.parseText(cleaned, requirePublicHost = true)
            if (parsed.valid.isEmpty()) return EndpointResult.Fail("no_valid_entries")
            if (parsed.invalid.size * 10 > lines.size) return EndpointResult.Fail("malformed_ratio")
            if (parsed.valid.size > MAX_LINES) return EndpointResult.Fail("line_limit")
            return EndpointResult.Ok(parsed.valid.map { it.raw }, null, null, lines.size, parsed.invalid.size)
        }
        internal fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long {
            if (value == null) return 0
            val seconds = value.trim().toLongOrNull()
            val millis = if (seconds != null) seconds.coerceIn(0, 86400) * 1000 else
                runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }.getOrDefault(0)
            return millis.coerceIn(0, 86_400_000)
        }
    }
    sealed class EndpointResult {
        data class Ok(val urls: List<String>, val etag: String?, val lastModified: String?, val lineCount: Int, val droppedLines: Int) : EndpointResult()
        data object NotModified : EndpointResult()
        data class Fail(val reason: String, val retryAfterMs: Long = 0, val validCount: Int = 0, val rejectedCount: Int = 0) : EndpointResult()
    }
    data class HostRejection(val index: Int, val reason: String)
    data class PublicValidation(val accepted: List<String>, val rejected: List<HostRejection>)
    private val io = ThreadPoolExecutor(MAX_CONCURRENT, MAX_CONCURRENT, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { r -> Thread(r, "bt-tracker-io").apply { isDaemon = true } })
    private val validationIo = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { r -> Thread(r, "bt-tracker-validate").apply { isDaemon = true } })
    private val dnsIo = ThreadPoolExecutor(MAX_CONCURRENT, MAX_CONCURRENT, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1000),
        { r -> Thread(r, "bt-tracker-dns").apply { isDaemon = true } })
    private val active = ConcurrentHashMap<Future<*>, AtomicReference<Call?>>()
    private val baseHttp = http.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).build()

    /** A preflight only: libtorrent may resolve again later. Never use this to rewrite original trackers. */
    open fun validatePublicTrackers(urls: List<String>, timeoutMs: Long = ENDPOINT_TIMEOUT_MS.toLong()): Future<PublicValidation> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(1, ENDPOINT_TIMEOUT_MS.toLong()))
        val task = object : FutureTask<PublicValidation>(Callable { validatePublicTrackersInternal(urls, deadline) }) {
            override fun done() { active.remove(this) }
        }
        active[task] = AtomicReference(null)
        try { validationIo.execute(task) } catch (_: Exception) { task.cancel(true) }
        return task
    }
    private fun validatePublicTrackersInternal(urls: List<String>, deadline: Long): PublicValidation {
        if (urls.size > MAX_LINES) return PublicValidation(emptyList(), urls.indices.map { HostRejection(it, "limit") })
        val parsed = urls.map { (BtTrackerUrl.parse(it) as? BtTrackerUrl.Result.Ok)?.value }
        val jobs = LinkedHashMap<String, Future<List<InetAddress>>>()
        val outcomes = HashMap<String, String?>()
        try {
            parsed.filterNotNull().forEach { value ->
                if (value.host !in jobs && value.host !in outcomes) {
                    if (!BtTrackerUrl.isPublicHost(value.host)) outcomes[value.host] = "non_public_target"
                    else if (System.nanoTime() >= deadline) outcomes[value.host] = "timeout"
                    else try { jobs[value.host] = dnsIo.submit<List<InetAddress>> { resolver.lookup(value.host) } }
                    catch (_: Exception) { outcomes[value.host] = "dns" }
                }
            }
            for ((host, job) in jobs) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                outcomes[host] = try {
                    val remaining = deadline - System.nanoTime()
                    val addresses = if (job.isDone) job.get() else if (remaining > 0) job.get(remaining, TimeUnit.NANOSECONDS) else null
                    when {
                        addresses == null -> "timeout"
                        addresses.isEmpty() -> "dns"
                        addresses.any { !addressAllowed(it) } -> "non_public_target"
                        else -> null
                    }
                } catch (e: InterruptedException) { throw e }
                  catch (_: java.util.concurrent.TimeoutException) { "timeout" }
                  catch (_: Exception) { "dns" }
            }
            val accepted = ArrayList<String>(); val rejected = ArrayList<HostRejection>()
            urls.forEachIndexed { index, url ->
                val value = parsed[index]
                val problem = if (value == null) "invalid_url" else outcomes[value.host]
                if (problem == null && value != null) accepted.add(url) else rejected.add(HostRejection(index, problem ?: "dns"))
            }
            return PublicValidation(accepted, rejected)
        } finally { jobs.values.forEach { it.cancel(true) }; dnsIo.purge() }
    }

    open fun fetch(endpoint: String, etag: String? = null, lastModified: String? = null,
                   timeoutMs: Long = ENDPOINT_TIMEOUT_MS.toLong()): Future<EndpointResult> {
        val call = AtomicReference<Call?>()
        val task = object : FutureTask<EndpointResult>(Callable {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(1, ENDPOINT_TIMEOUT_MS.toLong()))
            try { fetchInternal(endpoint, etag, lastModified, deadline, call) }
            catch (_: Exception) { EndpointResult.Fail(if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) "timeout" else "network") }
        }) {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean { call.get()?.cancel(); return super.cancel(mayInterruptIfRunning) }
            override fun done() { active.remove(this) }
        }
        active[task] = call
        try { io.execute(task) } catch (_: Exception) { active.remove(task); task.cancel(true) }
        return task
    }
    private fun fetchInternal(endpoint: String, etag: String?, modified: String?, deadline: Long,
                              activeCall: AtomicReference<Call?>): EndpointResult {
        fun remaining(): Long {
            if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) throw IOException("Timeout")
            return left
        }
        val http = baseHttp.newBuilder().dns(object : Dns {
          override fun lookup(hostname: String): List<InetAddress> {
            val pending = dnsIo.submit<List<InetAddress>> { resolver.lookup(hostname) }
            try {
                val addresses = pending.get(remaining(), TimeUnit.MILLISECONDS)
                if (addresses.isEmpty() || addresses.any { !addressAllowed(it) }) throw UnknownHostException("Non-public target")
                return addresses
            } finally { pending.cancel(true) }
          }
        }).build()
        var url = endpoint
        for (redirect in 0..MAX_REDIRECTS) {
            if (BtTrackerUrl.validateSubscriptionUrl(url) !is BtTrackerUrl.Result.Ok) return EndpointResult.Fail("invalid_endpoint")
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "text/plain")
                .header("Accept-Encoding", "identity")
            if (redirect == 0) {
                safeHeader(etag)?.let { request.header("If-None-Match", it) }
                safeHeader(modified)?.let { request.header("If-Modified-Since", it) }
            }
            val call = http.newCall(request.build())
            call.timeout().timeout(remaining(), TimeUnit.MILLISECONDS)
            activeCall.set(call)
            if (Thread.currentThread().isInterrupted) { call.cancel(); throw IOException("Cancelled") }
            try {
                call.execute().use { response ->
                    when {
                        response.code == 304 -> return EndpointResult.NotModified
                        response.code in 300..399 -> {
                            if (redirect >= MAX_REDIRECTS) return EndpointResult.Fail("too_many_redirects")
                            val location = response.header("Location") ?: return EndpointResult.Fail("bad_redirect")
                            url = try { URI(url).resolve(location).toString() } catch (_: Exception) { return EndpointResult.Fail("bad_redirect") }
                        }
                        response.code == 429 || response.code == 503 -> return EndpointResult.Fail("rate_limited", retryAfter(response.header("Retry-After")))
                        response.code != 200 -> return EndpointResult.Fail("http_${response.code}")
                        else -> {
                            val body = response.body ?: return EndpointResult.Fail("empty_body")
                            val stream = body.byteStream()
                            val decoded = when (response.header("Content-Encoding")?.lowercase()) {
                                null, "", "identity" -> stream
                                "gzip" -> GZIPInputStream(stream)
                                "deflate" -> InflaterInputStream(stream)
                                else -> return EndpointResult.Fail("unsupported_encoding")
                            }
                            val bytes = ByteArrayOutputStream()
                            decoded.use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    remaining()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (bytes.size() + count > MAX_BODY_BYTES) return EndpointResult.Fail("body_too_large")
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            return when (val parsed = decodeBody(bytes.toByteArray())) {
                                is EndpointResult.Ok -> {
                                    val validated = validatePublicTrackersInternal(parsed.urls, deadline)
                                    val dropped = parsed.droppedLines + validated.rejected.size
                                    when {
                                        validated.accepted.isEmpty() -> EndpointResult.Fail(
                                            if (validated.rejected.any { it.reason == "non_public_target" }) "non_public_target" else validated.rejected.firstOrNull()?.reason ?: "no_valid_entries",
                                            rejectedCount = dropped)
                                        else -> parsed.copy(urls = validated.accepted, droppedLines = dropped,
                                            etag = safeHeader(response.header("ETag")), lastModified = safeHeader(response.header("Last-Modified")))
                                    }
                                }
                                else -> parsed
                            }
                        }
                    }
                }
            } finally { activeCall.compareAndSet(call, null) }
        }
        return EndpointResult.Fail("too_many_redirects")
    }
    private fun safeHeader(value: String?): String? = value?.takeIf { it.isNotEmpty() && it.length <= 2048 && it.none { c -> c < ' ' || c == '\u007f' } }
    fun cancelAll() { active.keys.toList().forEach { it.cancel(true) } }
    open fun shutdown() { cancelAll(); io.shutdownNow(); validationIo.shutdownNow(); dnsIo.shutdownNow(); baseHttp.connectionPool.evictAll() }
}
