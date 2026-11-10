package com.clipisle.app.bt

import org.junit.Assert.*
import org.junit.Test

class BtInputTest {
    private val v1 = "0123456789abcdef0123456789abcdef01234567"
    private val v2 = "0123456789abcdef".repeat(4)

    @Test fun decodesEncodedParametersWithoutRewritingMagnet() {
        val link = "magnet:?xt=urn%3Abtih%3A$v1&dn=%E6%B5%8B%E8%AF%95+film&tr=https%3A%2F%2Fexample.com%2Fannounce%3Fpasskey%3Da%2Bb"
        val info = (BtInput.parse(link) as BtInput.ParseResult.Ok).info
        assertEquals(v1, info.infoHashV1Hex)
        assertEquals("测试 film", info.displayName)
        assertEquals(listOf("https://example.com/announce?passkey=a+b"), info.trackers)
        assertEquals(link, info.magnet)
    }

    @Test fun acceptsRepeatedEquivalentV1IncludingBase32() {
        val link = "magnet:?xt=urn:btih:" + "0".repeat(40) + "&xt=urn:btih:" + "A".repeat(32)
        val info = (BtInput.parse(link) as BtInput.ParseResult.Ok).info
        assertEquals("0".repeat(40), info.infoHashV1Hex)
    }

    @Test fun retainsFullV2IdentityAndHybridAliases() {
        val info = (BtInput.parse("magnet:?xt=urn:btih:$v1&xt=urn:btmh:1220$v2") as BtInput.ParseResult.Ok).info
        assertTrue(info.isHybrid)
        assertEquals(v2, info.infoHashV2Hex)
        val v2Only = (BtInput.parse("magnet:?xt=urn:btmh:1220$v2") as BtInput.ParseResult.Ok).info
        assertTrue(BtInput.sameContent(info, v2Only))
        assertEquals("v2:$v2", v2Only.dedupeKey)
    }

    @Test fun rejectsMalformedMultihashesAndConflictingAliases() {
        val bad = listOf(
            "magnet:?xt=urn:btmh:$v2",
            "magnet:?xt=urn:btmh:1220" + "z".repeat(64),
            "magnet:?xt=urn:btih:$v1&xt=urn:btih:" + "a".repeat(40),
            "magnet:?xt=urn:btmh:1220$v2&xt=urn:btmh:1220" + "a".repeat(64),
            "magnet:?xt=urn:btih:$v1&xt=urn:btih:bad",
        )
        for (link in bad) assertTrue(link, BtInput.parse(link) is BtInput.ParseResult.Fail)
    }

    @Test fun rejectsBrokenEscapesAndOversizedOrUnencodedInput() {
        for (suffix in listOf("&dn=%", "&dn=%GG", "&dn=hello world", "&dn=" + "a".repeat(65536))) {
            assertTrue(BtInput.parse("magnet:?xt=urn:btih:$v1$suffix") is BtInput.ParseResult.Fail)
        }
    }

    @Test fun fragmentIsNotPartOfHash() {
        val info = (BtInput.parse("magnet:?xt=urn:btih:$v1#preview") as BtInput.ParseResult.Ok).info
        assertEquals(v1, info.infoHashV1Hex)
    }

    @Test fun hybridLookupCannotAcceptOnlyOneMatchingCachedAlias() {
        val info = (BtInput.parse("magnet:?xt=urn:btih:$v1&xt=urn:btmh:1220$v2") as BtInput.ParseResult.Ok).info
        assertTrue(info.matchesHashes(v1, v2))
        assertFalse(info.matchesHashes(v1, "a".repeat(64)))
        assertFalse(info.matchesHashes("a".repeat(40), v2))
        assertFalse(info.matchesHashes(v1, null))
    }

    @Test fun singleHashCanResolveToValidHybridMetadata() {
        val v1Only = (BtInput.parse("magnet:?xt=urn:btih:$v1") as BtInput.ParseResult.Ok).info
        val v2Only = (BtInput.parse("magnet:?xt=urn:btmh:1220$v2") as BtInput.ParseResult.Ok).info
        assertTrue(v1Only.matchesHashes(v1, v2))
        assertTrue(v2Only.matchesHashes(v1, v2))
    }
}
