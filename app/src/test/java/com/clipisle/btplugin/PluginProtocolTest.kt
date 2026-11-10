package com.clipisle.btplugin
import org.junit.Assert.*
import org.junit.Test
class PluginProtocolTest {
 @Test fun boundsRequestsBeforeParsing() {
  assertTrue(PluginProtocol.validRequest("id", "parseInput", "{}"))
  assertFalse(PluginProtocol.validRequest("", "parseInput", "{}"))
  assertFalse(PluginProtocol.validRequest("id", "", "{}"))
  assertFalse(PluginProtocol.validRequest("x".repeat(257), "load", "{}"))
  assertFalse(PluginProtocol.validRequest("id", "load", "x".repeat(PluginProtocol.MAX_ARGUMENT_CHARS + 1)))
 }
 @Test fun restrictsExportToTaskIdsAndNonnegativeIndices() {
  assertTrue(PluginProtocol.validExport("task_01-A", 0))
  assertFalse(PluginProtocol.validExport("../outside", 0))
  assertFalse(PluginProtocol.validExport("task/name", 0))
  assertFalse(PluginProtocol.validExport("task", -1))
  assertFalse(PluginProtocol.validExport("a".repeat(129), 0))
 }
}
