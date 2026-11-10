package com.clipisle.app.bt

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BtPendingResolveCancelsTest {
 @Test fun `cancel before queued preview prevents the fetch from starting`() {
  val queue = Executors.newSingleThreadExecutor()
  try {
   val pending = BtPendingResolveCancels()
   val starts = AtomicInteger()
   val canceled = queue.submit<BtPendingResolveCancels.MarkResult> { pending.mark("request-1", 100) }
   val result = queue.submit<String> {
    if (pending.consume("request-1", 101)) "CANCELED" else { starts.incrementAndGet(); "STARTED" }
   }
   assertEquals(BtPendingResolveCancels.MarkResult.MARKED, canceled.get(1, TimeUnit.SECONDS))
   assertEquals("CANCELED", result.get(1, TimeUnit.SECONDS))
   assertEquals(0, starts.get())
   assertFalse(pending.consume("request-1", 102))
  } finally { queue.shutdownNow() }
 }
 @Test fun `markers are scoped to request and expire without canceling a later request`() {
  val pending = BtPendingResolveCancels(lifetimeMs = 1000)
  assertEquals(BtPendingResolveCancels.MarkResult.MARKED, pending.mark("first", 100))
  assertFalse(pending.consume("second", 200))
  assertTrue(pending.consume("first", 1099))
  assertEquals(BtPendingResolveCancels.MarkResult.MARKED, pending.mark("first", 1100))
  assertFalse(pending.consume("first", 2100))
 }
 @Test fun `a full marker set rejects new cancellations without evicting accepted ones`() {
  val pending = BtPendingResolveCancels(limit = 2, lifetimeMs = 1000)
  assertEquals(BtPendingResolveCancels.MarkResult.MARKED, pending.mark("a", 0))
  assertEquals(BtPendingResolveCancels.MarkResult.MARKED, pending.mark("b", 0))
  assertEquals(BtPendingResolveCancels.MarkResult.FULL, pending.mark("c", 1))
  assertTrue(pending.consume("a", 1))
  assertTrue(pending.consume("b", 1))
  assertEquals(BtPendingResolveCancels.MarkResult.MARKED, pending.mark("c", 1))
 }
 @Test fun `invalid request identifiers do not consume marker capacity`() {
  val pending = BtPendingResolveCancels(limit = 1)
  assertEquals(BtPendingResolveCancels.MarkResult.INVALID, pending.mark(" ", 0))
  assertEquals(BtPendingResolveCancels.MarkResult.INVALID, pending.mark("x".repeat(257), 0))
  assertEquals(BtPendingResolveCancels.MarkResult.MARKED, pending.mark("valid", 0))
 }
}
