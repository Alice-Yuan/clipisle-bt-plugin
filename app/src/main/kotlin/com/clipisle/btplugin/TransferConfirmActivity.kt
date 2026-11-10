package com.clipisle.btplugin
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import com.clipisle.app.bt.BtRuntime
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object TransferTickets {
 private data class Ticket(val method: String, val args: String, val created: Long)
 private val tickets = LinkedHashMap<String, Ticket>()
 @Synchronized fun create(method: String, args: JSONObject): String {
  val now = android.os.SystemClock.elapsedRealtime()
  tickets.entries.removeAll { now - it.value.created > 120_000 }
  require(tickets.size < 32) { "too many pending confirmations" }
  val id = UUID.randomUUID().toString()
  tickets[id] = Ticket(method, args.toString(), now)
  return id
 }
 @Synchronized fun take(id: String): Pair<String, String>? {
  val ticket = tickets.remove(id) ?: return null
  if (android.os.SystemClock.elapsedRealtime() - ticket.created > 120_000) return null
  return ticket.method to ticket.args
 }
}
class TransferConfirmActivity : Activity() {
 private val worker = Executors.newSingleThreadExecutor()
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  if (callingPackage != "com.clipisle.app" || !HostTrust.isAuthorized(this)) { finish(); return }
  val request = TransferTickets.take(intent.getStringExtra("token") ?: "") ?: run { finish(); return }
  AlertDialog.Builder(this).setTitle("启动 BT 下载").setMessage("允许插件启动此传输任务？")
   .setNegativeButton("取消") { _, _ -> finish() }
   .setPositiveButton("启动") { _, _ ->
    worker.execute {
     val result = try {
      val a = JSONObject(request.second); val runtime = BtRuntime.get(applicationContext)
      if (request.first == "enqueue") {
       val ar = a.getJSONArray("selectedFileIndices"); require(ar.length() <= 20000)
       val indices = (0 until ar.length()).map { ar.getInt(it).also { n -> require(n >= 0) } }
       val root = File(filesDir, "downloads").apply { mkdirs() }
       runtime.enqueue(a.optString("magnet"), indices, root.absolutePath, a.getString("taskId"), a.optString("metadataKey").takeIf { it.isNotEmpty() }).get()
      } else runtime.resume(a.getString("taskId")).get()
     } catch (_: Exception) { mapOf("ok" to false, "code" to "ENGINE_UNAVAILABLE", "message" to "无法启动传输") }
     val safe = result.filterKeys { it !in setOf("taskDir", "targetRoot", "outputPath") }
     val resultJson = JSONObject(if (safe["ok"] == true) safe + ("navigate" to "downloads") else safe).toString()
     runOnUiThread { setResult(RESULT_OK, Intent().putExtra("result", resultJson)); finish() }
    }
   }.setOnCancelListener { finish() }.show()
 }
 override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
