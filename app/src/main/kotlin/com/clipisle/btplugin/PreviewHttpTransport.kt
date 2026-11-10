package com.clipisle.btplugin

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException

internal class PreviewTransportException(val safeMessage: String) : IOException(safeMessage)

/** One wall-clock budget covers redirects and the entire bounded response body. */
internal class PreviewHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val budgetMs: Long = 20_000,
    private val validate: (String) -> HttpUrl? = PreviewLookup::allowedUrl,
) {
    companion object {
        const val MAX_BYTES = 256 * 1024
        // A platform DNS resolver may ignore interruption. Bound those workers
        // and fail promptly rather than letting IPC threads wait indefinitely.
        private val network = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, SynchronousQueue<Runnable>()) {
            runnable -> Thread(runnable, "clipisle-preview-network").apply { isDaemon = true }
        }
    }
    fun get(address: String, session: PreviewRequests.Session): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        if (session.canceled.get()) throw PreviewTransportException("操作已取消")
        val work = try { network.submit<String> { getBlocking(address, session, deadline) } }
        catch (_: RejectedExecutionException) { throw PreviewTransportException("预览请求过多，请稍后重试") }
        try {
            while (true) {
                if (session.canceled.get()) throw PreviewTransportException("操作已取消")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw PreviewTransportException("预览请求超时，可以直接选择文件")
                try {
                    return work.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    // Also observe cancellation while a platform DNS call is blocked.
                } catch (error: ExecutionException) {
                    throw error.cause as? Exception ?: IOException("preview transport failed")
                }
            }
        } finally {
            if (!work.isDone) { session.cancel(); work.cancel(true) }
        }
    }

    private fun getBlocking(address: String, session: PreviewRequests.Session, deadline: Long): String {
        var url = validate(address) ?: throw PreviewTransportException("预览服务地址无效")
        repeat(5) { hop ->
            if (session.canceled.get()) throw PreviewTransportException("操作已取消")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw PreviewTransportException("预览请求超时，可以直接选择文件")
            val call = client.newCall(Request.Builder().url(url)
                .header("Accept", "application/json").header("User-Agent", "ClipIsle-BT-Plugin").build())
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            session.attach(call)
            try {
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        if (hop == 4) throw PreviewTransportException("预览服务返回的数据无效")
                        val next = response.header("Location")?.let { url.resolve(it) }
                            ?: throw PreviewTransportException("预览服务地址无效")
                        url = validate(next.toString()) ?: throw PreviewTransportException("预览服务地址无效")
                        return@repeat
                    }
                    if (!response.isSuccessful) throw PreviewTransportException("暂时无法获取预览，可以直接选择文件")
                    val body = response.body ?: throw PreviewTransportException("预览服务返回的数据无效")
                    if (body.contentLength() > MAX_BYTES) throw PreviewTransportException("预览服务返回的数据无效")
                    return body.byteStream().use { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            if (session.canceled.get()) throw PreviewTransportException("操作已取消")
                            if (System.nanoTime() >= deadline) throw PreviewTransportException("预览请求超时，可以直接选择文件")
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (out.size() + n > MAX_BYTES) throw PreviewTransportException("预览服务返回的数据无效")
                            out.write(buffer, 0, n)
                        }
                        out.toString("UTF-8")
                    }
                }
            } catch (error: IOException) {
                if (session.canceled.get()) throw PreviewTransportException("操作已取消")
                if (System.nanoTime() >= deadline) throw PreviewTransportException("预览请求超时，可以直接选择文件")
                throw error
            } finally { session.detach(call) }
        }
        throw PreviewTransportException("预览服务返回的数据无效")
    }
}
