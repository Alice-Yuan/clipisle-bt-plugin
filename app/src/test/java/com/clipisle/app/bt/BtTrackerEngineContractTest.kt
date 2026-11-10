package com.clipisle.app.bt

import org.junit.Assert.*
import org.junit.Test
import com.clipisle.app.bt.tracker.BtTrackerPolicy

/** Exercises production pre-JNI validation. Actual flags/add/resume require device tests. */
class BtTrackerEngineContractTest {
    private fun version() = BtTrackerPolicy.PlanVersion(7, "handle-a", "runtime-a", 2, 5, 3, 10,
        listOf("4:original", "5:supplement"))

    @Test fun `rebuilt handle invalidates preview even with the same tracker table`() {
        val planned = version()
        assertTrue(BtTrackerPolicy.canApplyPlan(planned, planned, 200, 100, false))
        assertFalse(BtTrackerPolicy.canApplyPlan(planned, planned.copy(handleGeneration = "handle-b"), 200, 100, false))
        val noHandle = planned.copy(handleGeneration = "")
        assertTrue(BtTrackerPolicy.canApplyPlan(noHandle, noHandle, 200, 100, false))
        assertFalse(BtTrackerPolicy.canApplyPlan(noHandle, planned, 200, 100, false))
    }
    @Test fun `preview compare and set rejects all stale authority generations`() {
        val planned = version()
        val changed = listOf(planned.copy(taskGeneration = 8), planned.copy(runtimeEpoch = "runtime-b"),
            planned.copy(networkEpoch = 3), planned.copy(policyRevision = 6), planned.copy(configRevision = 4),
            planned.copy(catalogRevision = 11), planned.copy(tableSignature = listOf("4:original")))
        changed.forEach { assertFalse(BtTrackerPolicy.canApplyPlan(planned, it, 200, 100, false)) }
        assertFalse(BtTrackerPolicy.canApplyPlan(planned, planned, 200, 201, false))
        assertFalse(BtTrackerPolicy.canApplyPlan(planned, planned, 200, 100, true))
    }
    @Test fun `malformed tier count is rejected instead of flattening originals`() {
        assertThrows(IllegalArgumentException::class.java) {
            BtTorrentEngine.validateTrackerOverride(listOf("https://one.example/announce", "udp://two.example:80/announce"), listOf(4))
        }
    }
    @Test fun `missing tier list cannot silently erase priority`() {
        assertThrows(IllegalArgumentException::class.java) {
            BtTorrentEngine.validateTrackerOverride(listOf("https://one.example/announce"), null)
        }
    }
    @Test fun `explicit empty override remains different from preserve existing list`() {
        assertNull(BtTorrentEngine.validateTrackerOverride(null, null))
        assertEquals(emptyList<TrackerEntry>(), BtTorrentEngine.validateTrackerOverride(emptyList(), emptyList()))
    }
    @Test fun `tier range matches native unsigned byte and preserves sparse originals`() {
        assertEquals(listOf(TrackerEntry("http://127.0.0.1/announce?passkey=s", 4), TrackerEntry("udp://two.example:80/announce", 255)),
            BtTorrentEngine.validateTrackerOverride(listOf("http://127.0.0.1/announce?passkey=s", "udp://two.example:80/announce"), listOf(4, 255)))
        for (tier in listOf(-1, 256)) assertThrows(IllegalArgumentException::class.java) {
            BtTorrentEngine.validateTrackerOverride(listOf("https://one.example/announce"), listOf(tier))
        }
    }
    @Test fun `tiers without override and blank URLs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BtTorrentEngine.validateTrackerOverride(null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { BtTorrentEngine.validateTrackerOverride(listOf(" "), listOf(0)) }
    }
}
