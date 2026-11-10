package com.clipisle.btplugin

import org.junit.Assert.*
import org.junit.Test

class PluginSettingsPagesTest {
    @Test fun onlinePreviewChoiceUsesPluginOwnedSettingsAndDeclaresDisclosure() {
        for (enabled in listOf(true, false)) {
            val page = PluginSettingsPages.settings(mapOf("onlinePreviewEnabled" to enabled))["page"] as Map<*, *>
            val field = (page["fields"] as List<*>).filterIsInstance<Map<*, *>>().single { it["id"] == "onlinePreviewEnabled" }
            assertEquals(enabled, field["value"])
            assertEquals("仅向 whatslink.info 发送资源哈希", field["subtitle"])
            assertEquals("saveSettings", page["autoSaveAction"])
        }
    }
    @Test fun decimalLimitsRoundTripWithoutChangingTheirMeaning() {
        for (bytes in listOf(0, 1, 128000, 524288, Int.MAX_VALUE)) {
            assertEquals(bytes, PluginSettingsPages.parseRate(PluginSettingsPages.rateToUi(bytes)))
        }
        assertEquals(128000, PluginSettingsPages.parseRate("128"))
        assertEquals(1500, PluginSettingsPages.parseRate("1.5"))
    }
    @Test fun invalidOrOverflowingLimitsAreRejectedInsteadOfTruncated() {
        for (value in listOf("", "NaN", "Infinity", "-1", "0.0001", "2147483.648", "1e1000")) {
            assertNull(value, PluginSettingsPages.parseRate(value))
        }
    }
    @Test fun trackerIntervalKeepsAnExistingNonPresetValue() {
        val page = PluginSettingsPages.trackerOptions(mapOf("syncIntervalHours" to 36))["page"] as Map<*, *>
        val field = (page["fields"] as List<*>).filterIsInstance<Map<*, *>>().first { it["id"] == "syncIntervalHours" }
        assertEquals("36", field["value"])
        assertTrue((field["options"] as List<*>).filterIsInstance<Map<*, *>>().any { it["value"] == "36" })
    }
}
