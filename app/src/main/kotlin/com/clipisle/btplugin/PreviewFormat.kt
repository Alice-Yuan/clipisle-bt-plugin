package com.clipisle.btplugin

import java.util.Locale

/** Presentation only: these values are never used as torrent file paths. */
internal object PreviewFormat {
 fun bytes(bytes: Long): String {
  require(bytes >= 0)
  if (bytes < 1000) return "$bytes B"
  val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
  return String.format(Locale.ROOT, "%.2f %s", value, units[unit])
 }

 fun commonRootPrefix(name: String, paths: List<String>): String {
  if (name.isEmpty() || '/' in name || paths.isEmpty()) return ""
  val prefix = "$name/"
  return if (paths.all { it.startsWith(prefix) && it.length > prefix.length }) prefix else ""
 }
}
