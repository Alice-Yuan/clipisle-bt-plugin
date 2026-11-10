package com.clipisle.app.bt.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracker 合并策略的离线测试。
 *
 * 覆盖任务书 12.1「合并与 tier」「私有/未知」两栏。
 */
class BtTrackerPolicyTest {

    private fun original(url: String, tier: Int = 0) =
        BtTrackerPolicy.Original(
            url = url,
            canonicalKey = keyOf(url),
            tier = tier,
            from = "torrent",
        )

    private fun supplement(url: String, source: String = "xiu2_best") =
        BtTrackerPolicy.Supplement(
            url = url,
            canonicalKey = keyOf(url),
            sourceIds = listOf(source),
        )

    private fun keyOf(url: String): String {
        val r = BtTrackerUrl.parse(url)
        return (r as BtTrackerUrl.Result.Ok).value.canonicalKey
    }

    // ---- public 与 private ----

    @Test
    fun `private task gets no supplements`() {
        val r = BtTrackerPolicy.effective(
            originals = listOf(original("udp://orig.example:1/a")),
            supplements = listOf(supplement("udp://pub.example:2/a")),
            privacy = BtTrackerPolicy.Privacy.private,
            supplementEnabled = true,
        )
        assertEquals(1, r.urls.size)
        assertTrue(r.usedSupplements.isEmpty())
        assertNotNull(r.skipReason)
    }

    @Test
    fun `unknown task gets no supplements`() {
        val r = BtTrackerPolicy.effective(
            originals = listOf(original("udp://orig.example:1/a")),
            supplements = listOf(supplement("udp://pub.example:2/a")),
            privacy = BtTrackerPolicy.Privacy.unknown,
            supplementEnabled = true,
        )
        assertTrue("unknown 不能补公共", r.usedSupplements.isEmpty())
        assertNotNull(r.skipReason)
    }

