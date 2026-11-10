package com.clipisle.btplugin

/** Size limits bound binder payloads before JSON parsing and before queuing work. */
internal object PluginProtocol {
 const val MAX_ARGUMENT_CHARS = 128 * 1024
 fun validRequest(id: String, method: String, raw: String): Boolean =
  id.isNotBlank() && id.length <= 256 && method.isNotBlank() && method.length <= 128 && raw.length <= MAX_ARGUMENT_CHARS
 fun validExport(taskId: String, index: Int): Boolean =
  index >= 0 && taskId.matches(Regex("[A-Za-z0-9_-]{1,128}"))
}
