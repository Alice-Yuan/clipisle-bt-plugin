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

    @Test fun `editing builtin endpoints preserves fallback and blacklist policy`() {
        val store = BtTrackerStore(temp.newFolder())
        val defaults = BtTrackerStore.Config.defaults()
        val builtin = defaults.sources.first { it.id == "xiu2_best" }
        assertEquals(3, builtin.effectiveEndpoints().size)
        val renamed = BtTrackerStore.editSource(builtin, builtin.id, "Renamed", false, builtin.effectiveEndpoints())!!
        assertEquals(builtin.endpoints, renamed.endpoints)
        assertEquals(builtin.customEndpoints, renamed.customEndpoints)
        assertFalse(renamed.enabled)
        assertNull(BtTrackerStore.editSource(builtin, builtin.id, "Renamed", true,
            listOf("https://one.example/list", "https://two.example/list", "https://three.example/list")))
        assertNull(BtTrackerStore.editSource(null, "custom_1", "Custom", true,
            listOf("https://one.example/list", "https://two.example/list", "https://three.example/list")))
        val blacklist = defaults.sources.first { it.isBlacklist }
        val edited = BtTrackerStore.editSource(blacklist, blacklist.id, "Edited", true,
            listOf("https://new.example/blacklist"))!!
        assertTrue(edited.isBlacklist)
        assertEquals(blacklist.endpoints, edited.endpoints)
        val config = defaults.copy(revision = 2, sources = defaults.sources.map { if (it.id == edited.id) edited else it })
        store.saveConfig(config)
        val reloaded = (store.loadConfig() as BtTrackerStore.LoadResult.Ok).value
        assertTrue(reloaded.sources.first { it.id == edited.id }.isBlacklist)
        val blackCache = cache(edited.id, listOf("udp://blocked.example:6969/a")).copy(
            sourceFingerprint = edited.fingerprint(), endpoint = edited.effectiveEndpoints()[0])
        assertTrue(BtTrackerStore.pool(reloaded, mapOf(edited.id to blackCache), 101).isEmpty())
        store.saveCatalog(BtTrackerStore.Catalog(mapOf(edited.id to blackCache), emptyList(), 101))
        assertTrue((store.loadCatalog() as BtTrackerStore.LoadResult.Ok).value.pool.isEmpty())
    }

    @Test fun `three custom sources and their six cache records round trip`() {
        val store = BtTrackerStore(temp.newFolder())
        val defaults = BtTrackerStore.Config.defaults()
        val extra = (1..3).map { i -> BtTrackerStore.SourceConfig("custom_$i", "Custom $i", true, listOf("https://source$i.example/list")) }
        val config = defaults.copy(sources = defaults.sources + extra)
        store.saveConfig(config)
        assertEquals(6, (store.loadConfig() as BtTrackerStore.LoadResult.Ok).value.sources.size)
        val caches = config.sources.associate { source ->
            val entry = cache(source.id).copy(endpoint = source.effectiveEndpoints().first(), sourceFingerprint = source.fingerprint())
            source.id to entry
        }
        store.saveCatalog(BtTrackerStore.Catalog(caches, emptyList(), 101))
        assertEquals(caches.keys, (store.loadCatalog() as BtTrackerStore.LoadResult.Ok).value.sources.keys)
        assertThrows(IllegalArgumentException::class.java) { store.saveConfig(config.copy(sources = config.sources +
            BtTrackerStore.SourceConfig("custom_4", "Fourth", true, listOf("https://four.example/list")))) }
    }

    @Test fun `legacy supplement values are capped on load and invalid new configs are rejected`() {
        val dir = temp.newFolder(); val store = BtTrackerStore(dir)
        val config = BtTrackerStore.Config.defaults()
        store.saveConfig(config)
        val file = File(dir, "config.json")
        file.writeText(JSONObject(file.readText()).put("maxSupplements", 50).toString())
        assertEquals(20, (store.loadConfig() as BtTrackerStore.LoadResult.Ok).value.maxSupplements)
        assertThrows(IllegalArgumentException::class.java) { store.saveConfig(config.copy(maxSupplements = 50)) }
    }

    @Test fun `catalog preserves cache for every builtin fallback endpoint`() {
        val store = BtTrackerStore(temp.newFolder())
        val source = BtTrackerStore.Config.defaults().sources.first()
        val entries = source.endpoints.associateWith { BtTrackerStore.EndpointCache(listOf("udp://tracker.example.org:6969/a"), null, null, 100) }
        val record = cache(source.id).copy(endpoint = source.endpoints.first(), endpointCaches = entries)
        store.saveCatalog(BtTrackerStore.Catalog(mapOf(source.id to record), record.urls, 101))
        assertEquals(entries, (store.loadCatalog() as BtTrackerStore.LoadResult.Ok).value.sources.getValue(source.id).endpointCaches)
    }
}