    @Test
    fun `public task gets supplements`() {
        val r = BtTrackerPolicy.effective(
            originals = listOf(original("udp://orig.example:1/a")),
            supplements = listOf(supplement("udp://pub.example:2/a")),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        assertEquals(2, r.urls.size)
        assertEquals(1, r.usedSupplements.size)
        assertNull(r.skipReason)
    }

    @Test
    fun `disabled supplement switch blocks supplements`() {
        val r = BtTrackerPolicy.effective(
            originals = listOf(original("udp://orig.example:1/a")),
            supplements = listOf(supplement("udp://pub.example:2/a")),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = false,
        )
        assertTrue(r.usedSupplements.isEmpty())
    }

    // ---- tier ----

    @Test
    fun `original tiers preserved and supplements appended after`() {
        val r = BtTrackerPolicy.effective(
            originals = listOf(
                original("udp://a.example:1/a", tier = 0),
                original("udp://b.example:2/a", tier = 1),
            ),
            supplements = listOf(
                supplement("udp://c.example:3/a"),
                supplement("udp://d.example:4/a"),
            ),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        assertEquals(listOf(0, 1, 2, 2), r.tiers)
    }

    // ---- 上限 ----

    @Test
    fun `supplements capped at twenty`() {
        val many = (1..30).map { supplement("udp://pub$it.example:1/a") }
        val r = BtTrackerPolicy.effective(
            originals = listOf(original("udp://orig.example:1/a")),
            supplements = many,
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        assertEquals(BtTrackerPolicy.MAX_SUPPLEMENTS + 1, r.urls.size)
    }

    @Test
    fun `originals are not truncated by the supplement cap`() {
        val manyOriginals = (1..30).map { original("udp://orig$it.example:1/a") }
        val r = BtTrackerPolicy.effective(
            originals = manyOriginals,
            supplements = (1..30).map { supplement("udp://pub$it.example:1/a") },
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        // 30 个原始项必须全部保留
        assertEquals(30, r.urls.take(30).size)
        assertEquals(30 + BtTrackerPolicy.MAX_SUPPLEMENTS, r.urls.size)
    }

    // ---- 重叠 ----

    @Test
    fun `original wins over identical supplement`() {
        val url = "udp://same.example:6969/announce"
        val r = BtTrackerPolicy.effective(
            originals = listOf(original(url, tier = 0)),
            supplements = listOf(supplement(url)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        assertEquals("只应出现一次", 1, r.urls.size)
        assertEquals(0, r.tiers[0])
        assertTrue(r.usedSupplements.isEmpty())
    }

    // ---- 排除 ----

    @Test
    fun `excluded supplement keys are skipped`() {
        val a = "udp://pub-a.example:1/a"
        val b = "udp://pub-b.example:2/a"
        val r = BtTrackerPolicy.effective(
            originals = listOf(original("udp://orig.example:1/a")),
            supplements = listOf(supplement(a), supplement(b)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
            excludedKeys = setOf(keyOf(a)),
        )
        assertEquals(1, r.usedSupplements.size)
        assertEquals(b, r.usedSupplements[0].url)
    }

    // ---- unowned ----

    @Test(expected = IllegalArgumentException::class)
    fun `private task rejects unowned addresses`() {
        val r = BtTrackerPolicy.effective(
            originals = listOf(original("udp://orig.example:1/a")),
            unowned = listOf(original("udp://unknown.example:2/a", tier = 0)),
            supplements = emptyList(),
            privacy = BtTrackerPolicy.Privacy.private,
            supplementEnabled = true,
        )
        assertEquals(2, r.urls.size)
    }

    @Test
    fun `last original tier prevents overflowing supplementary tier`() {
        val r = BtTrackerPolicy.effective(listOf(original("https://original.example/announce", 255)),
            supplements = listOf(supplement("udp://extra.example:6969/announce")),
            privacy = BtTrackerPolicy.Privacy.public, supplementEnabled = true)
        assertEquals(listOf(255), r.tiers)
        assertTrue(r.usedSupplements.isEmpty())
        assertEquals("TRACKER_TIER_EXHAUSTED", r.skipReason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out of range original tier is rejected rather than flattened`() {
        BtTrackerPolicy.effective(listOf(original("https://original.example/announce", 256)),
            privacy = BtTrackerPolicy.Privacy.public, supplementEnabled = false)
    }

    @Test
    fun `duplicate first round does not hide valid later candidates`() {
        val original = "https://original.example/announce"
        val picked = BtTrackerPolicy.pickSupplements(emptyList(), linkedMapOf("s" to listOf(
            supplement(original), supplement("https://valid.example/announce"))), setOf(keyOf(original)), emptySet())
        assertEquals(listOf("https://valid.example/announce"), picked.map { it.url })
    }

    @Test
    fun `opaque id conceals passkeys and still revokes exact address`() {
        val url = "https://original.example/announce?passkey=secret"
        val id = BtTrackerPolicy.idForUrl(url)
        assertEquals(64, id.length)
        assertTrue(BtTrackerPolicy.disabled(url, listOf(id)))
        assertTrue(!BtTrackerPolicy.disabled(url + "2", listOf(id)))
    }

    // ---- 候选选择 ----

    @Test
    fun `user public trackers come first`() {
        val user = listOf(supplement("udp://user.example:1/a", "custom"))
        val fromSources = linkedMapOf(
            "xiu2_best" to listOf(supplement("udp://xiu2.example:1/a", "xiu2_best")),
        )
        val picked = BtTrackerPolicy.pickSupplements(
            userPublic = user,
            bySource = fromSources,
            originalKeys = emptySet(),
            excludedKeys = emptySet(),
        )
        assertEquals("udp://user.example:1/a", picked[0].url)
    }

    @Test
    fun `pick excludes originals and disabled`() {
        val orig = "udp://orig.example:1/a"
        val disabled = "udp://disabled.example:1/a"
        val picked = BtTrackerPolicy.pickSupplements(
            userPublic = listOf(supplement(orig), supplement(disabled)),
            bySource = linkedMapOf(
                "s1" to listOf(supplement("udp://ok.example:1/a", "s1")),
            ),
            originalKeys = setOf(keyOf(orig)),
            excludedKeys = setOf(keyOf(disabled)),
        )
        assertEquals(1, picked.size)
        assertEquals("udp://ok.example:1/a", picked[0].url)
    }

    @Test
    fun `pick rotates across sources`() {
        val picked = BtTrackerPolicy.pickSupplements(
            userPublic = emptyList(),
            bySource = linkedMapOf(
                "s1" to (1..3).map { supplement("udp://s1-$it.example:1/a", "s1") },
                "s2" to (1..3).map { supplement("udp://s2-$it.example:1/a", "s2") },
            ),
            originalKeys = emptySet(),
            excludedKeys = emptySet(),
            limit = 4,
        )
        assertEquals(4, picked.size)
        // 轮转：s1-1, s2-1, s1-2, s2-2
        assertEquals("udp://s1-1.example:1/a", picked[0].url)
        assertEquals("udp://s2-1.example:1/a", picked[1].url)
        assertEquals("udp://s1-2.example:1/a", picked[2].url)
        assertEquals("udp://s2-2.example:1/a", picked[3].url)
    }

    // ---- digest ----

    @Test
    fun `digest stable for same list`() {
        val r1 = BtTrackerPolicy.effective(
            originals = listOf(original("udp://a.example:1/a", 0)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = false,
        )
        val r2 = BtTrackerPolicy.effective(
            originals = listOf(original("udp://a.example:1/a", 0)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = false,
        )
        assertEquals(r1.toDigest(), r2.toDigest())
    }

    @Test
    fun `digest changes when list changes`() {
        val r1 = BtTrackerPolicy.effective(
            originals = listOf(original("udp://a.example:1/a")),
            supplements = emptyList(),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        val r2 = BtTrackerPolicy.effective(
            originals = listOf(original("udp://a.example:1/a")),
            supplements = listOf(supplement("udp://b.example:2/a")),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        assertTrue(r1.toDigest() != r2.toDigest())
    }

    @Test
    fun `full twenty item selection still merges provenance from later sources`() {
        val custom = (1..20).map { supplement("https://tracker$it.example/announce").copy(sourceIds = listOf("custom")) }
        val result = BtTrackerPolicy.pickSupplements(custom, linkedMapOf(
            "one" to listOf(custom.first().copy(sourceIds = listOf("one"))),
            "two" to listOf(custom.first().copy(sourceIds = listOf("two")),
                supplement("https://overflow.example/announce").copy(sourceIds = listOf("two")))), emptySet(), emptySet())
        assertEquals(custom.map { it.url }, result.map { it.url })
        assertEquals(listOf("custom", "one", "two"), result.first().sourceIds)
        assertEquals(20, result.size)
    }

    // ---- diff ----

    @Test
    fun `diff reports adds and removes`() {
        val a = "udp://a.example:1/announce"
        val b = "udp://b.example:2/announce"

        val current = BtTrackerPolicy.effective(
            originals = listOf(original(a)),
            supplements = emptyList(),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        val desired = BtTrackerPolicy.effective(
            originals = listOf(original(a)),
            supplements = listOf(supplement(b)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        val d = BtTrackerPolicy.diff(current, desired, setOf(keyOf(a)))
        assertEquals(1, d.added.size)
        assertEquals(0, d.removed.size)
        assertEquals(b, d.added[0])
    }

    @Test
    fun `diff empty when identical`() {
        val a = "udp://a.example:1/announce"
        val e1 = BtTrackerPolicy.effective(
            originals = listOf(original(a)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        val e2 = BtTrackerPolicy.effective(
            originals = listOf(original(a)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        assertTrue(BtTrackerPolicy.diff(e1, e2, setOf(keyOf(a))).isEmpty)
    }

    @Test
    fun `turning supplements off diffs to removal`() {
        val orig = "udp://orig.example:1/announce"
        val pub = "udp://pub.example:2/announce"
        val on = BtTrackerPolicy.effective(
            originals = listOf(original(orig)),
            supplements = listOf(supplement(pub)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = true,
        )
        val off = BtTrackerPolicy.effective(
            originals = listOf(original(orig)),
            supplements = listOf(supplement(pub)),
            privacy = BtTrackerPolicy.Privacy.public,
            supplementEnabled = false,
        )
        val d = BtTrackerPolicy.diff(on, off, setOf(keyOf(orig)))
        assertEquals(1, d.removed.size)
        assertEquals(pub, d.removed[0])
        assertEquals(0, d.added.size)
    }
}
