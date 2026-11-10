package com.clipisle.app.bt.tracker

import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tracker 健康度探测。
 *
 * ## 与 Motrix 的差异（有意的）
 *
 * Motrix 用 HTTP `HEAD` 加 UDP 完整 BEP 15 握手来判定。这里第一版**只做 TCP
 * 连通性 + TLS 可用性**，不解析 announce 响应：
 *
 *  - 它要回答的问题是「这个地址值不值得放进候选池」，不是「它现在能返回
 *    多少 peer」。后者由 libtorrent 的真实 announce 决定，我们不重复实现
 *    一套通告协议。
 *  - Android 上写 UDP BEP 15 要自己管事务 ID、连接超时、响应校验，
 *    而且 libtorrent 之后还会用自己的方式再连一次——两份实现容易给出
 *    矛盾的结论。
 *  - TCP 连不上 / TLS 握不上 是**硬失败**，这类地址留着只会拖慢调度。
 *
 * 对于 udp:// 的 Tracker，因为没有 TCP 端口可连，这里**不做探测**，
 * 直接标为 unknown（未知，不是失败）。这避免了「UDP 探测失败就误杀好地址」。
 *
 * ## 边界
 *
 * 探测结果只是排序提示，**不是连接门禁**：
 *  - 连续失败达到阈值会进入冷却，但仍保留在池里
 *  - 未探测的地址有保留配额（见 [BtTrackerRanker]），不会因为没数据被淘汰
 *  - 网络切换后结论作废（由调用方传 routeKey 区分）
 */
class BtTrackerProber {

    companion object {
        private const val TAG = "BtTrackerProbe"

        /** 单次探测超时。Motrix 默认 5 秒，这里给 4 秒——手机网络更快失败更划算。 */
        const val DEFAULT_TIMEOUT_MS = 4_000

        /** 并发上限。按 host 分组，避免对同一主机连打。 */
        const val MAX_CONCURRENCY = 4

        /** 连续失败多少次进冷却 */
        const val FAIL_THRESHOLD = 3

        /** 探测结果的新鲜期：超过这个时间的结论不再参与排序 */
        const val FRESH_MS = 30L * 60 * 1000
    }

    /** 一次探测的结论。 */
    data class Health(
        val key: String,
        val protocol: String,
        /** unknown / reachable / unreachable / unsupported */
        val status: String,
        /** 连接耗时；-1 表示没测出来 */
        val latencyMs: Long = -1,
        val lastProbeAt: Long = 0,
        val consecutiveFailures: Int = 0,
        val successCount: Int = 0,
        val failCount: Int = 0,
        /** 网络代次：换网后旧结论作废 */
        val routeKey: String = "",
    ) {
        val isFresh: Boolean
            get() = status != "unknown" &&
                lastProbeAt > 0 &&
                System.currentTimeMillis() - lastProbeAt < FRESH_MS && lastProbeAt <= System.currentTimeMillis()

        /** 是否应该进冷却 */
        val inCooldown: Boolean
            get() = consecutiveFailures >= FAIL_THRESHOLD

        fun successRate(): Double {
            val total = successCount + failCount
            return if (total == 0) 0.0 else successCount.toDouble() / total
        }
    }

