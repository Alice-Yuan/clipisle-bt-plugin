package com.clipisle.app.bt.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracker URL 校验的离线测试。
 *
 * 覆盖任务书 12.1「URL 与文本」一栏：BOM/CRLF/重复/query/大小写/IPv6，
 * HTML、无效协议、超长行、异常重定向都要被拒绝。
 */
class BtTrackerUrlTest {

    private fun ok(url: String): BtTrackerUrl.Parsed {
        val r = BtTrackerUrl.parse(url)
        assertTrue("应通过: $url -> $r", r is BtTrackerUrl.Result.Ok)
        return (r as BtTrackerUrl.Result.Ok).value
    }

    private fun rejected(url: String): String {
        val r = BtTrackerUrl.parse(url)
        assertTrue("应拒绝: $url", r is BtTrackerUrl.Result.Reject)
        return (r as BtTrackerUrl.Result.Reject).reason
    }

    // ---- 协议 ----

    @Test
    fun `accepts udp http https`() {
        assertEquals(BtTrackerUrl.Kind.udp, ok("udp://tracker.example.org:6969/announce").kind)
        assertEquals(BtTrackerUrl.Kind.http, ok("http://tracker.example.org/announce").kind)
        assertEquals(BtTrackerUrl.Kind.https, ok("https://tracker.example.org/announce").kind)
    }

    @Test
    fun `rejects unsupported schemes`() {
        for (u in listOf(
            "ws://tracker.example.org/announce",
            "wss://tracker.example.org/announce",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/plain,abc",
        )) {
            rejected(u)
        }
    }

    // ---- 端口 ----

    @Test
    fun `udp requires explicit port`() {
        rejected("udp://tracker.example.org/announce")
    }

    @Test
    fun `rejects out of range port`() {
        rejected("udp://tracker.example.org:0/announce")
        rejected("udp://tracker.example.org:70000/announce")
        rejected("http://tracker.example.org:abc/announce")
    }

    // ---- 安全 ----

    @Test
    fun `rejects userinfo`() {
        rejected("http://user:pass@tracker.example.org/announce")
    }

    @Test
    fun `rejects fragment`() {
        rejected("http://tracker.example.org/announce#frag")
    }

    @Test
    fun `rejects control chars`() {
        rejected("http://tracker.example.org/ann\u0001ounce")
    }

    @Test
    fun `rejects missing host`() {
        rejected("http:///announce")
    }

    // ---- IPv6 ----

    @Test
    fun `accepts ipv6 with port`() {
        val v = ok("udp://[2001:db8::1]:6969/announce")
        assertTrue(v.isIpv6)
        assertEquals("2001:db8::1", v.host)
        assertEquals(6969, v.port)
    }

    @Test
    fun `accepts ipv6 loopback literal but marks non public`() {
        val v = ok("udp://[::1]:6969/announce")
        assertFalse(BtTrackerUrl.isPublicHost(v.host))
    }

    // ---- 大小写与 path ----

    @Test
    fun `host lowercased but path preserved`() {
        val v = ok("https://Tracker.Example.ORG/Announce/Path?Key=AbC")
        assertEquals("tracker.example.org", v.host)
        // path 与 query 原样保留，不整体小写
        assertEquals("/Announce/Path?Key=AbC", v.pathAndQuery)
    }

    @Test
    fun `percent encoding preserved`() {
        val v = ok("https://tracker.example.org/an%2Fnounce?x=%41")
        assertEquals("/an%2Fnounce?x=%41", v.pathAndQuery)
    }

    @Test
    fun `same host different path are different keys`() {
        val a = ok("https://tracker.example.org/a/announce")
        val b = ok("https://tracker.example.org/b/announce")
        assertTrue("路径不同不能合并", a.canonicalKey != b.canonicalKey)
    }

    @Test
    fun `same url different host case is same key`() {
        val a = ok("https://Tracker.Example.org/a")
        val b = ok("https://tracker.example.ORG/a")
        assertEquals(a.canonicalKey, b.canonicalKey)
    }

    // ---- 公网判定 ----

    @Test
    fun `rejects private and loopback hosts`() {
        for (h in listOf(
            "10.0.0.1", "127.0.0.1", "192.168.1.1", "172.16.0.1",
            "169.254.1.1", "224.0.0.1", "localhost", "nas.local",
        )) {
            assertFalse("应判为非公网: $h", BtTrackerUrl.isPublicHost(h))
        }
    }

