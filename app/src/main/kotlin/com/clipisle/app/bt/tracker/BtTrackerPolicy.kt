package com.clipisle.app.bt.tracker

import java.security.MessageDigest

/**
 * Tracker 合并策略。**纯函数**，不做 IO、不碰引擎。
 *
 * 任务书 7.1 的核心约束：
 *  - 有效列表 = 原始项 + 受保护未归属项 + （确认 public 且开关允许的）已应用补充项
 *  - 同一 URL 可同时属于原始与订阅：只保留一次连接，**原始归属优先**
 *  - 原始 tier 保持不变，补充项追加到其后的备用 tier
 *  - 补充上限 20，不截断原始列表
 *  - private 任务不得使用公共补充
 */
object BtTrackerPolicy {

    /** 每任务补充上限（任务书 5.2） */
    const val MAX_SUPPLEMENTS = 20

    enum class Privacy { unknown, public, private }

    /** Pure compare-and-set identity for the engine table previewed by the user. */
    data class PlanVersion(val taskGeneration: Int, val handleGeneration: String, val runtimeEpoch: String,
        val networkEpoch: Long, val policyRevision: Int, val configRevision: Int,
        val catalogRevision: Long, val tableSignature: List<String>)

    fun canApplyPlan(expected: PlanVersion, actual: PlanVersion, expiresAt: Long, now: Long,
        terminal: Boolean): Boolean = !terminal && now <= expiresAt && expected == actual

    data class CustomInput(val urls: List<String>, val inputLines: List<Int>, val duplicateCount: Int,
        val invalidLines: List<Int>)

    /** Keep user line numbers even when blank/comment/duplicate lines are omitted. */
    fun parseCustomInput(lines: List<String>): CustomInput {
        require(lines.size <= 500 && lines.all { it.length <= 2048 })
        val urls = arrayListOf<String>(); val positions = arrayListOf<Int>()
        val invalid = arrayListOf<Int>(); val seen = hashSetOf<String>(); var duplicates = 0
        lines.forEachIndexed { index, raw ->
            val line = raw.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val parsed = BtTrackerUrl.parse(line)
            if (parsed is BtTrackerUrl.Result.Ok && BtTrackerUrl.isPublicHost(parsed.value.host)) {
                if (seen.add(parsed.value.canonicalKey)) { urls.add(parsed.value.raw); positions.add(index + 1) }
                else duplicates++
            } else invalid.add(index + 1)
        }
        return CustomInput(urls, positions, duplicates, invalid)
    }

    /** 一条原始 Tracker */
    data class Original(
        val url: String,
        val canonicalKey: String,
        val tier: Int,
        /** torrent 或 magnet */
        val from: String,
    )

    /** 一条补充候选 */
    data class Supplement(
        val url: String,
        val canonicalKey: String,
        val sourceIds: List<String>,
    )

    /** 合并结果 */
    data class Effective(
        val urls: List<String>,
        val tiers: List<Int>,
        /** 实际使用的补充项（已达上限或 privacy 不允许时为空） */
        val usedSupplements: List<Supplement>,
        /** 无法附加的原因；null 表示正常 */
        val skipReason: String?,
    ) {
        fun toDigest(): String {
            val sb = StringBuilder()
            for (i in urls.indices) {
                sb.append(urls[i]).append('|').append(tiers.getOrElse(i) { 0 }).append('\n')
            }
            return sha256(sb.toString())
        }
    }

    /**
     * 计算一个任务的有效 Tracker 列表。
     *
     * @param originals 原始项（顺序即 tier 顺序）
     * @param unowned 无法证明归属的已有项，只保护不猜测
     * @param supplements 可用的公共补充候选（已按来源顺序排好）
     * @param privacy 隐私状态
     * @param supplementEnabled 该任务是否启用公共补充
     * @param excludedKeys 用户禁用的补充地址
     */
    fun effective(
        originals: List<Original>,
        unowned: List<Original> = emptyList(),
        supplements: List<Supplement> = emptyList(),
        privacy: Privacy,
        supplementEnabled: Boolean,
        excludedKeys: Set<String> = emptySet(),
    ): Effective {
        require((originals + unowned).all { it.tier in 0..255 }) { "TRACKER_POLICY_INVALID" }
        require(privacy != Privacy.private || unowned.isEmpty()) { "TRACKER_PRIVATE_PROTECTED" }
        // ---- 1) 原始项：tier 原样保留 ----
        val outUrls = ArrayList<String>()
        val outTiers = ArrayList<Int>()
        val seenKeys = HashSet<String>()

        for (o in originals) {
            if (!seenKeys.add(o.canonicalKey)) continue
            outUrls.add(o.url)
            outTiers.add(o.tier)
        }

        // ---- 2) 受保护但未归属的项 ----
        // Private provenance must be proven before an unowned address can be used.
        for (u in unowned) {
            if (!seenKeys.add(u.canonicalKey)) continue
            outUrls.add(u.url)
            outTiers.add(u.tier)
        }

        // ---- 3) 补充项 ----
        // 关键安全规则：只有确认 public 才允许补充。
        // unknown 与 private 一律不补（任务书第 6 节）。
        if (!supplementEnabled) {
            return Effective(outUrls, outTiers, emptyList(), null)
        }
        if (privacy != Privacy.public) {
            val reason = when (privacy) {
                Privacy.private -> "TRACKER_PRIVATE_PROTECTED"
                Privacy.unknown -> "TRACKER_METADATA_UNKNOWN"
                Privacy.public -> ""
            }
            return Effective(outUrls, outTiers, emptyList(), reason)
        }

        // 原始项数已达上限时不硬塞，避免压平原始 tier
        val used = ArrayList<Supplement>()
        val maxTier = outTiers.maxOrNull() ?: 0
        if (maxTier == 255 && supplements.isNotEmpty()) {
            return Effective(outUrls, outTiers, emptyList(), "TRACKER_TIER_EXHAUSTED")
        }
        val nextTier = maxTier + 1

        for (s in supplements) {
            if (used.size >= MAX_SUPPLEMENTS) break
            if (s.canonicalKey in excludedKeys) continue
            // 与原始项重叠：原始优先，不重复添加
            if (s.canonicalKey in seenKeys) continue
            if (!seenKeys.add(s.canonicalKey)) continue
            outUrls.add(s.url)
            outTiers.add(nextTier)
            used.add(s)
        }

        if (used.size < supplements.count { it.canonicalKey !in excludedKeys }) {
            // 还有候选但没放下 —— 说明触到上限
        }

        return Effective(outUrls, outTiers, used, null)
    }

