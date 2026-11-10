package com.clipisle.app.bt.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracker 健康度排序。
 *
 * 这里只测纯函数部分（[BtTrackerRanker]）——真实探测需要网络，
 * 由真机验收覆盖。排序逻辑本身是纯的，值得单独钉住，
 * 因为它决定「有限的 20 个名额给谁」。
 */
class BtTrackerRankerTest {

    private fun health(
        url: String,
        status: String,
        latency: Long = 500,
        route: String = "net-1",
        successes: Int = 1,
        fails: Int = 0,
        consecutive: Int = 0,
        at: Long = System.currentTimeMillis(),
    ) = BtTrackerProber.Health(
        key = url,
        protocol = "HTTPS",
        status = status,
        latencyMs = latency,
        lastProbeAt = at,
        consecutiveFailures = consecutive,
        successCount = successes,
        failCount = fails,
        routeKey = route,
    )

    @Test
    fun `reachable sorts before unreachable`() {
        val ranked = BtTrackerRanker.rank(
            candidates = listOf("udp://dead.example:1/a", "udp://live.example:2/a"),
            health = mapOf(
                "udp://dead.example:1/a" to health("udp://dead.example:1/a", "unreachable", fails = 1),
                "udp://live.example:2/a" to health("udp://live.example:2/a", "reachable", latency = 100),
            ),
            routeKey = "net-1",
            previous = emptySet(),
        )
        assertEquals("udp://live.example:2/a", ranked.first())
    }

    @Test
    fun `lower latency wins among reachable`() {
        val fast = "udp://fast.example:1/a"
        val slow = "udp://slow.example:1/a"
        val ranked = BtTrackerRanker.rank(
            candidates = listOf(slow, fast),
            health = mapOf(
                fast to health(fast, "reachable", latency = 100),
                slow to health(slow, "reachable", latency = 2500),
            ),
            routeKey = "net-1",
            previous = emptySet(),
        )
        assertEquals(fast, ranked.first())
    }

    @Test
    fun `previously used gets a small bonus`() {
        val a = "udp://a.example:1/x"
        val b = "udp://b.example:1/x"
        // 两者都没有探测数据，都没有加分时按字典序；
        // 给 b 加「上轮用过」应当让它排前面
        val ranked = BtTrackerRanker.rank(
            candidates = listOf(a, b),
            health = emptyMap(),
            routeKey = "net-1",
            previous = setOf(b),
        )
        assertEquals(b, ranked.first())
    }

    @Test
    fun `stale health from another network is ignored`() {
        val a = "udp://a.example:1/x"
        val b = "udp://b.example:1/x"
        val ranked = BtTrackerRanker.rank(
            candidates = listOf(a, b),
            health = mapOf(
                // a 在旧网络上是坏的；换网后这条结论必须作废
                a to health(a, "unreachable", route = "net-0", fails = 5),
                b to health(b, "reachable", route = "net-1"),
            ),
            routeKey = "net-1",
            previous = emptySet(),
        )
        // a 的坏结论作废后不加分也不扣分，b 因为可达应该在前
        assertEquals(b, ranked.first())
    }

    @Test
    fun `expired health is ignored`() {
        val a = "udp://a.example:1/x"
        val b = "udp://b.example:1/x"
        val old = System.currentTimeMillis() - BtTrackerProber.FRESH_MS - 60_000
        val ranked = BtTrackerRanker.rank(
            candidates = listOf(a, b),
            health = mapOf(
                a to health(a, "unreachable", at = old, fails = 5),
                b to health(b, "reachable"),
            ),
            routeKey = "net-1",
            previous = emptySet(),
        )
        assertEquals(b, ranked.first())
    }

    @Test
    fun `unsupported udp probe does not get punished`() {
        val udp = "udp://udp.example:6969/a"
        val bad = "https://bad.example/a"
        val ranked = BtTrackerRanker.rank(
            candidates = listOf(bad, udp),
            health = mapOf(
                udp to health(udp, "unsupported"),
                bad to health(bad, "unreachable", fails = 3),
            ),
            routeKey = "net-1",
            previous = emptySet(),
        )
        // UDP 没探测不等于坏，应排在确认不可达的前面
        assertEquals(udp, ranked.first())
    }

    @Test
    fun `partition separates measured from unknown`() {
        val known = "https://known.example/a"
        val unknown = "udp://unknown.example:1/a"
        val (k, u) = BtTrackerRanker.partition(
            ranked = listOf(known, unknown),
            health = mapOf(known to health(known, "reachable")),
            routeKey = "net-1",
        )
        assertEquals(1, k.size)
        assertEquals(1, u.size)
        assertEquals(known, k[0])
        assertEquals(unknown, u[0])
    }

    @Test
    fun `exploration quota gives unknown endpoints a chance`() {
        // 上限 20 时至少留 1 个名额给没测过的地址，
        // 否则新地址永远进不来
        assertEquals(1, BtTrackerRanker.explorationQuota(5))
        assertEquals(1, BtTrackerRanker.explorationQuota(4))
        assertEquals(4, BtTrackerRanker.explorationQuota(20))
        assertTrue(BtTrackerRanker.explorationQuota(50) <= 50)
    }

    @Test
    fun `cooldown requires three consecutive failures`() {
        val url = "https://x.example/a"
        assertTrue(!health(url, "unreachable", consecutive = 2).inCooldown)
        assertTrue(health(url, "unreachable", consecutive = 3).inCooldown)
    }

    @Test
    fun `success rate reflects history`() {
        val url = "https://x.example/a"
        assertEquals(1.0, health(url, "reachable", successes = 5, fails = 0).successRate(), 0.001)
        assertEquals(0.5, health(url, "reachable", successes = 5, fails = 5).successRate(), 0.001)
        assertEquals(0.0, health(url, "unknown", successes = 0, fails = 0).successRate(), 0.001)
    }

    @Test
    fun `ranking is stable for equal scores`() {
        val a = "udp://a.example:1/x"
        val b = "udp://b.example:1/x"
        val r1 = BtTrackerRanker.rank(listOf(a, b), emptyMap(), "net-1", emptySet())
        val r2 = BtTrackerRanker.rank(listOf(b, a), emptyMap(), "net-1", emptySet())
        assertEquals(r1, r2)
    }
}
