package com.clipisle.app.bt

/** Only expose a DHT reading taken during this request, never a stale cached zero. */
internal object BtMetadataDiagnostics {
 fun snapshot(startedAtMs: Long, nowMs: Long, nodes: Long?, observedAtMs: Long?): Map<String, Any> {
  val result = mutableMapOf<String, Any>("elapsedMs" to (nowMs - startedAtMs).coerceAtLeast(0))
  if (nodes != null && nodes >= 0 && observedAtMs != null && observedAtMs in startedAtMs..nowMs) {
   result["dhtNodes"] = nodes
   result["dhtSampleAgeMs"] = nowMs - observedAtMs
  }
  return result
 }
}
