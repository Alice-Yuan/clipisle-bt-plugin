package com.clipisle.btplugin

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.*
import android.util.Log
import com.clipisle.app.bt.BtRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PluginService : Service() {
 private val workers = java.util.concurrent.ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(32))
 private val cancellations = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(8))
 private val timer = Executors.newSingleThreadScheduledExecutor()
 private val subscribers = java.util.concurrent.CopyOnWriteArraySet<Messenger>()
 private val runtime get() = BtRuntime.get(applicationContext)
 private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
  override fun handleMessage(msg: Message) {
   if (msg.what != 1 || msg.replyTo == null) return
   if (!isHost(msg.sendingUid)) { Log.w("ClipIslePlugin", "Rejected caller uid=${msg.sendingUid}"); return }
   val reply = msg.replyTo; val id = msg.data.getString("requestId") ?: return
   val method = msg.data.getString("method") ?: return; val raw = msg.data.getString("arguments") ?: "{}"
   fun replyError(code: String) { try { reply.send(Message.obtain(null, 2).apply { data = Bundle().apply { putString("requestId", id.take(256)); putString("result", JSONObject().put("ok", false).put("code", code).toString()) } }) } catch (_: Exception) { } }
   if (!PluginProtocol.validRequest(id, method, raw)) { replyError("INVALID_ARGUMENT"); return }
   val executor = if (method in setOf("cancelResolve", "cancelTrackerSync")) cancellations else workers
   try { executor.execute {
    val result = try { if (!HostTrust.isAuthorized(this@PluginService)) mapOf("ok" to false, "code" to "AUTHORIZATION_REQUIRED", "requestUi" to mapOf("type" to "launch", "action" to "com.clipisle.plugin.AUTHORIZE", "packageName" to packageName)) else execute(method, JSONObject(raw), reply) } catch (e: Exception) { Log.w("ClipIslePlugin", "Failed method=$method type=${e.javaClass.simpleName}"); mapOf("ok" to false, "code" to "ENGINE_UNAVAILABLE", "message" to "插件操作失败") } catch (e: LinkageError) { Log.e("ClipIslePlugin", "Native linkage failed method=$method type=${e.javaClass.simpleName}"); mapOf("ok" to false, "code" to "ENGINE_UNAVAILABLE", "message" to "插件引擎不可用") }
    val encoded = toJson(result).toString().let { if (it.toByteArray(Charsets.UTF_8).size > 512 * 1024) "{\"ok\":false,\"code\":\"RESPONSE_TOO_LARGE\"}" else it }
    try { reply.send(Message.obtain(null, 2).apply { data = Bundle().apply { putString("requestId", id); putString("result", encoded) } }) } catch (_: Exception) { subscribers.remove(reply) }
   } } catch (_: java.util.concurrent.RejectedExecutionException) {
    replyError("BUSY")
   }
  }
 })
 override fun onCreate() { super.onCreate(); timer.scheduleWithFixedDelay({
  if (subscribers.isNotEmpty() && !HostTrust.isAuthorized(this)) subscribers.clear()
  if (subscribers.isNotEmpty()) try {
   val payload = JSONObject().put("type", "snapshot").put("data", toJson(runtime.getSnapshot().get())).toString()
   subscribers.forEach { sub -> try { sub.send(Message.obtain(null, 3).apply { data = Bundle().apply { putString("event", payload) } }) } catch (_: Exception) { subscribers.remove(sub) } }
  } catch (_: Exception) { }
 }, 1, 1, TimeUnit.SECONDS) }
 private fun isHost(uid: Int) = packageManager.getPackagesForUid(uid)?.contains("com.clipisle.app") == true
 override fun onBind(intent: Intent): IBinder = messenger.binder
 override fun onDestroy() { timer.shutdownNow(); cancellations.shutdownNow(); workers.shutdown(); super.onDestroy() }
 private fun execute(method: String, a: JSONObject, reply: Messenger): Any? {
  fun str(k: String) = a.optString(k, "").also { require(it.isNotBlank() && it.length <= 65536) }
  fun num(k: String): Int? = if (a.has(k) && !a.isNull(k)) a.getInt(k).also { require(it >= 0) } else null
  fun bool(k: String): Boolean? = if (a.has(k) && !a.isNull(k)) a.getBoolean(k) else null
  fun strings(k: String): List<String> = a.optJSONArray(k)?.let { ar -> (0 until ar.length()).map { ar.getString(it) } } ?: emptyList()
  if (Build.VERSION.SDK_INT >= 34 && method in setOf("enqueue", "resume", "retry")) {
   val token = TransferTickets.create(if (method == "enqueue") "enqueue" else "resume", a)
   return mapOf("ok" to true, "requestUi" to mapOf("type" to "launch", "action" to "com.clipisle.plugin.CONFIRM_TRANSFER", "packageName" to packageName, "extras" to mapOf("token" to token)))
  }
  return when (method) {
   "manifest" -> JSONObject(getString(R.string.plugin_manifest))
   "protocolVersion" -> 1
   "subscribe" -> synchronized(subscribers) { if (subscribers.size >= 8 && !subscribers.contains(reply)) mapOf("ok" to false, "code" to "BUSY") else { subscribers.add(reply); mapOf("ok" to true) } }
   "unsubscribe" -> { subscribers.remove(reply); mapOf("ok" to true) }
   "invokeAction" -> invokeAction(a, reply)
   "parseInput" -> { val input = str("input"); if (input.startsWith("magnet:")) preview(runtime.resolveMagnet(input, a.optString("requestId").takeIf { it.isNotBlank() && it.length <= 256 } ?: UUID.randomUUID().toString(), 90000).get(), input) else if (input.startsWith("clipisle-torrent:content://")) preview(runtime.importTorrent(readTorrent(input.removePrefix("clipisle-torrent:")), UUID.randomUUID().toString(), "").get(), "") else mapOf("ok" to false, "code" to "UNSUPPORTED_INPUT") }
   "capabilities" -> runtime.capabilities().get()
   "load" -> mapOf("ok" to true, "taskCount" to runtime.loadFromDisk().get())
   "resolveMagnet" -> runtime.resolveMagnet(str("magnet"), str("requestId"), (num("timeoutMs") ?: 90000).coerceIn(1000, 90000)).get()
   "cancelResolve" -> runtime.cancelResolve(str("requestId")).get()
   "importTorrent" -> runtime.importTorrent(readTorrent(str("uri")), str("requestId"), a.optString("displayName", "")).get()
   "enqueue" -> {
    val ar = a.getJSONArray("selectedFileIndices"); require(ar.length() <= 20000)
    val indices = (0 until ar.length()).map { ar.getInt(it).also { n -> require(n >= 0) } }
    val root = File(filesDir, "downloads").apply { mkdirs() }
    runtime.enqueue(a.optString("magnet", ""), indices, root.absolutePath, str("taskId"), a.optString("metadataKey").takeIf { it.isNotEmpty() }).get()
   }
   "getSnapshot" -> runtime.getSnapshot().get()
   "getTaskFiles" -> runtime.getTaskFiles(str("taskId")).get()
   "exportFile" -> { val uri = Uri.Builder().scheme("content").authority("com.clipisle.btplugin.exports").appendPath(str("taskId")).appendPath((num("fileIndex") ?: error("index")).toString()).build(); grantUriPermission("com.clipisle.app", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); mapOf("ok" to true, "uri" to uri.toString()) }
   "pause" -> runtime.pause(str("taskId")).get()
   "resume", "retry" -> runtime.resume(str("taskId")).get()
   "cancel" -> runtime.cancel(str("taskId")).get()
   "removeHistory" -> runtime.removeHistory(str("taskId")).get()
   "deleteTask" -> runtime.deleteTask(str("taskId"), bool("deleteFiles") ?: false).get()
   "removeArtifact" -> runtime.removeArtifact(str("taskId"), num("fileIndex") ?: error("index")).get()
   "pauseAll" -> mapOf("ok" to true, "paused" to runtime.pauseAllRunning().get())
   "getSettings" -> runtime.getSettings().get()
   "updateSettings" -> runtime.updateSettings(bool("unmeteredOnly"), num("maxActiveDownloads"), num("downloadRateLimit"), num("uploadRateLimit")).get()
   "getTrackerConfig" -> runtime.getTrackerConfig().get()
   "getCustomTrackers" -> runtime.getCustomTrackers().get()
   "getTrackerSource" -> runtime.getTrackerSource(a.optString("id").ifEmpty { str("sourceId") }).get()
   "saveTrackerSource" -> runtime.saveTrackerSource(a.optString("id").takeIf { it.isNotEmpty() }, a.optString("name"), strings("endpoints"), bool("enabled") ?: true, num("expectedRevision"), bool("delete") ?: false).get()
   "restoreRecommendedSources" -> runtime.restoreRecommendedSources(num("expectedRevision")).get()
   "updateTrackerConfig" -> runtime.updateTrackerConfig(jsonMap(a.optJSONObject("patch") ?: JSONObject()), num("expectedRevision")).get()
   "saveCustomTrackers" -> runtime.saveCustomTrackers(strings("urls"), num("expectedRevision"), bool("validateOnly") ?: false).get()
   "syncTrackerSources" -> runtime.syncTrackers(bool("manual") ?: false).get()
   "getTrackerSyncState" -> runtime.getTrackerSyncState(a.optString("operationId")).get()
   "cancelTrackerSync" -> runtime.cancelTrackerSync(a.optString("operationId")).get()
   "getTrackerCatalog" -> runtime.getTrackerCatalog(num("cursor") ?: 0, num("pageSize") ?: 50, bool("disabledOnly") ?: false).get()
   "getTaskTrackers" -> runtime.getTaskTrackers(str("taskId"), num("taskGeneration"), num("cursor") ?: 0, num("pageSize") ?: 50).get()
   "previewTaskTrackerUpdate" -> runtime.previewTaskTrackerUpdate(str("taskId"), num("taskGeneration"), if(a.has("catalogRevision")) a.getLong("catalogRevision") else null, a.optString("desiredMode", "latest")).get()
   "applyTaskTrackerUpdate" -> runtime.applyTaskTrackerUpdate(a.optString("planToken")).get()
   else -> mapOf("ok" to false, "code" to "NOT_IMPLEMENTED")
  }
 }
 private fun readTorrent(raw: String): ByteArray {
  val uri = Uri.parse(raw); require(uri.scheme == "content")
  return contentResolver.openInputStream(uri)!!.use { input -> val out = ByteArrayOutputStream(); val buf = ByteArray(65536)
   while (true) { val n = input.read(buf); if (n < 0) break; require(out.size().toLong() + n <= 16 * 1024 * 1024); out.write(buf, 0, n) }; out.toByteArray() }
 }
 private fun preview(result: Map<String, Any>, magnet: String): Any {
  if (result["ok"] != true) return result
  val files = result["files"] as? List<*> ?: emptyList<Any>()
  val paths = files.mapNotNull { (it as? Map<*, *>)?.get("path") as? String }
  val root = if (paths.size == files.size) PreviewFormat.commonRootPrefix(result["displayName"]?.toString() ?: "", paths) else ""
  val items = files.mapNotNull { f ->
   val m = f as? Map<*, *> ?: return@mapNotNull null
   val path = m["path"] as? String ?: ""
   val size = (m["lengthBytes"] as? Number)?.toLong()
   mapOf("id" to m["fileIndex"].toString(), "title" to path.removePrefix(root), "subtitle" to if (size != null && size >= 0) PreviewFormat.bytes(size) else "", "selected" to true)
  }
  return mapOf("ok" to true, "page" to mapOf("id" to "selectFiles", "title" to (result["displayName"] ?: "选择文件"), "fields" to listOf(mapOf("id" to "metadataKey", "type" to "hidden", "value" to (result["dedupeKey"] ?: "")), mapOf("id" to "magnet", "type" to "hidden", "value" to magnet)), "items" to items, "actions" to listOf(mapOf("id" to "enqueue", "label" to "开始下载", "primary" to true))))
 }
 private fun refreshed(result: Map<String, Any>, page: Any): Any =
  if (result["ok"] == true) page else result
 private fun settingsPage(): Any {
  val settings = runtime.getSettings().get()
  return mapOf("ok" to true, "page" to mapOf("id" to "settings", "title" to "磁力下载设置", "fields" to listOf(
   mapOf("id" to "unmeteredOnly", "type" to "switch", "label" to "仅在非计费网络下载", "value" to (settings["unmeteredOnly"] ?: true)),
   mapOf("id" to "maxActiveDownloads", "type" to "select", "label" to "同时下载任务", "value" to (settings["maxActiveDownloads"] ?: 1).toString(), "options" to listOf(mapOf("value" to "1", "label" to "1"), mapOf("value" to "2", "label" to "2"))),
   mapOf("id" to "downloadRateLimit", "type" to "number", "label" to "下载限速（字节/秒，0 为不限速）", "value" to (settings["downloadRateLimit"] ?: 0)),
   mapOf("id" to "uploadRateLimit", "type" to "number", "label" to "上传限速（字节/秒，0 为不限速）", "value" to (settings["uploadRateLimit"] ?: 0))
  ), "items" to emptyList<Any>(), "actions" to listOf(mapOf("id" to "saveSettings", "label" to "保存", "primary" to true), mapOf("id" to "trackers", "label" to "Tracker 来源"))))
 }
 private fun trackersPage(): Any {
  val config = runtime.getTrackerConfig().get()
  val sources = config["sources"] as? List<*> ?: emptyList<Any>()
  return mapOf("ok" to true, "page" to mapOf("id" to "trackers", "title" to "Tracker 来源", "fields" to listOf(mapOf("id" to "revision", "type" to "hidden", "value" to config["revision"])),
   "items" to sources.mapNotNull { item -> val m = item as? Map<*, *> ?: return@mapNotNull null; mapOf("id" to (m["id"] ?: ""), "title" to (m["name"] ?: ""), "subtitle" to if (m["enabled"] == true) "已启用" else "已停用", "action" to "trackerSource") },
   "actions" to listOf(mapOf("id" to "newTrackerSource", "label" to "新增来源"), mapOf("id" to "customTrackers", "label" to "自定义 Tracker"), mapOf("id" to "syncTrackers", "label" to "同步来源"), mapOf("id" to "restoreTrackers", "label" to "恢复推荐来源"))))
 }
 private fun trackerSourcePage(source: Map<String, Any?>): Any {
  val id = source["id"]?.toString() ?: ""
  val actions = mutableListOf(mapOf("id" to "saveTrackerSource", "label" to "保存", "primary" to true))
  if (id.isNotEmpty()) actions.add(mapOf("id" to "confirmDeleteTrackerSource", "label" to "删除来源"))
  return mapOf("ok" to true, "page" to mapOf("id" to "trackerSource", "title" to if (id.isEmpty()) "新增来源" else "编辑来源", "fields" to listOf(
   mapOf("id" to "id", "type" to "hidden", "value" to id), mapOf("id" to "revision", "type" to "hidden", "value" to source["revision"]),
   mapOf("id" to "name", "type" to "text", "label" to "名称", "value" to (source["name"] ?: "")),
   mapOf("id" to "endpoints", "type" to "text", "label" to "HTTPS 地址（每行一个，最多两个）", "multiline" to true, "value" to ((source["endpoints"] as? List<*>)?.joinToString("\n") ?: "")),
   mapOf("id" to "enabled", "type" to "switch", "label" to "启用", "value" to (source["enabled"] ?: true))
  ), "items" to emptyList<Any>(), "actions" to actions))
 }
 private fun customTrackersPage(): Any {
  val config = runtime.getCustomTrackers().get()
  return mapOf("ok" to true, "page" to mapOf("id" to "customTrackers", "title" to "自定义 Tracker", "fields" to listOf(
   mapOf("id" to "revision", "type" to "hidden", "value" to config["revision"]), mapOf("id" to "urls", "type" to "text", "label" to "公共 Tracker 地址（每行一个，最多 20 个）", "multiline" to true, "value" to ((config["urls"] as? List<*>)?.joinToString("\n") ?: ""))
  ), "items" to emptyList<Any>(), "actions" to listOf(mapOf("id" to "saveCustomTrackers", "label" to "保存并验证", "primary" to true))))
 }
 private fun trackerSyncPage(state: Map<String, Any>): Any {
  val status = when (state["state"]?.toString()) {
   "running" -> "同步中"
   "completed", "succeeded" -> "同步完成"
   "failed" -> "同步失败"
   "canceled", "cancelled" -> "已取消"
   else -> "未知状态"
  }
  return mapOf("ok" to true, "page" to mapOf("id" to "trackerSync", "title" to "Tracker 同步", "fields" to listOf(mapOf("id" to "operationId", "type" to "hidden", "value" to (state["operationId"] ?: ""))),
   "items" to listOf(mapOf("id" to "status", "title" to status)), "actions" to listOf(mapOf("id" to "refreshTrackerSync", "label" to "刷新状态"), mapOf("id" to "cancelTrackerSync", "label" to "取消同步"), mapOf("id" to "trackers", "label" to "返回来源"))))
 }
 private fun invokeAction(a: JSONObject, reply: Messenger): Any? {
  val action = a.optString("action"); val values = a.optJSONObject("values") ?: JSONObject()
  return when (action) {
   "parseInput" -> { val input = a.optString("input").ifEmpty { values.optString("input") }; execute("parseInput", JSONObject().put("input", input).put("requestId", a.optString("requestId")), reply) }
   "importTorrent" -> if (!a.has("uri")) mapOf("ok" to true, "requestUi" to mapOf("type" to "pickFile", "mimeTypes" to listOf("application/x-bittorrent"), "resultAction" to "importTorrent")) else { val bytes = readTorrent(a.getString("uri")); val result = runtime.importTorrent(bytes, UUID.randomUUID().toString(), a.optString("displayName")).get(); preview(result, "") }
   "enqueue" -> { val indices = a.optJSONArray("selectedFileIndices") ?: values.optJSONArray("selectedFileIndices") ?: JSONArray(); val args = JSONObject().put("selectedFileIndices", indices).put("metadataKey", values.optString("metadataKey")).put("magnet", values.optString("magnet")).put("taskId", UUID.randomUUID().toString()); val r = execute("enqueue", args, reply) as Map<*, *>; if (r.containsKey("requestUi")) r else if (r["ok"] == true) mapOf("ok" to true, "message" to "已添加下载", "navigate" to "downloads") else r }
   "settings" -> settingsPage()
   "saveSettings" -> {
    val max = values.optString("maxActiveDownloads").toIntOrNull()
    val down = values.optString("downloadRateLimit").toIntOrNull()
    val up = values.optString("uploadRateLimit").toIntOrNull()
    if (max == null || max !in 1..2 || down == null || down < 0 || up == null || up < 0) mapOf("ok" to false, "code" to "INVALID_ARGUMENT", "message" to "请输入有效的并发数和非负限速")
    else refreshed(runtime.updateSettings(values.optBoolean("unmeteredOnly", true), max, down, up).get(), settingsPage())
   }
   "trackers" -> trackersPage()
   "trackerSource" -> {
    val id = a.optString("itemId").ifEmpty { a.optString("id") }
    val source = runtime.getTrackerSource(id).get()
    if (source["ok"] != true) source else trackerSourcePage(source)
   }
   "newTrackerSource" -> trackerSourcePage(mapOf("id" to "", "revision" to runtime.getTrackerConfig().get()["revision"], "name" to "", "endpoints" to emptyList<String>(), "enabled" to true))
   "saveTrackerSource" -> {
    val id = values.optString("id").takeIf { it.isNotEmpty() }
    val endpoints = values.optString("endpoints").lines().map { it.trim() }.filter { it.isNotEmpty() }
    val result = runtime.saveTrackerSource(id, values.optString("name"), endpoints, values.optBoolean("enabled", true), values.optString("revision").toIntOrNull(), false).get()
    if (result["ok"] == true) refreshed(result, trackersPage()) else result
   }
   "confirmDeleteTrackerSource" -> mapOf("ok" to true, "page" to mapOf("id" to "confirmTrackerDelete", "title" to "删除 Tracker 来源？", "fields" to listOf(mapOf("id" to "id", "type" to "hidden", "value" to values.optString("id")), mapOf("id" to "revision", "type" to "hidden", "value" to values.optString("revision"))), "items" to emptyList<Any>(), "actions" to listOf(mapOf("id" to "deleteTrackerSource", "label" to "确认删除", "primary" to true), mapOf("id" to "trackers", "label" to "取消"))))
   "deleteTrackerSource" -> { val result = runtime.saveTrackerSource(values.optString("id").takeIf { it.isNotEmpty() }, "", emptyList(), false, values.optString("revision").toIntOrNull(), true).get(); if (result["ok"] == true) refreshed(result, trackersPage()) else result }
   "restoreTrackers" -> { val result = runtime.restoreRecommendedSources(values.optString("revision").toIntOrNull()).get(); if (result["ok"] == true) refreshed(result, trackersPage()) else result }
   "customTrackers" -> customTrackersPage()
   "saveCustomTrackers" -> { val urls = values.optString("urls").lines().map { it.trim() }.filter { it.isNotEmpty() }; val result = runtime.saveCustomTrackers(urls, values.optString("revision").toIntOrNull(), false).get(); if (result["ok"] == true) refreshed(result, customTrackersPage()) else result }
   "syncTrackers" -> {
    val result = runtime.syncTrackers(true).get()
    if (result["ok"] == true && result["operationId"] != null) trackerSyncPage(result) else result
   }
   "refreshTrackerSync" -> { val result = runtime.getTrackerSyncState(values.optString("operationId")).get(); if (result["operationId"] != null) trackerSyncPage(result) else result }
   "cancelTrackerSync" -> { val result = runtime.cancelTrackerSync(values.optString("operationId")).get(); if (result["ok"] == true) refreshed(result, trackersPage()) else result }
   else -> mapOf("ok" to false, "code" to "UNKNOWN_ACTION")
  }
 }
}
private fun jsonMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> fromJson(o.get(k)) }
private fun fromJson(x: Any?): Any? = when (x) { JSONObject.NULL -> null; is JSONObject -> jsonMap(x); is JSONArray -> (0 until x.length()).map { fromJson(x.get(it)) }; else -> x }
private fun toJson(x: Any?): Any = when (x) { null -> JSONObject.NULL; is JSONObject, is JSONArray -> x; is Map<*, *> -> JSONObject().apply { x.forEach { (k,v) -> val key = k.toString(); put(key, if (key in setOf("outputPath", "taskDir", "targetRoot")) "" else toJson(v)) } }; is Iterable<*> -> JSONArray().apply { x.forEach { put(toJson(it)) } }; else -> x }
