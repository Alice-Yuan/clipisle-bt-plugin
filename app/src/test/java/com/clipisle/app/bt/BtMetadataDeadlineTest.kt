package com.clipisle.app.bt

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class BtMetadataDeadlineTest {
 @Test fun `deadline completes a request even when its worker never responds`() {
  val executor = ScheduledThreadPoolExecutor(1)
  try {
   val result = CompletableFuture<String>()
   val cancelled = CountDownLatch(1)
   BtMetadataDeadline.arm(executor, result, 25, { "timeout" }, { cancelled.countDown() })
   assertEquals("timeout", result.get(2, TimeUnit.SECONDS))
   assertTrue(cancelled.await(2, TimeUnit.SECONDS))
   assertFalse(result.complete("late metadata"))
  } finally { executor.shutdownNow() }
 }
 @Test fun `completed request cannot subsequently trigger deadline cleanup`() {
  val executor = ScheduledThreadPoolExecutor(1)
  try {
   val result = CompletableFuture<String>()
   val cancelled = CountDownLatch(1)
   BtMetadataDeadline.arm(executor, result, 100, { "timeout" }, { cancelled.countDown() })
   assertTrue(result.complete("metadata"))
   assertEquals("metadata", result.get(1, TimeUnit.SECONDS))
   assertFalse(cancelled.await(250, TimeUnit.MILLISECONDS))
  } finally { executor.shutdownNow() }
 }
 @Test fun `diagnostics omit unavailable or stale DHT measurements`() {
  assertEquals(mapOf("elapsedMs" to 1000L), BtMetadataDiagnostics.snapshot(100, 1100, null, null))
  assertEquals(mapOf("elapsedMs" to 1000L), BtMetadataDiagnostics.snapshot(100, 1100, 45, 99))
  assertEquals(mapOf("elapsedMs" to 1000L), BtMetadataDiagnostics.snapshot(100, 1100, -1, 500))
  assertEquals(mapOf("elapsedMs" to 1000L, "dhtNodes" to 45L, "dhtSampleAgeMs" to 600L),
   BtMetadataDiagnostics.snapshot(100, 1100, 45, 500))
 }
 @Test fun `DHT bootstrap configuration uses distinct upstream router addresses`() {
  assertEquals(listOf("dht.libtorrent.org:25401", "dht.transmissionbt.com:6881", "router.bittorrent.com:6881"), BtDhtBootstrap.nodes)
  assertEquals(BtDhtBootstrap.nodes.size, BtDhtBootstrap.nodes.toSet().size)
  assertEquals(BtDhtBootstrap.nodes, BtDhtBootstrap.settingsValue.split(','))
 }
 @Test fun `stopping downloads preserves discovery while metadata preview is active`() {
  assertFalse(BtSessionOwnership.shouldPause(hasDownloadOwner = false, hasMetadataPreview = true))
  assertFalse(BtSessionOwnership.shouldPause(hasDownloadOwner = true, hasMetadataPreview = false))
  assertTrue(BtSessionOwnership.shouldPause(hasDownloadOwner = false, hasMetadataPreview = false))
 }
 @Test fun `current magnet trackers are retained when cached hash is reused`() {
  val hash = "a".repeat(40)
  val old = "magnet:?xt=urn:btih:$hash"
  val current = "$old&tr=udp%3A%2F%2Ftracker.example.org%3A6969%2Fannounce"
  val parsed = (BtInput.parse(current) as BtInput.ParseResult.Ok).info
  val binding = BtMetadataSource.bind(old, "magnet", parsed)
  assertEquals(current, binding.magnet)
  assertEquals(listOf("udp://tracker.example.org:6969/announce"),
   (BtInput.parse(binding.magnet) as BtInput.ParseResult.Ok).info.trackers)
  assertEquals(BtMetadataSource.Binding("", "torrent"), BtMetadataSource.bind("", "torrent", null))
 }
}
