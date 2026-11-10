package com.clipisle.btplugin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.clipisle.app.bt.BtRuntime
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

internal object TransferTickets {
    private val ledger = TransferTicketLedger(SystemClock::elapsedRealtime)
    val worker = Executors.newSingleThreadExecutor()
    fun create(method: String, args: JSONObject): String = ledger.create(method, args.toString())
    fun claim(id: String, restored: Boolean): TransferTicketLedger.Ticket? = ledger.claim(id, restored)
    fun startOnce(ticket: TransferTicketLedger.Ticket) = ledger.startOnce(ticket)
    fun remainingBeforeDeadline(ticket: TransferTicketLedger.Ticket) = ledger.remainingBeforeDeadline(ticket)
}

/** Transparent activity supplies this plugin UID's visible context for a user-initiated transfer. */
class TransferConfirmActivity : Activity() {
    private var ticket: TransferTicketLedger.Ticket? = null
    private var token: String = ""
    private var attached = false
    private val unknownResult = "{\"ok\":false,\"code\":\"OUTCOME_UNKNOWN\",\"message\":\"任务状态未知，请返回下载列表查看\"}"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (callingPackage != "com.clipisle.app" || !HostTrust.isAuthorized(this)) { finish(); return }
        token = intent.getStringExtra("token") ?: ""
        val restored = state?.getString("transferToken") == token && token.isNotEmpty()
        ticket = TransferTickets.claim(token, restored) ?: run { finish(); return }
        // Once claimed, cancellation must never imply that the transfer was
        // not started. A completed result replaces this conservative default.
        setResult(RESULT_OK, Intent().putExtra("result", unknownResult))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { /* The one-use operation must report its actual result first. */ }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // For pre-Android 13, keep the bridge visible until the result arrives.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("transferToken", token)
        super.onSaveInstanceState(outState)
    }

    override fun onPostResume() {
        super.onPostResume()
        val current = ticket ?: return
        if (!attached) {
            attached = true
            val activity = WeakReference(this)
            current.result.whenComplete { result, _ ->
                activity.get()?.runOnUiThread {
                    val instance = activity.get() ?: return@runOnUiThread
                    if (instance.isFinishing || instance.isDestroyed) return@runOnUiThread
                    instance.setResult(RESULT_OK, Intent().putExtra("result", result ?: "{\"ok\":false,\"code\":\"ENGINE_UNAVAILABLE\"}"))
                    instance.finish()
                }
            }
        }
        val remaining = TransferTickets.remainingBeforeDeadline(current)
        if (remaining == 0L) {
            current.result.complete(unknownResult)
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // This is only an outcome deadline: the operation is neither
            // cancelled nor retried, and a late result cannot change it.
            current.result.complete(unknownResult)
        }, remaining)
        if (TransferTickets.startOnce(current)) {
            val app = applicationContext
            TransferTickets.worker.execute {
                val result = try {
                    val args = JSONObject(current.args)
                    val runtime = BtRuntime.get(app)
                    if (current.method == "enqueue") {
                        val array = args.getJSONArray("selectedFileIndices")
                        require(array.length() <= 20000)
                        val indices = (0 until array.length()).map { array.getInt(it).also { n -> require(n >= 0) } }
                        val root = File(app.filesDir, "downloads").apply { mkdirs() }
                        runtime.enqueue(args.optString("magnet"), indices, root.absolutePath, args.getString("taskId"), args.optString("metadataKey").takeIf { it.isNotEmpty() }).get()
                    } else {
                        runtime.resume(args.getString("taskId")).get()
                    }
                } catch (_: Exception) {
                    mapOf("ok" to false, "code" to "ENGINE_UNAVAILABLE", "message" to "无法启动传输")
                }
                val safe = result.filterKeys { it !in setOf("taskDir", "targetRoot", "outputPath") }
                current.result.complete(JSONObject(if (safe["ok"] == true) safe + ("navigate" to "downloads") else safe).toString())
            }
        }
    }
}
