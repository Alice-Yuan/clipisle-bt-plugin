package com.clipisle.btplugin

/** Remote preview hints; selecting files always obtains local torrent metadata. */
internal object PreviewPage {
    fun build(preview: PreviewLookup.Preview): Map<String, Any> {
        val items = mutableListOf<Map<String, Any>>()
        when (preview) {
            is PreviewLookup.Preview.Known -> {
                val summary = buildList<Map<String, Any>> {
                    preview.sizeBytes?.let { add(mapOf("text" to PreviewFormat.bytes(it))) }
                    preview.fileCount?.let { add(mapOf("key" to "{count} 个文件", "arguments" to mapOf("count" to it))) }
                }
                items.add(mapOf("id" to "contentHead", "type" to "headline", "title" to preview.name,
                    "titleLiteral" to true, "subtitleParts" to summary))
                if (preview.screenshots.isNotEmpty()) items.add(mapOf(
                    "id" to "screenshots", "type" to "gallery", "images" to preview.screenshots,
                ))
            }
            is PreviewLookup.Preview.Unknown -> items.add(mapOf(
                "id" to "status", "type" to "headline", "title" to "暂无内容预览",
                "subtitle" to "可以继续获取文件列表",
            ))
            is PreviewLookup.Preview.Failed -> items.add(mapOf(
                "id" to "status", "type" to "headline", "title" to "预览暂不可用", "subtitle" to preview.reason,
            ))
        }
        val actions = buildList<Map<String, Any>> {
            if (preview is PreviewLookup.Preview.Failed) add(mapOf(
                "id" to "preview", "label" to "重试预览", "cancelMethod" to "cancelInput",
            ))
            add(mapOf("id" to "parseInput", "label" to "选择文件", "primary" to true, "cancelMethod" to "cancelInput"))
        }
        return mapOf("ok" to true, "page" to mapOf(
            "id" to "preview", "title" to "链接内容", "stickyActions" to true,
            "fields" to listOf(mapOf("id" to "input", "type" to "hidden", "value" to preview.input)),
            "items" to items, "actions" to actions,
        ))
    }
}
