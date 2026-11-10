package com.clipisle.btplugin

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** In-process, bounded, one-use bridge tickets. Losing the process never replays work. */
internal class TransferTicketLedger(
    private val now: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    internal class Ticket(val method: String, val args: String, val created: Long) {
        val result = CompletableFuture<String>()
        var claimed = false
        var started = false
    }
    private val tickets = LinkedHashMap<String, Ticket>()

    @Synchronized fun create(method: String, args: String): String {
        require(method == "enqueue" || method == "resume")
        val time = now()
        tickets.entries.removeAll { time - it.value.created > 10 * 60_000L || (!it.value.claimed && time - it.value.created > 120_000L) }
        // Completed results are retained for recreation, but do not block new
        // user operations indefinitely when many downloads are started quickly.
        while (tickets.size >= 32) {
            val completed = tickets.entries.firstOrNull { it.value.result.isDone } ?: break
            tickets.remove(completed.key)
        }
        require(tickets.size < 32) { "too many pending transfers" }
        val id = newId()
        require(id !in tickets)
        tickets[id] = Ticket(method, args, time)
        return id
    }

    @Synchronized fun claim(id: String, restored: Boolean): Ticket? {
        val ticket = tickets[id] ?: return null
        if (now() - ticket.created > 10 * 60_000L || (!ticket.claimed && now() - ticket.created > 120_000L)) {
            tickets.remove(id)
            return null
        }
        if (ticket.claimed && !restored) return null
        if (restored && !ticket.claimed) return null
        ticket.claimed = true
        return ticket
    }

    fun remainingBeforeDeadline(ticket: Ticket, timeoutMillis: Long = 30_000L): Long =
        (timeoutMillis - (now() - ticket.created).coerceAtLeast(0L)).coerceAtLeast(0L)

    @Synchronized fun startOnce(ticket: Ticket): Boolean {
        if (!ticket.claimed || ticket.started) return false
        ticket.started = true
        return true
    }
}
