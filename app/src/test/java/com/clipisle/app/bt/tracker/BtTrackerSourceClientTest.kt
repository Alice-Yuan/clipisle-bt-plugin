package com.clipisle.app.bt.tracker

import org.junit.Assert.*
import org.junit.Test

class BtTrackerSourceClientTest {
    @Test fun `invalid utf8 empty html and overlong lines cannot replace good cache`() {
        listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(0), "# comments only".toByteArray(),
            "<html>blocked</html>".toByteArray(), ("https://a.example/" + "a".repeat(2048)).toByteArray(),
            ByteArray(BtTrackerSourceClient.MAX_BODY_BYTES + 1) { 32 }).forEach {
            assertTrue(BtTrackerSourceClient.decodeBody(it) is BtTrackerSourceClient.EndpointResult.Fail)
        }
    }
    @Test fun `body parser keeps bounded minor errors and rejects widespread corruption`() {
        val valid = (0..9).joinToString("\n") { "udp://t$it.example:6969/a" }
        val accepted = BtTrackerSourceClient.decodeBody("$valid\nwrong".toByteArray()) as BtTrackerSourceClient.EndpointResult.Ok
        assertEquals(10, accepted.urls.size); assertEquals(1, accepted.droppedLines)
        assertTrue(BtTrackerSourceClient.decodeBody("$valid\nwrong\nwrong2".toByteArray()) is BtTrackerSourceClient.EndpointResult.Fail)
        assertTrue(BtTrackerSourceClient.decodeBody((0..500).joinToString("\n") { "udp://t$it.example:6969/a" }.toByteArray()) is BtTrackerSourceClient.EndpointResult.Fail)
    }
    @Test fun `retry after supports seconds and dates with a one day cap`() {
        assertEquals(5000L, BtTrackerSourceClient.retryAfter("5", 0))
        assertEquals(86_400_000L, BtTrackerSourceClient.retryAfter("999999999", 0))
        assertEquals(10_000L, BtTrackerSourceClient.retryAfter("Thu, 01 Jan 1970 00:00:10 GMT", 0))
        assertEquals(0L, BtTrackerSourceClient.retryAfter("not a date", 0))
    }
}
