package com.clipisle.btplugin

import org.junit.Assert.*
import org.junit.Test

class PreviewPageTest {
    private val known = PreviewLookup.Preview.Known(
        "magnet:?xt=urn:btih:" + "a".repeat(40), "FOLDER", "folder", "Big Buck Bunny", 276445467, 3,
        listOf("https://whatslink.info/image/a", "https://whatslink.info/image/b"),
    )
    @Suppress("UNCHECKED_CAST") private fun page(result: Map<String, Any>) = result["page"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST") private fun items(result: Map<String, Any>) = page(result)["items"] as List<Map<String, Any>>
    @Suppress("UNCHECKED_CAST") private fun fields(result: Map<String, Any>) = page(result)["fields"] as List<Map<String, Any>>
    @Suppress("UNCHECKED_CAST") private fun actions(result: Map<String, Any>) = page(result)["actions"] as List<Map<String, Any>>

    @Test fun `remote title remains literal while facts use translatable parts`() {
        val headline = items(PreviewPage.build(known)).first()
        assertEquals("Big Buck Bunny", headline["title"])
        assertEquals(true, headline["titleLiteral"])
        assertEquals(listOf(mapOf("text" to "276.45 MB"), mapOf("key" to "{count} 个文件", "arguments" to mapOf("count" to 3))), headline["subtitleParts"])
    }
    @Test fun `missing facts do not render fake zero quantities`() {
        val headline = items(PreviewPage.build(known.copy(sizeBytes = null, fileCount = null))).first()
        assertEquals(emptyList<Any>(), headline["subtitleParts"])
    }
    @Test fun `all screenshots share one gallery and no screenshot produces no gallery`() {
        val galleries = items(PreviewPage.build(known)).filter { it["type"] == "gallery" }
        assertEquals(1, galleries.size)
        assertEquals(known.screenshots, galleries.single()["images"])
        assertTrue(items(PreviewPage.build(known.copy(screenshots = emptyList()))).none { it["type"] == "gallery" })
    }
    @Test fun `every response keeps original input without a source footer`() {
        for (preview in listOf(known, PreviewLookup.Preview.Unknown("magnet:x"), PreviewLookup.Preview.Failed("magnet:y", "预览暂不可用"))) {
            val result = PreviewPage.build(preview)
            assertEquals(preview.input, fields(result).single()["value"])
            assertEquals("hidden", fields(result).single()["type"])
            assertTrue(items(result).none { it["type"] == "link" || it["id"] == "source" })
        }
    }
    @Test fun `unknown is not described as a transport failure`() {
        assertEquals("暂无内容预览", items(PreviewPage.build(PreviewLookup.Preview.Unknown("x"))).first()["title"])
    }
    @Test fun `failed previews can retry or use local metadata`() {
        val actions = actions(PreviewPage.build(PreviewLookup.Preview.Failed("x", "暂时无法获取预览，可以直接选择文件")))
        assertTrue(actions.any { it["id"] == "preview" && it["cancelMethod"] == "cancelInput" })
        assertTrue(actions.any { it["id"] == "parseInput" && it["cancelMethod"] == "cancelInput" })
    }
    @Test fun `primary button selects files rather than promising an immediate download`() {
        val result = PreviewPage.build(known)
        val action = actions(result).single { it["primary"] == true }
        assertEquals("parseInput", action["id"])
        assertEquals("选择文件", action["label"])
        assertEquals(true, page(result)["stickyActions"])
    }
}
