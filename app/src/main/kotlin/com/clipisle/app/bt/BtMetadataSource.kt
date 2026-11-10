package com.clipisle.app.bt

/** Source context belongs to the current preview, even when info bytes are cached. */
internal object BtMetadataSource {
 data class Binding(val magnet: String, val source: String)
 fun bind(cachedMagnet: String, cachedSource: String, request: BtInput.MagnetInfo?): Binding =
  if (request == null) Binding(cachedMagnet, cachedSource) else Binding(request.magnet, "magnet")
}
