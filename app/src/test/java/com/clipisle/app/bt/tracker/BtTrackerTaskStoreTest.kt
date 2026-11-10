package com.clipisle.app.bt.tracker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BtTrackerTaskStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun policy() = BtTrackerTaskStore.Policy("task-1", 7, "hash-1", BtTrackerPolicy.Privacy.public,
        listOf(BtTrackerPolicy.Original("http://127.0.0.1/announce?passkey=secret",
            BtTrackerPolicy.keyOf("http://127.0.0.1/announce?passkey=secret"), 4, "torrent")),
        supplements = listOf(BtTrackerPolicy.Supplement("https://public.example/announce",
            BtTrackerPolicy.keyOf("https://public.example/announce"), listOf("custom"))), catalogRevision = 42)

    @Test fun `pending intent survives new store and preserves original LAN credentials and tiers`() {
        val directory = folder.newFolder()
        val saved = policy().copy(pending = true, revision = 3)
        BtTrackerTaskStore(directory).save(saved)
        assertEquals(saved, BtTrackerTaskStore(directory).load("task-1"))
    }

    @Test fun `committed removal cannot resurrect supplements after reopening`() {
        val directory = folder.newFolder()
        val store = BtTrackerTaskStore(directory)
        store.save(policy())
        val removed = policy().copy(supplements = emptyList(), enabled = false, revision = 2)
        store.save(removed.copy(pending = true))
        store.save(removed)
        assertEquals(removed, BtTrackerTaskStore(directory).load("task-1"))
    }

    @Test fun `corruption is reported and original bytes are preserved`() {
        val directory = folder.newFolder()
        val store = BtTrackerTaskStore(directory)
        store.save(policy())
        val file = File(directory, "task-1.policy")
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertThrows(IllegalArgumentException::class.java) { store.load("task-1") }
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test fun `crash after global revocation preserves obligation and does not revive on reenable`() {
        val directory = folder.newFolder()
        val revoked = policy().copy(lastAppliedAt = 123).revoked(false, emptyList())
        assertTrue(revoked.pending)
        assertEquals(0L, revoked.lastAppliedAt)
        assertEquals(policy().originals, revoked.originals)
        BtTrackerTaskStore(directory).save(revoked)
        val afterRestart = BtTrackerTaskStore(directory).load("task-1")!!
        assertTrue(afterRestart.revoked(true, emptyList()).supplements.isEmpty())
        assertTrue(afterRestart.pending)
    }

    @Test fun `single opaque key revocation preserves originals and survives reopen`() {
        val store = BtTrackerTaskStore(folder.newFolder())
        val revoked = policy().revoked(true, listOf(BtTrackerPolicy.idForUrl(policy().supplements.single().url)))
        store.save(revoked)
        assertEquals(policy().originals, store.load("task-1")!!.originals)
        assertTrue(store.load("task-1")!!.supplements.isEmpty())
    }

    @Test fun `completed task bounded status survives restart without storing full URLs`() {
        val store = BtTrackerTaskStore(folder.newFolder())
        val entry = BtTrackerStatus.Entry(BtTrackerPolicy.idForUrl(policy().originals.single().url), "127.0.0.1",
            "HTTP", "original", "", BtTrackerStatus.AnnounceState.responded, 4, observedAt = 12345)
        store.save(policy().copy(lastStates = listOf(entry)))
        assertEquals(listOf(entry), store.load("task-1")!!.lastStates)
    }

    @Test fun `private supplement policy rejected before persistence`() {
        val store = BtTrackerTaskStore(folder.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.save(policy().copy(privacy = BtTrackerPolicy.Privacy.private)) }
        assertNull(store.load("task-1"))
    }

    @Test fun `task identifier cannot escape private directory`() {
        val store = BtTrackerTaskStore(folder.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.load("../task-1") }
    }
}
