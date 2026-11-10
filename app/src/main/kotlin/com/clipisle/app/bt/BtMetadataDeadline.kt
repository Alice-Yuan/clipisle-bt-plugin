package com.clipisle.app.bt

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Keeps a request deadline independent of a blocked native or runtime worker. */
internal object BtMetadataDeadline {
 fun <T> arm(scheduler: ScheduledExecutorService, future: CompletableFuture<T>, delayMs: Long,
             timeoutValue: () -> T, onTimeout: () -> Unit) {
  val timer = scheduler.schedule({ if (future.complete(timeoutValue())) onTimeout() }, delayMs, TimeUnit.MILLISECONDS)
  future.whenComplete { _, _ -> timer.cancel(false) }
 }
}
