package com.clipisle.btplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPageTest {
    private val known = PreviewLookup.Preview.Known(
        input = "magnet:?xt=urn:btih:abc",
        type = "FOLDER",
        fileType = "folder",
        name = "Big Buck Bunny",
        sizeBytes = 276445467,
        fileCount = 3,
        screenshots = listOf(
            "https://whatslink.info/image/a",
            "https://whatslink.info/image/b",
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun page(result: Map<String, Any>): Map<String, Any> = result["page"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun items(result: Map<String, Any>): List<Map<String, Any>> =
        page(result)["items"] as List<Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun headline(result: Map<String, Any>): Map<String, Any> =
        items(result).first { it["type"] == "headline" }

    @Suppress("UNCHECKED_CAST")
    private fun fields(result: Map<String, Any>, id: String): Map<String, Any> =
        (page(result)["fields"] as List<Map<String, Any>>).first { it["id"] == id }

    @Test
    fun `known preview exposes name size and file count as read-only headline`() {
        val result = PreviewPage.build(known)
        val head = headline(result)
        assertEquals("Big Buck Bunny", head["title"])
        // 十进制单位，和项目里其他大小显示保持一致
        assertEquals("大小 276.45 MB · 3 个文件 · 文件夹", head["subtitle"])
    }

    @Test
    fun `the facts are items not editable fields`() {
        val result = PreviewPage.build(known)
        @Suppress("UNCHECKED_CAST")
        val fields = page(result)["fields"] as List<Map<String, Any>>
        // 只剩隐藏的 input；名称和概要不能渲染成输入框
        assertEquals(listOf("input"), fields.map { it["id"] })
        assertEquals("hidden", fields.single()["type"])
    }

    @Test
    fun `each screenshot becomes one gallery row`() {
        val result = PreviewPage.build(known)
        val galleries = items(result).filter { it["type"] == "gallery" }
        assertEquals(2, galleries.size)
        assertEquals(listOf("https://whatslink.info/image/a"), galleries[0]["images"])
    }

    @Test
    fun `no screenshots means no content section and no empty gallery`() {
        val result = PreviewPage.build(known.copy(screenshots = emptyList()))
        assertTrue(items(result).none { it["type"] == "gallery" })
        @Suppress("UNCHECKED_CAST")
        val sections = page(result)["sections"] as List<Map<String, Any>>
        assertTrue("没有画面时不应出现空的预览分组", sections.none { it["id"] == "content" })
    }

    @Test
    fun `unknown never pretends to know the content`() {
        val result = PreviewPage.build(PreviewLookup.Preview.Unknown("https://example.com"))
        val head = headline(result)
        assertTrue("无法识别时不应给出内容名称", head["title"] != "Sintel")
        assertTrue(head["subtitle"].toString().contains("无法预览"))
    }

    @Test
    fun `failure shows the reason and offers both retry and fallback`() {
        val result = PreviewPage.build(PreviewLookup.Preview.Failed("x", "服务返回 HTTP 500"))
        assertEquals("服务返回 HTTP 500", headline(result)["subtitle"])
        @Suppress("UNCHECKED_CAST")
        val actions = page(result)["actions"] as List<Map<String, Any>>
        assertTrue(actions.any { it["id"] == "preview" })
        assertTrue(actions.any { it["id"] == "parseInput" })
    }

    @Test
    fun `the original input always survives for the fallback action`() {
        for (preview in listOf(
            known,
            PreviewLookup.Preview.Unknown("https://example.com"),
            PreviewLookup.Preview.Failed("magnet:x", "boom"),
        )) {
            val result = PreviewPage.build(preview)
            assertEquals(preview.input, fields(result, "input")["value"])
        }
    }

    @Test
    fun `primary action is always the one that can finish the job`() {
        val result = PreviewPage.build(known)
        @Suppress("UNCHECKED_CAST")
        val actions = page(result)["actions"] as List<Map<String, Any>>
        assertEquals("parseInput", actions.single { it["primary"] == true }["id"])
    }
}