    /**
     * 选补充候选。
     *
     * 任务书 7.1：先排除原始重叠和被禁用项，再让**用户公共项优先**，
     * 余量在启用来源间稳定轮转去重。尽量选不同 host 但不据此淘汰。
     */
    fun pickSupplements(
        userPublic: List<Supplement>,
        bySource: Map<String, List<Supplement>>,
        originalKeys: Set<String>,
        excludedKeys: Set<String>,
        limit: Int = MAX_SUPPLEMENTS,
    ): List<Supplement> {
        val out = ArrayList<Supplement>()
        val seen = HashSet<String>()

        fun tryAdd(s: Supplement): Boolean {
            if (out.size >= limit.coerceIn(0, MAX_SUPPLEMENTS)) return false
            if (s.canonicalKey in originalKeys) return false
            if (s.canonicalKey in excludedKeys) return false
            if (!seen.add(s.canonicalKey)) {
                val existing = out.indexOfFirst { it.canonicalKey == s.canonicalKey }
                if (existing >= 0) out[existing] = out[existing].copy(sourceIds = (out[existing].sourceIds + s.sourceIds).distinct())
                return false
            }
            out.add(s)
            return true
        }

        // 用户公共项优先
        for (s in userPublic) tryAdd(s)

        // 其余在启用来源间稳定轮转
        // 顺序由 bySource 的迭代顺序决定，调用方用 LinkedHashMap 保证稳定
        val sourceLists = bySource.values.map { it.toMutableList() }
        var idx = 0
        while (sourceLists.any { idx < it.size } && out.size < limit.coerceIn(0, MAX_SUPPLEMENTS)) {
            for (list in sourceLists) {
                if (idx < list.size) {
                    tryAdd(list[idx])
                }
            }
            idx++
            if (sourceLists.all { idx >= it.size }) break
        }

        // Selection may stop at twenty, but provenance must include later duplicate sources.
        val provenance = LinkedHashMap<String, LinkedHashSet<String>>()
        for (s in userPublic + bySource.values.flatten()) {
            if (s.canonicalKey in seen) provenance.getOrPut(s.canonicalKey) { linkedSetOf() }.addAll(s.sourceIds)
        }
        return out.map { it.copy(sourceIds = provenance[it.canonicalKey]?.toList() ?: it.sourceIds) }
    }

    /**
     * 差异计划：新增 / 移除 / 保留原始。
     */
    data class Diff(
        val added: List<String>,
        val removed: List<String>,
        val keptOriginals: List<String>,
    ) {
        val isEmpty: Boolean
            get() = added.isEmpty() && removed.isEmpty()
    }

    fun diff(
        current: Effective,
        desired: Effective,
        originalKeys: Set<String>,
    ): Diff {
        val currentKeys = current.urls.map { keyOf(it) }.toSet()
        val desiredKeys = desired.urls.map { keyOf(it) }.toSet()

        val added = ArrayList<String>()
        val removed = ArrayList<String>()
        val kept = desired.urls.filter { keyOf(it) in originalKeys }.toMutableList()

        for (i in desired.urls.indices) {
            val k = keyOf(desired.urls[i])
            if (k !in currentKeys) added.add(desired.urls[i])
        }
        for (i in current.urls.indices) {
            val k = keyOf(current.urls[i])
            if (k !in desiredKeys) {
                if (k in originalKeys) {
                    // 理论上不该发生（原始项永远保留），记下来便于断言
                    if (current.urls[i] !in kept) kept.add(current.urls[i])
                } else {
                    removed.add(current.urls[i])
                }
            }
        }
        return Diff(added, removed, kept)
    }

    fun keyOf(url: String): String {
        val r = BtTrackerUrl.parse(url)
        return if (r is BtTrackerUrl.Result.Ok) r.value.canonicalKey else url
    }

    /** Public DTOs carry only opaque IDs; a canonical URL can contain a passkey. */
    fun idForUrl(url: String): String = sha256(keyOf(url))

    fun disabled(url: String, keys: Collection<String>): Boolean =
        keyOf(url) in keys || idForUrl(url) in keys

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
