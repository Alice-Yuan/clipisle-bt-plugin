package com.clipisle.btplugin

import okhttp3.Call
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A cancellation arriving before a worker starts must also prevent the HTTP call. */
internal class PreviewRequests(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = 64,
) {
    internal class Session {
        val canceled = AtomicBoolean(false)
        private val call = AtomicReference<Call?>()
        fun attach(next: Call) {
            call.set(next)
            if (canceled.get()) next.cancel()
        }
        fun detach(previous: Call) { call.compareAndSet(previous, null) }
        fun cancel() { canceled.set(true); call.get()?.cancel() }
    }
    private val active = HashMap<String, Session>()
    private val pending = LinkedHashMap<String, Long>()
    private fun valid(id: String) = id.isNotBlank() && id.length <= 256
    private fun prune() { val time = now(); pending.entries.removeAll { it.value <= time } }

    @Synchronized fun begin(id: String): Session? {
        prune()
        if (!valid(id) || id in active || active.size >= capacity) return null
        return Session().also { session ->
            if (pending.remove(id) != null) session.cancel()
            active[id] = session
        }
    }
    @Synchronized fun finish(id: String, session: Session) {
        if (active[id] === session) active.remove(id)
    }
    @Synchronized fun cancel(id: String): Boolean {
        prune()
        if (!valid(id)) return false
        active[id]?.let { it.cancel(); return true }
        if (id !in pending && pending.size >= capacity) return false
        pending[id] = now() + 180_000
        return true
    }
    @Synchronized fun cancelAll() { active.values.forEach { it.cancel() } }
}
