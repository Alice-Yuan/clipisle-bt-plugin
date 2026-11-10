package com.clipisle.app.bt.tracker

import okhttp3.Call
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Coordinates cancellation with publishing a call on another thread. */
internal class BtTrackerCallGate {
 private val cancelled = AtomicBoolean(false)
 private val call = AtomicReference<Call?>()
 fun isCancelled() = cancelled.get()
 fun current(): Call? = call.get()
 fun requestCancel(): Call? { cancelled.set(true); return call.get() }
 fun publish(next: Call): Boolean {
  call.set(next)
  if (cancelled.get()) { next.cancel(); call.compareAndSet(next, null); return false }
  return true
 }
 fun clear(expected: Call) { call.compareAndSet(expected, null) }
}
