package com.clipisle.btplugin

import java.math.BigDecimal

/** Plugin-owned presentation data; the host only renders the generic vocabulary. */
internal object PluginSettingsPages {
    fun settings(settings: Map<String, Any>): Map<String, Any> = page(
        "settings", "磁力下载设置",
        autoSaveAction = "saveSettings",
        sections = listOf(section("preview", "内容预览", "image"), section("network", "网络与任务", "network"), section("speed", "传输速度", "speed"), section("sources", "连接来源", "tracker")),
        fields = listOf(
            mapOf("id" to "onlinePreviewEnabled", "type" to "switch", "section" to "preview", "icon" to "image", "label" to "在线内容预览", "subtitle" to "仅向 whatslink.info 发送资源哈希", "value" to (settings["onlinePreviewEnabled"] ?: true)),
            mapOf("id" to "unmeteredOnly", "type" to "switch", "section" to "network", "icon" to "network", "label" to "仅在非计费网络下载", "subtitle" to "通常为 Wi-Fi", "value" to (settings["unmeteredOnly"] ?: true)),
            mapOf("id" to "maxActiveDownloads", "type" to "select", "section" to "network", "icon" to "download", "label" to "同时下载任务", "value" to (settings["maxActiveDownloads"] ?: 1).toString(), "options" to listOf(option("1", "1"), option("2", "2"))),
            rate("downloadRateLimit", "下载限速", settings["downloadRateLimit"]),
            rate("uploadRateLimit", "上传限速", settings["uploadRateLimit"]),
        ),
        actions = listOf(
            row("trackers", "Tracker 来源", "tracker", "sources"),
        ),
    )

    fun trackers(config: Map<String, Any>): Map<String, Any> {
        val sources = (config["sources"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        return page("trackers", "Tracker 来源",
            sections = listOf(section("sources", "订阅来源", "tracker"), section("manage", "管理", "settings")),
            fields = listOf(hidden("revision", config["revision"])),
            items = sources.map { source ->
                mapOf("id" to (source["id"] ?: ""), "section" to "sources", "icon" to "tracker", "title" to (source["name"] ?: ""),
                    "subtitle" to if (source["enabled"] == true) "已启用" else "已停用", "action" to "trackerSource")
            },
            actions = listOf(
                row("newTrackerSource", "新增来源", "add", "sources"),
                row("customTrackers", "自定义 Tracker", "edit", "manage"),
                row("trackerOptions", "更新与筛选", "settings", "manage"),
                row("confirmRestoreTrackers", "恢复推荐来源", "refresh", "manage"),
                mapOf("id" to "syncTrackers", "label" to "同步来源", "primary" to true),
            ),
        )
    }

    fun trackerOptions(config: Map<String, Any>): Map<String, Any> {
        val interval = (config["syncIntervalHours"] as? Number)?.toInt() ?: 24
        return page("trackerOptions", "更新与筛选",
            autoSaveAction = "saveTrackerOptions",
            sections = listOf(section("update", "自动更新", "refresh"), section("filter", "补充与筛选", "tracker")),
            fields = listOf(
                hidden("revision", config["revision"]),
                toggle("autoUpdateEnabled", "自动更新来源", "update", config["autoUpdateEnabled"]),
                mapOf("id" to "syncIntervalHours", "type" to "select", "section" to "update", "icon" to "refresh", "label" to "更新间隔", "unit" to "小时", "value" to interval.toString(), "options" to (listOf(6, 12, 24, 48, 72) + interval).distinct().sorted().map { option(it.toString(), it.toString()) }),
                toggle("supplementsEnabled", "补充公共 Tracker", "filter", config["supplementsEnabled"]),
                toggle("blacklistEnabled", "过滤不可用 Tracker", "filter", config["blacklistEnabled"]),
                mapOf("id" to "maxSupplements", "type" to "number", "section" to "filter", "icon" to "tracker", "label" to "每任务补充数量", "integer" to true, "min" to 5, "max" to 20, "value" to (config["maxSupplements"] ?: 20)),
            ),
        )
    }

    fun confirmRestore(config: Map<String, Any>): Map<String, Any> = page(
        "confirmRestoreTrackers", "恢复推荐来源？",
        fields = listOf(hidden("revision", config["revision"])),
        items = listOf(mapOf("id" to "explanation", "title" to "推荐来源将恢复默认配置，自定义来源保留。")),
        actions = listOf(mapOf("id" to "restoreTrackers", "label" to "确认恢复", "primary" to true), mapOf("id" to "trackers", "label" to "取消")),
    )

    /** Decimal KB/s; preserve fractional values instead of silently rounding saved limits. */
    fun rateToUi(bytes: Any?): String = BigDecimal((bytes as? Number)?.toLong() ?: 0L)
        .movePointLeft(3).stripTrailingZeros().toPlainString()

    fun parseRate(value: Any?): Int? = try {
        val bytes = BigDecimal(value?.toString() ?: "").movePointRight(3).intValueExact()
        bytes.takeIf { it >= 0 }
    } catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }

    private fun rate(id: String, label: String, value: Any?) = mapOf(
        "id" to id, "type" to "number", "section" to "speed", "icon" to "speed", "label" to label,
        "unit" to "KB/s", "subtitle" to "0 为不限速", "min" to 0, "max" to 2147483.647, "value" to rateToUi(value),
    )
    private fun toggle(id: String, label: String, section: String, value: Any?) = mapOf("id" to id, "type" to "switch", "section" to section, "icon" to "settings", "label" to label, "value" to (value ?: true))
    private fun hidden(id: String, value: Any?) = mapOf("id" to id, "type" to "hidden", "value" to value)
    private fun option(value: String, label: String) = mapOf("value" to value, "label" to label)
    private fun section(id: String, title: String, icon: String) = mapOf("id" to id, "title" to title, "icon" to icon)
    private fun row(id: String, label: String, icon: String, section: String) = mapOf("id" to id, "label" to label, "icon" to icon, "section" to section, "presentation" to "row")
    private fun page(id: String, title: String, sections: List<Any> = emptyList(), fields: List<Any> = emptyList(), items: List<Any> = emptyList(), actions: List<Any> = emptyList(), autoSaveAction: String? = null): Map<String, Any> = mapOf("ok" to true, "page" to (mapOf("id" to id, "title" to title, "sections" to sections, "fields" to fields, "items" to items, "actions" to actions) + if (autoSaveAction == null) emptyMap() else mapOf("autoSaveAction" to autoSaveAction)))
}
