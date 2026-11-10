package com.clipisle.app.bt

/** Access only from the Runtime serial queue. IDs identify a single preview request. */
internal class BtPendingResolveCancels(
 private val limit: Int = 256,
 private val lifetimeMs: Long = 180_000,
) {
 enum class MarkResult { MARKED, FULL, INVALID }
 private val pending = LinkedHashMap<String, Long>()

 private fun prune(nowMs: Long) {
  val iterator = pending.entries.iterator()
  while (iterator.hasNext()) if (iterator.next().value <= nowMs) iterator.remove()
 }
 fun mark(requestId: String, nowMs: Long): MarkResult {
  if (!valid(requestId)) return MarkResult.INVALID
  prune(nowMs)
  if (requestId !in pending && pending.size >= limit) return MarkResult.FULL
  pending[requestId] = nowMs + lifetimeMs
  return MarkResult.MARKED
 }
 fun consume(requestId: String, nowMs: Long): Boolean {
  prune(nowMs)
  return pending.remove(requestId) != null
 }
 companion object {
  fun valid(requestId: String): Boolean = requestId.isNotBlank() && requestId.length <= 256
 }
}
