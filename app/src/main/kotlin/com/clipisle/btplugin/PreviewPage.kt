package com.clipisle.btplugin

/**
 * Presentation for the "what is inside this link" screen.
 *
 * This page exists because a magnet tells the user nothing before the download
 * starts: no name, no size, no idea whether it is the thing they wanted. The
 * remote probe fills that gap with torrent-level facts only.
 *
 * The service has no file listing, so this page must never imply one. When the
 * probe cannot identify the content the page says so and offers the authoritative
 * path instead — fetching the real metadata over DHT.
 */
internal object PreviewPage {
    fun build(preview: PreviewLookup.Preview): Map<String, Any> = when (preview) {
        is PreviewLookup.Preview.Known -> known(preview)
        is PreviewLookup.Preview.Unknown -> unknown(preview.input)
        is PreviewLookup.Preview.Failed -> failed(preview)
    }

    private fun known(preview: PreviewLookup.Preview.Known): Map<String, Any> {
        val summary = buildList {
            add("大小 ${PreviewFormat.bytes(preview.sizeBytes)}")
            add("${preview.fileCount} 个文件")
            kindLabel(preview.type, preview.fileType)?.let { add(it) }
        }
        val items = mutableListOf<Any>()
        for ((index, url) in preview.screenshots.withIndex()) {
            items.add(
                mapOf(
                    "id" to "shot$index",
                    "type" to "gallery",
                    "section" to "content",
                    "images" to listOf(url),
                    "caption" to "第 ${index + 1} 帧",
                ),
            )
        }
        val sections = mutableListOf(section("summary", "内容概要", "info"))
        val actions = mutableListOf<Any>()
        if (items.isNotEmpty()) {
            sections.add(section("content", "画面预览", "image"))
        }
        actions.add(mapOf("id" to "parseInput", "label" to "获取文件列表并下载", "primary" to true))
        return mapOf(
            "ok" to true,
            "page" to mapOf(
                "id" to "preview",
                "title" to "链接内容",
                "sections" to sections,
                "fields" to listOf(
                    mapOf("id" to "input", "type" to "hidden", "value" to preview.input),
                ),
                // The name and the summary are facts, not inputs. A title row
                // and a subtitle item read correctly and cannot be mistaken for
                // something the user is expected to type into.
                "items" to listOf(
                    mapOf(
                        "id" to "contentHead",
                        "type" to "headline",
                        "section" to "summary",
                        "title" to preview.name,
                        "subtitle" to summary.joinToString(" · "),
                    ),
                ) + items,
                "actions" to actions,
            ),
        )
    }

    /**
     * "We asked and could not tell" is a real answer, not an error. Show it
     * plainly and hand the user the path that always works.
     */
    private fun unknown(input: String): Map<String, Any> = mapOf(
        "ok" to true,
        "page" to mapOf(
            "id" to "previewUnknown",
            "title" to "链接内容",
            "sections" to listOf(section("summary", "内容概要", "info")),
            "fields" to listOf(
                mapOf("id" to "input", "type" to "hidden", "value" to input),
            ),
            "items" to listOf(
                mapOf(
                    "id" to "explanation",
                    "type" to "headline",
                    "section" to "summary",
                    "title" to "无法预览这个链接",
                    "subtitle" to "网页、网盘页面和需要登录的链接通常都无法预览。可以直接获取文件列表。",
                ),
            ),
            "actions" to listOf(
                mapOf("id" to "parseInput", "label" to "直接获取文件列表", "primary" to true),
            ),
        ),
    )

    private fun failed(preview: PreviewLookup.Preview.Failed): Map<String, Any> = mapOf(
        "ok" to true,
        "page" to mapOf(
            "id" to "previewFailed",
            "title" to "链接内容",
            "sections" to listOf(section("summary", "内容概要", "info")),
            "fields" to listOf(
                mapOf("id" to "input", "type" to "hidden", "value" to preview.input),
            ),
            "items" to listOf(
                mapOf(
                    "id" to "explanation",
                    "type" to "headline",
                    "section" to "summary",
                    "title" to "预览失败",
                    "subtitle" to preview.reason,
                ),
            ),
            "actions" to listOf(
                mapOf("id" to "preview", "label" to "重试预览"),
                mapOf("id" to "parseInput", "label" to "直接获取文件列表", "primary" to true),
            ),
        ),
    )

    private fun kindLabel(type: String, fileType: String): String? = when (fileType) {
        "video" -> "视频"
        "audio" -> "音频"
        "image" -> "图片"
        "document" -> "文档"
        "archive" -> "压缩包"
        "font" -> "字体"
        "text" -> "文本"
        "folder" -> if (type == "FOLDER") "文件夹" else null
        else -> null
    }

    private fun section(id: String, title: String, icon: String) =
        mapOf("id" to id, "title" to title, "icon" to icon)
}