    private val io: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENCY) { r ->
        Thread(r, "bt-tracker-probe").apply { isDaemon = true }
    }

    /**
     * 探测一批地址。
     *
     * 只探测 http/https；udp 直接返回 unknown（见类注释）。
     * 按 host 串行分组，不同 host 之间并发。
     */
    fun probe(
        urls: List<String>,
        routeKey: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        previous: Map<String, Health> = emptyMap(),
    ): Future<List<Health>> = io.submit(
        Callable {
            // 按 host 分组：同一主机不并发打，避免看起来像攻击
            val byHost = LinkedHashMap<String, MutableList<Pair<String, String>>>()
            for (url in urls) {
                val parsed = BtTrackerUrl.parse(url)
                if (parsed !is BtTrackerUrl.Result.Ok) continue
                val v = parsed.value
                byHost.getOrPut(v.host) { mutableListOf() }.add(url to v.kind.name)
            }

            val out = ArrayList<Health>(urls.size)
            for ((_, group) in byHost) {
                for ((url, kind) in group) {
                    out.add(probeOne(url, kind, routeKey, timeoutMs, previous[url]))
                }
            }
            out
        },
    )

    private fun probeOne(
        url: String,
        kind: String,
        routeKey: String,
        timeoutMs: Int,
        prev: Health?,
    ): Health {
        val now = System.currentTimeMillis()

        // UDP 不探测：没有 TCP 端口可连，标 unknown 而不是 unreachable
        if (kind == "udp") {
            return Health(
                key = url,
                protocol = "UDP",
                status = "unsupported",
                lastProbeAt = now,
                routeKey = routeKey,
            )
        }

        val parsed = BtTrackerUrl.parse(url)
        if (parsed !is BtTrackerUrl.Result.Ok) {
            return Health(
                key = url,
                protocol = "",
                status = "unreachable",
                lastProbeAt = now,
                consecutiveFailures = (prev?.consecutiveFailures ?: 0) + 1,
                failCount = (prev?.failCount ?: 0) + 1,
                successCount = prev?.successCount ?: 0,
                routeKey = routeKey,
            )
        }

        val v = parsed.value
        val port = if (v.port > 0) v.port else if (v.kind == BtTrackerUrl.Kind.https) 443 else 80
        val started = System.currentTimeMillis()

        return try {
            // 只做 TCP 连接 + （https 时）TLS 握手。
            // 不发 HTTP 请求：有些 Tracker 收到裸 HEAD 会直接断连，
            // 而我们要判断的是「这个端点活着」，不是它答不答 HTTP。
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(v.host, port), timeoutMs)
                socket.soTimeout = timeoutMs

                if (v.kind == BtTrackerUrl.Kind.https) {
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, v.host, port, true) as SSLSocket
                    tls.soTimeout = timeoutMs
                    tls.startHandshake()
                    tls.close()
                }
            } finally {
                runCatching { socket.close() }
            }

            val elapsed = System.currentTimeMillis() - started
            // 超过 3 秒算 slow：仍然可用，但排序会靠后
            Health(
                key = url,
                protocol = v.kind.name.uppercase(),
                status = if (elapsed <= 3000) "reachable" else "slow",
                latencyMs = elapsed,
                lastProbeAt = now,
                consecutiveFailures = 0,
                successCount = (prev?.successCount ?: 0) + 1,
                failCount = prev?.failCount ?: 0,
                routeKey = routeKey,
            )
        } catch (t: Throwable) {
            Log.d(TAG, "probe failed: ${v.host}:$port ${t.javaClass.simpleName}")
            Health(
                key = url,
                protocol = v.kind.name.uppercase(),
                status = "unreachable",
                latencyMs = -1,
                lastProbeAt = now,
                consecutiveFailures = (prev?.consecutiveFailures ?: 0) + 1,
                successCount = prev?.successCount ?: 0,
                failCount = (prev?.failCount ?: 0) + 1,
                routeKey = routeKey,
            )
        }
    }

    fun shutdown() {
        runCatching { io.shutdownNow() }
    }
}

/**
 * 候选排序。与 Motrix 的评分思路一致，参数按手机场景调过。
 *
 * 排序不是「谁分数高就一定要」，而是在 [BtTrackerPolicy.pickSupplements]
 * 之前给候选列表排好序。选择仍然遵守那两条硬约束：
 * 原始项优先、补充上限。
 */
object BtTrackerRanker {

    /** 未探测地址的保留比例：至少给 1 个名额，最多占上限的 1/5。 */
    fun explorationQuota(limit: Int): Int =
        maxOf(1, minOf(limit / 5, limit)).coerceAtMost(limit)

    /**
     * 给候选打分并排序。
     *
     * 分数构成（与 Motrix 同构，权重按手机调）：
     *  - 成功率 × 10
     *  - 可达 +1，不可达 -4
     *  - 上轮用过 +0.25（避免每次刷新都换一批，减少通告抖动）
     *  - 延迟每超 1 秒扣 0.1，最多扣 0.5
     */
    fun rank(
        candidates: List<String>,
        health: Map<String, BtTrackerProber.Health>,
        routeKey: String,
        previous: Set<String>,
    ): List<String> {
        fun score(url: String): Double {
            val h = health[url] ?: return if (url in previous) 0.05 else 0.0
            // 网络换代后的旧结论不参与
            if (h.routeKey != routeKey || !h.isFresh) {
                return if (url in previous) 0.05 else 0.0
            }
            if (h.status == "unsupported") {
                // 未探测（UDP）与「已测可用」同分，不吃惩罚
                return 1.0 + if (url in previous) 0.25 else 0.0
            }
            val base = h.successRate() * 10
            val reach = if (h.status == "unreachable") -4.0 else 1.0
            val reuse = if (url in previous) 0.25 else 0.0
            val latency = if (h.latencyMs > 0) {
                -0.1 * minOf(5, h.latencyMs / 1000)
            } else {
                0.0
            }
            return base + reach + reuse + latency
        }

        return candidates.sortedWith(
            compareByDescending<String> { score(it) }.thenBy { it },
        )
    }

    /**
     * 分成「已可用」与「未探测」两批，用于保证探索配额。
     */
    fun partition(
        ranked: List<String>,
        health: Map<String, BtTrackerProber.Health>,
        routeKey: String,
    ): Pair<List<String>, List<String>> {
        val known = ArrayList<String>()
        val unknown = ArrayList<String>()
        for (url in ranked) {
            val h = health[url]
            val hasData = h != null && h.isFresh && h.routeKey == routeKey &&
                h.status != "unsupported" && h.status != "unknown"
            if (hasData) known.add(url) else unknown.add(url)
        }
        return known to unknown
    }
}