    @Test
    fun `accepts public hosts`() {
        for (h in listOf("8.8.8.8", "tracker.example.org", "1.1.1.1")) {
            assertTrue("应判为公网: $h", BtTrackerUrl.isPublicHost(h))
        }
    }

    // ---- 文本解析 ----

    @Test
    fun `parses text with bom crlf and comments`() {
        val text = "\uFEFF# comment\r\n" +
            "udp://tracker.a.example:6969/announce\r\n" +
            "\r\n" +
            "https://tracker.b.example/announce\r\n"
        val r = BtTrackerUrl.parseText(text)
        assertEquals(2, r.valid.size)
        assertEquals(0, r.duplicates.size)
        assertEquals(0, r.invalid.size)
    }

    @Test
    fun `dedupes identical entries`() {
        val text = "udp://t.example:1/a\nudp://t.example:1/a\n"
        val r = BtTrackerUrl.parseText(text)
        assertEquals(1, r.valid.size)
        assertEquals(1, r.duplicates.size)
    }

    @Test
    fun `does not split on comma`() {
        // 逗号不是分隔符：整行只有一个 URL，第二个被当作路径一部分
        val text = "https://a.example/announce,https://b.example/announce\n"
        val r = BtTrackerUrl.parseText(text)
        assertEquals(1, r.valid.size)
        assertTrue(r.valid[0].pathAndQuery.contains(","))
    }

    @Test
    fun `reports invalid lines individually`() {
        val text = "udp://good.example:1/a\n" +
            "not-a-url\n" +
            "http://\n" +
            "ws://bad.example/x\n"
        val r = BtTrackerUrl.parseText(text)
        assertEquals(1, r.valid.size)
        assertEquals(3, r.invalid.size)
        // 行号是 1-based 且指向真实行
        assertEquals(2, r.invalid[0].line)
    }

    @Test
    fun `rejects over long single line`() {
        val long = "https://t.example/" + "a".repeat(BtTrackerUrl.MAX_LINE_BYTES)
        val r = BtTrackerUrl.parseText(long)
        assertEquals(0, r.valid.size)
        assertEquals(1, r.invalid.size)
    }

    @Test
    fun `require public host filters private`() {
        val text = "udp://192.168.1.5:6969/a\nudp://8.8.8.8:6969/a\n"
        val r = BtTrackerUrl.parseText(text, requirePublicHost = true)
        assertEquals(1, r.valid.size)
        assertEquals("8.8.8.8", r.valid[0].host)
        assertEquals(1, r.invalid.size)
    }

    @Test
    fun `display host omits default ports`() {
        val v = ok("https://tracker.example.org/announce")
        assertEquals("tracker.example.org", BtTrackerUrl.displayHost(v))
        val u = ok("udp://tracker.example.org:6969/announce")
        assertEquals("tracker.example.org:6969", BtTrackerUrl.displayHost(u))
    }

    @Test fun `query without slash keeps authority separate and preserves spelling`() {
        val parsed = ok("https://Tracker.Example.org?token=%2f&token=%2F")
        assertEquals("tracker.example.org", parsed.host)
        assertEquals("?token=%2f&token=%2F", parsed.pathAndQuery)
    }

    @Test fun `rejects malformed uri and ambiguous numeric hosts`() {
        listOf("https://a.example/a%zz", "https://a.example/a b", "https://[::1]garbage/a",
            "https://2130706433/a", "https://127.1/a", "https://0177.0.0.1/a", "https://0x7f000001/a",
            "https://[::::]/a", "https://a.example:/a").forEach { rejected(it) }
    }

    @Test fun `actual address checks reject expanded loopback mapped private and link local ranges`() {
        listOf("0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "fe9f::1",
            "febf::1", "fc00::1", "2002:7f00:1::1").forEach {
            assertFalse(it, BtTrackerUrl.isPublicAddress(java.net.InetAddress.getByName(it)))
        }
        assertTrue(BtTrackerUrl.isPublicAddress(java.net.InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun `subscription endpoint requires https and a public host`() {
        assertTrue(BtTrackerUrl.validateSubscriptionUrl("https://example.org/list.txt") is BtTrackerUrl.Result.Ok)
        listOf("http://example.org/list", "https://127.0.0.1/list", "https://nas.local/list")
            .forEach { assertTrue(BtTrackerUrl.validateSubscriptionUrl(it) is BtTrackerUrl.Result.Reject) }
    }
}
