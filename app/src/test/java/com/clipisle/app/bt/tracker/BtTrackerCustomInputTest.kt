package com.clipisle.app.bt.tracker

import org.junit.Assert.*
import org.junit.Test

class BtTrackerCustomInputTest {
    @Test fun `empty editor and empty native list both represent clearing custom addresses`() {
        for (input in listOf(emptyList(), listOf(""), listOf(" ", "\uFEFF", " # comment"))) {
            val parsed = BtTrackerPolicy.parseCustomInput(input)
            assertTrue(parsed.urls.isEmpty())
            assertTrue(parsed.invalidLines.isEmpty())
        }
    }
    @Test fun `BOM blank and comment lines do not shift original DNS error line positions`() {
        val parsed = BtTrackerPolicy.parseCustomInput(listOf("\uFEFF# trackers", " ", "https://one.example/announce",
            "# second", "\uFEFF https://two.example/announce"))
        assertEquals(listOf(3, 5), parsed.inputLines)
        assertEquals(listOf("https://one.example/announce", "https://two.example/announce"), parsed.urls)
        assertTrue(parsed.invalidLines.isEmpty())
    }
    @Test fun `duplicates retain first original input line without concealing invalid lines`() {
        val parsed = BtTrackerPolicy.parseCustomInput(listOf("", "https://one.example/announce",
            "https://one.example/announce", "not-a-tracker", "http://127.0.0.1/announce"))
        assertEquals(listOf(2), parsed.inputLines)
        assertEquals(1, parsed.duplicateCount)
        assertEquals(listOf(4, 5), parsed.invalidLines)
    }
    @Test fun `custom parser rejects oversized drafts before allocation or DNS`() {
        assertThrows(IllegalArgumentException::class.java) { BtTrackerPolicy.parseCustomInput(List(501) { "" }) }
        assertThrows(IllegalArgumentException::class.java) { BtTrackerPolicy.parseCustomInput(listOf("x".repeat(2049))) }
    }
}
