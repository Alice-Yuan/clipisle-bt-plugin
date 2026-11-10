package com.clipisle.app.bt.tracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BtTrackerStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun cache(id: String = "xiu2_best", urls: List<String> = listOf("udp://tracker.example.org:6969/a")) =
        BtTrackerStore.SourceCache(id, "https://cf.trackerslist.com/best.txt", urls, "\"etag\"", null, 100, 100, 100, dnsValidated = true)

    @Test fun `previous valid config survives damaged current and empty source list is valid`() {
        val dir = temp.newFolder(); val store = BtTrackerStore(dir)
        val original = BtTrackerStore.Config.defaults()
        store.saveConfig(original)
        store.saveConfig(original.copy(revision = 2, sources = emptyList()))
        assertTrue((store.loadConfig() as BtTrackerStore.LoadResult.Ok).value.sources.isEmpty())
        File(dir, "config.json").writeText("{broken")
        assertEquals(original, (store.loadConfig() as BtTrackerStore.LoadResult.Ok).value)
        store.saveConfig(original.copy(revision = 3))
        assertEquals(original, BtTrackerStore(dir).let { File(dir, "config.json").writeText("bad again"); (it.loadConfig() as BtTrackerStore.LoadResult.Ok).value })
        assertTrue(dir.listFiles()!!.any { it.name.contains(".corrupt-") })
    }

    @Test fun `both damaged configs cannot be silently replaced by defaults`() {
        val dir = temp.newFolder(); val store = BtTrackerStore(dir)
        File(dir, "config.json").writeText("{}")
        File(dir, "config.json.bak").writeText("[]")
        assertEquals(BtTrackerStore.LoadResult.Damaged, store.loadConfig())
        assertThrows(java.io.IOException::class.java) { store.saveConfig(BtTrackerStore.Config.defaults()) }
        assertEquals("{}", File(dir, "config.json").readText())
    }

    @Test fun `invalid configuration fails before touching disk`() {
        val dir = temp.newFolder(); val store = BtTrackerStore(dir)
        val config = BtTrackerStore.Config.defaults()
        store.saveConfig(config)
        val bytes = File(dir, "config.json").readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.saveConfig(config.copy(customPublicTrackers = (0..20).map { "udp://t$it.example:80/a" })) }
        assertThrows(IllegalArgumentException::class.java) { store.saveConfig(config.copy(sources = config.sources + config.sources[0])) }
        assertArrayEquals(bytes, File(dir, "config.json").readBytes())
    }

    @Test fun `single corrupt source recovers independently without losing other source`() {
        val dir = temp.newFolder(); val store = BtTrackerStore(dir)
        val a = cache(); val b = cache("ngosang_best", listOf("https://second.example/a"))
        store.saveCatalog(BtTrackerStore.Catalog(mapOf(a.sourceId to a), a.urls, 100))
        store.saveCatalog(BtTrackerStore.Catalog(mapOf(a.sourceId to a, b.sourceId to b), a.urls + b.urls, 101))
        val json = JSONObject(File(dir, "catalog.json").readText())
        json.getJSONArray("sources").getJSONObject(0).put("lastSuccessfulCheckAt", -1)
        File(dir, "catalog.json").writeText(json.toString())
        val restored = (store.loadCatalog() as BtTrackerStore.LoadResult.Ok).value
        assertEquals(a, restored.sources[a.sourceId]); assertEquals(b, restored.sources[b.sourceId])
        store.saveCatalog(restored.copy(poolUpdatedAt = 102))
        File(dir, "catalog.json").writeText("broken after repair")
        val previous = (store.loadCatalog() as BtTrackerStore.LoadResult.Ok).value
        assertEquals(a, previous.sources[a.sourceId]); assertEquals(b, previous.sources[b.sourceId])
    }

    @Test fun `write failure propagates instead of acknowledging persistence`() {
        val blocker = temp.newFile()
        assertThrows(java.io.IOException::class.java) { BtTrackerStore(blocker).saveConfig(BtTrackerStore.Config.defaults()) }
    }

    @Test fun `expired changed and disabled source cannot contribute cached candidates`() {
        val config = BtTrackerStore.Config.defaults()
        val source = config.sources[0]
        val cached = cache().copy(sourceFingerprint = source.fingerprint())
        assertEquals(cached.urls, BtTrackerStore.pool(config, mapOf(source.id to cached), 101))
        assertTrue(BtTrackerStore.pool(config, mapOf(source.id to cached), 100 + BtTrackerStore.CACHE_AGE_MS).isEmpty())
        assertFalse(BtTrackerStore.usable(source.copy(enabled = false), cached, 101))
        assertFalse(BtTrackerStore.usable(source.copy(customEndpoints = listOf("https://other.example/list")), cached, 101))
        assertTrue(cached.isExpired(99))
        assertFalse(BtTrackerStore.usable(source, cached.copy(dnsValidated = false), 101))
    }
}
