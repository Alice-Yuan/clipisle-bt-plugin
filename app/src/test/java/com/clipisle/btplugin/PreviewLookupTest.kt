package com.clipisle.btplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLookupTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
    private val folderPayload = """
        {"error":"","type":"FOLDER","file_type":"folder","name":"Big Buck Bunny",
         "size":276445467,"count":3,"screenshots":[
          {"time":0,"screenshot":"https://whatslink.info/image/befdcf1bc84697c2e2e17cd0c1d29ad2"},
          {"time":0,"screenshot":"https://whatslink.info/image/21d9bfc7fc6da4b1619bc2689e538812"}]}
    """.trimIndent()

    private val filePayload = """
        {"error":"","type":"FILE","file_type":"video","name":"BigBuckBunny_320x180.mp4",
         "size":64657027,"count":1,"screenshots":[
          {"time":0,"screenshot":"https://whatslink.info/image/abc"}]}
    """.trimIndent()

    @Test
    fun `folder keeps name size and count`() {
        val preview = PreviewLookup.parse("magnet:?xt=urn:btih:abc", folderPayload)
        assertTrue(preview is PreviewLookup.Preview.Known)
        preview as PreviewLookup.Preview.Known
        assertEquals("Big Buck Bunny", preview.name)
        assertEquals(276445467L, preview.sizeBytes)
        assertEquals(3, preview.fileCount)
        assertEquals("FOLDER", preview.type)
        assertEquals(2, preview.screenshots.size)
    }

    @Test
    fun `single file keeps its type`() {
        val preview = PreviewLookup.parse("https://example.com/a.mp4", filePayload)
        preview as PreviewLookup.Preview.Known
        assertEquals("FILE", preview.type)
        assertEquals("video", preview.fileType)
        assertEquals(1, preview.fileCount)
    }

    @Test
    fun `unknown type is reported as unknown not as failure`() {
        val payload = """{"error":"","type":"UNKNOWN","file_type":"","name":"","size":0,"count":0,"screenshots":[]}"""
        val preview = PreviewLookup.parse("https://example.com", payload)
        assertTrue("网页应被判定为无法识别", preview is PreviewLookup.Preview.Unknown)
    }

    @Test
    fun `empty name is unknown even when type claims otherwise`() {
        val payload = """{"error":"","type":"FILE","file_type":"video","name":"","size":0,"count":0}"""
        assertTrue(PreviewLookup.parse("x", payload) is PreviewLookup.Preview.Unknown)
    }

    @Test
    fun `declared error does not expose remote text or URLs`() {
        val payload = """{"error":"Query failed, please try again later."}"""
        val preview = PreviewLookup.parse("x", payload)
        preview as PreviewLookup.Preview.Failed
        assertEquals("暂时无法获取预览，可以直接选择文件", preview.reason)
    }

    @Test
    fun `malformed payload does not throw`() {
        assertTrue(PreviewLookup.parse("x", "<html>500</html>") is PreviewLookup.Preview.Failed)
    }

    @Test
    fun `non https screenshots are dropped`() {
        val payload = """
            {"error":"","type":"FILE","file_type":"video","name":"a.mp4","size":1,"count":1,
             "screenshots":[{"time":0,"screenshot":"http://insecure/x"},
                            {"time":0,"screenshot":"https://whatslink.info/image/ok"}]}
        """.trimIndent()
        val preview = PreviewLookup.parse("x", payload) as PreviewLookup.Preview.Known
        assertEquals(listOf("https://whatslink.info/image/ok"), preview.screenshots)
    }

    @Test
    fun `screenshots are capped at five`() {
        val shots = (1..9).joinToString(",") { """{"time":0,"screenshot":"https://whatslink.info/image/$it"}""" }
        val payload = """{"error":"","type":"FILE","file_type":"video","name":"a.mp4","size":1,"count":1,"screenshots":[$shots]}"""
        val preview = PreviewLookup.parse("x", payload) as PreviewLookup.Preview.Known
        assertEquals(5, preview.screenshots.size)
    }

    @Test
    fun `blank input never reaches the network`() {
        var called = false
        val preview = PreviewLookup.lookup("   ") { called = true; "{}" }
        assertTrue(preview is PreviewLookup.Preview.Failed)
        assertTrue("空输入不应发起请求", !called)
    }

    @Test
    fun `transport error is surfaced as failure`() {
        val preview = PreviewLookup.lookup(magnet) { throw java.io.IOException("https://secret/?token=private") }
        preview as PreviewLookup.Preview.Failed
        assertEquals("暂时无法获取预览，可以直接选择文件", preview.reason)
    }

    @Test
    fun `lookup encodes the target into the query`() {
        var seen = ""
        PreviewLookup.lookup("$magnet&dn=a%20b&tr=https%3A%2F%2Fprivate.example%2Ftoken") { seen = it; folderPayload }
        assertTrue(seen.startsWith("https://whatslink.info/api/v1/link?url="))
        assertTrue("参数必须完整编码", !seen.substringAfter("url=").contains("&dn="))
        assertEquals(magnet, java.net.URLDecoder.decode(seen.substringAfter("url="), "UTF-8"))
    }

    @Test fun `original input survives but tracker credentials are never uploaded`() {
        val original = "$magnet&dn=Private%20Name&tr=https%3A%2F%2Ftracker.example%2Fpasskey&xs=https%3A%2F%2Fseed.example%2Fsecret"
        var seen = ""
        val result = PreviewLookup.lookup(original) { seen = it; folderPayload }
        assertEquals(original, result.input)
        assertEquals(magnet, java.net.URLDecoder.decode(seen.substringAfter("url="), "UTF-8"))
        assertTrue(!seen.contains("private", true) && !seen.contains("passkey", true))
    }

    @Test fun `non magnet targets and malformed hashes never call transport`() {
        for (input in listOf("https://private.example/?token=secret", "file:///private", "magnet:?xt=urn:btih:abc", "magnet:?xt=urn:btih:" + "g".repeat(40))) {
            var called = false
            val result = PreviewLookup.lookup(input) { called = true; folderPayload }
            assertTrue(result is PreviewLookup.Preview.Failed)
            assertTrue(!called)
        }
    }

    @Test fun `hybrid identity preserves hashes and removes unrelated parameters`() {
        val v2 = "1220" + "b".repeat(64)
        assertEquals("$magnet&xt=urn:btmh:$v2", PreviewLookup.publicIdentity("$magnet&xt=urn:btmh:$v2&dn=private"))
    }

    @Test fun `unknown or malformed facts are omitted instead of invented`() {
        for (facts in listOf("", ",\"size\":-1,\"count\":-2", ",\"size\":1.5,\"count\":2.5", ",\"size\":9223372036854775808,\"count\":2147483648")) {
            val known = PreviewLookup.parse("x", "{\"type\":\"FILE\",\"name\":\"a\"$facts}") as PreviewLookup.Preview.Known
            assertEquals(null, known.sizeBytes)
            assertEquals(null, known.fileCount)
        }
        assertTrue(PreviewLookup.parse("x", "{\"type\":\"SOMETHING\",\"name\":\"a\"}") is PreviewLookup.Preview.Unknown)
    }

    @Test fun `image URLs must stay on the public service origin`() {
        val rejected = listOf("https://127.0.0.1/image/a", "https://whatslink.info:444/image/a", "https://user@whatslink.info/image/a", "https://whatslink.info/image/a#x", "https://whatslink.info.evil.test/image/a", "https://whatslink.info/api/v1/link")
        val shots = rejected.joinToString(",") { "{\"screenshot\":\"$it\"}" }
        val known = PreviewLookup.parse("x", "{\"type\":\"FILE\",\"name\":\"a\",\"screenshots\":[$shots]}") as PreviewLookup.Preview.Known
        assertTrue(known.screenshots.isEmpty())
    }
}
