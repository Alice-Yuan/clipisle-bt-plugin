package com.clipisle.btplugin

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TransferTicketLedgerTest {
    @Test fun `recreation may observe the same result but cannot start it twice`() {
        var now = 10L
        val ledger = TransferTicketLedger({ now }, { "ticket" })
        val id = ledger.create("enqueue", "payload")
        val first = requireNotNull(ledger.claim(id, false))
        assertTrue(ledger.startOnce(first))
        assertNull(ledger.claim(id, false))
        now += 10
        val recreated = requireNotNull(ledger.claim(id, true))
        assertSame(first, recreated)
        assertFalse(ledger.startOnce(recreated))
        first.result.complete("ok")
        assertEquals("ok", recreated.result.get())
    }
    @Test fun `recreated instance can start if rotation occurred before resume`() {
        val ledger = TransferTicketLedger({ 0L }, { "ticket" })
        val id = ledger.create("resume", "payload")
        val initial = requireNotNull(ledger.claim(id, false))
        val recreated = requireNotNull(ledger.claim(id, true))
        assertSame(initial, recreated)
        assertTrue(ledger.startOnce(recreated))
        assertFalse(ledger.startOnce(initial))
    }
    @Test fun `only one concurrent execution can claim a ticket`() {
        val ledger = TransferTicketLedger({ 0L }, { "ticket" })
        val ticket = requireNotNull(ledger.claim(ledger.create("enqueue", "payload"), false))
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(24)
        val starts = AtomicInteger()
        repeat(24) { pool.execute { if (ledger.startOnce(ticket)) starts.incrementAndGet(); latch.countDown() } }
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, starts.get())
    }
    @Test fun `fresh expiry and unclaimed recreation fail closed`() {
        var now = 0L
        val ledger = TransferTicketLedger({ now }, { "ticket" })
        val id = ledger.create("resume", "payload")
        assertNull(ledger.claim(id, true))
        now = 120_001
        assertNull(ledger.claim(id, false))
    }
    @Test fun `unknown method is rejected`() {
        val ledger = TransferTicketLedger({ 0L })
        assertThrows(IllegalArgumentException::class.java) { ledger.create("delete", "payload") }
    }
    @Test fun `completed tickets do not block future transfers at capacity`() {
        var serial = 0
        val ledger = TransferTicketLedger({ 0L }, { (++serial).toString() })
        val firstId = ledger.create("enqueue", "payload")
        val first = requireNotNull(ledger.claim(firstId, false))
        assertTrue(ledger.startOnce(first))
        first.result.complete("ok")
        repeat(31) { ledger.create("resume", "payload") }
        assertNotNull(ledger.create("resume", "new payload"))
        assertNull(ledger.claim(firstId, false))
    }
    @Test fun `watchdog deadline remains fixed across recreation`() {
        var now = 100L
        val ledger = TransferTicketLedger({ now }, { "ticket" })
        val id = ledger.create("enqueue", "payload")
        val initial = requireNotNull(ledger.claim(id, false))
        assertEquals(30_000L, ledger.remainingBeforeDeadline(initial))
        now += 17_000L
        val recreated = requireNotNull(ledger.claim(id, true))
        assertEquals(13_000L, ledger.remainingBeforeDeadline(recreated))
        now += 14_000L
        assertEquals(0L, ledger.remainingBeforeDeadline(recreated))
        initial.result.complete("OUTCOME_UNKNOWN")
        assertFalse(recreated.result.complete("late success"))
        assertEquals("OUTCOME_UNKNOWN", initial.result.get())
    }
}
