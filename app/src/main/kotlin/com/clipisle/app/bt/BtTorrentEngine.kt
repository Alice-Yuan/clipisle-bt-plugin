package com.clipisle.app.bt

import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.swig.add_torrent_params
import com.frostwire.jlibtorrent.swig.error_code
import java.io.File

/**
 * 引擎适配层。**唯一接触 libtorrent add/flags 细节的地方。**
 *
 * 任务书 7.2 第 3–4 条是这里最容易出错的两点：
 *  - `read_resume_data()` 会把旧的 Tracker 与覆盖 flags 一起带回来，
 *    只改 `TorrentInfo` 无效，旧地址可能复活
 *  - 必须**先**设好 Tracker、文件优先级、private 发现 flags 和 PAUSED，
 *    再 add；是否 resume 由执行器决定
 */
/** 一条 Tracker 的 url 与 tier。用自有类型避开 jlibtorrent.Pair 命名冲突。 */
internal data class TrackerEntry(val url: String, val tier: Int)

internal object BtTorrentEngine {
    /** Reject malformed overrides before any JNI work; null alone means preserve existing list. */
    fun validateTrackerOverride(trackers: List<String>?, tiers: List<Int>?): List<TrackerEntry>? {
        if (trackers == null) { require(tiers == null); return null }
        require(tiers != null && tiers.size == trackers.size && tiers.all { it in 0..255 })
        require(trackers.all { it.isNotBlank() })
        return trackers.zip(tiers).map { TrackerEntry(it.first, it.second) }
    }
    fun resumeTrackers(bytes: ByteArray?): List<TrackerEntry> {
        if (bytes == null) return emptyList()
        val ec = error_code()
        val vector = Vectors.bytes2byte_vector(bytes)
        try {
            val raw = add_torrent_params.read_resume_data(vector, ec)
            try {
                if (ec.value() != 0) return emptyList()
                val params = AddTorrentParams(raw)
                val tiers = params.trackerTiers()
                return params.trackers().mapIndexed { i, url -> TrackerEntry(url, tiers.getOrElse(i) { 0 }) }
            } finally { raw.delete() }
        } finally { vector.delete(); ec.delete() }
    }

    /**
     * 加入一个 torrent。
     *
     * @param trackers 有效 Tracker 列表（已按策略合并）；null 表示不改动
     * @param tiers 与非 null 的 [trackers] 等长；错配拒绝，不能悄悄压平原始 tier
     * @param privateTorrent 已知私有：关闭 DHT/LSD/PEX
     */
    fun add(
        session: SessionManager,
        info: TorrentInfo,
        directory: File,
        selected: Set<Int>,
        resume: ByteArray?,
        trackers: List<String>? = null,
        tiers: List<Int>? = null,
        privateTorrent: Boolean = false,
    ): TorrentHandle {
        validateTrackerOverride(trackers, tiers)
        val params = resume?.let { bytes ->
            val ec = error_code()
            val vector = Vectors.bytes2byte_vector(bytes)
            try {
                val raw = add_torrent_params.read_resume_data(vector, ec)
                if (ec.value() != 0) {
                    raw.delete()
                    null
                } else {
                    AddTorrentParams(raw)
                }
            } finally {
                vector.delete()
                ec.delete()
            }
        } ?: AddTorrentParams()

        params.torrentInfo(info)
        params.savePath(directory.absolutePath)
        params.swig().get_renamed_files().clear()

        params.filePriorities(
            Array(info.numFiles()) {
                if (it in selected) Priority.NORMAL else Priority.IGNORE
            },
        )

        // ---- Tracker：必须在 add 之前写入，并显式 OVERRIDE_TRACKERS ----
        //
        // 不加 OVERRIDE_TRACKERS 时，resume 里的旧列表会与这里合并，
        // 被用户禁用/撤销的地址会复活（任务书 7.2 第 3 条）。
        var flags = params.flags()
        if (trackers != null) {
            params.trackers(trackers)
            // 两个列表长度必须一致，否则 libtorrent 会拒绝或错配
            params.trackerTiers(requireNotNull(tiers))
            flags = flags.or_(TorrentFlags.OVERRIDE_TRACKERS)
        }

        // ---- 私有种子：关闭公开发现机制 ----
        if (privateTorrent || info.isPrivate) {
            flags = flags
                .or_(TorrentFlags.DISABLE_DHT)
                .or_(TorrentFlags.DISABLE_LSD)
                .or_(TorrentFlags.DISABLE_PEX)
        }

        // 先保持暂停，由执行器决定何时 resume
        params.flags(
            flags
                .and_(TorrentFlags.AUTO_MANAGED.inv())
                .and_(TorrentFlags.SEED_MODE.inv())
                .and_(TorrentFlags.UPLOAD_MODE.inv())
                .and_(TorrentFlags.STOP_WHEN_READY.inv())
                .or_(TorrentFlags.PAUSED),
        )

        val ec = error_code()
        try {
            val handle = SessionHandle(session.swig()).addTorrent(params, ErrorCode(ec))
            check(ec.value() == 0 && handle.isValid) { "Torrent add failed (${ec.value()})" }
            return handle
        } finally {
            ec.delete()
        }
    }

    fun pause(handle: TorrentHandle) {
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
        handle.pause()
    }

    /**
     * 已完成字节数。
     *
     * 用 `PIECE_GRANULARITY` 而不是 `fileProgress()`：
     * 前者按已校验分片累计，后者包含未校验的进行中字节。
     * 任务书要求「按已校验分片累计所选字节」。
     */
    fun verifiedBytes(handle: TorrentHandle, record: BtTaskRecord): Long {
        val progress = handle.fileProgress(TorrentHandle.PIECE_GRANULARITY)
        return record.files.filter { it.fileIndex in record.selectedFileIndices }.sumOf {
            progress.getOrNull(it.fileIndex)?.coerceIn(0L, it.lengthBytes) ?: 0L
        }
    }

    /**
     * 读取 handle 当前的 Tracker 列表（只读副本）。
     *
     * 任务书 7.3：`handle.trackers()` 返回的是复制向量，
     * 可以在当前串行操作内读取，但**不能跨异步等待保存包装对象**。
     */
    fun currentTrackers(handle: TorrentHandle): List<TrackerEntry> {
        check(handle.isValid)
        return handle.trackers().map { entry ->
                val url = entry.url() ?: ""
                val tier = runCatching { entry.tier().toInt() }.getOrDefault(0)
                TrackerEntry(url, tier)
            }.filter { it.url.isNotEmpty() }
    }

    /** 只新增时优先用这个，避免整表替换造成重发通告。 */
    fun addTracker(handle: TorrentHandle, url: String, tier: Int): Boolean {
        require(tier in 0..255)
        return runCatching {
            val entry = AnnounceEntry(url)
            entry.tier(tier.toShort())
            handle.addTracker(entry)
            true
        }.getOrDefault(false)
    }

    /**
     * 整表替换。**需要删除或换 tier 时才用。**
     *
     * 任务书 7.3 明确：不能声称完全无重新通告 ——
     * 固定版本的原生转换不保留内部 endpoint 计时。
     */
    fun replaceTrackers(handle: TorrentHandle, entries: List<TrackerEntry>): Boolean {
        require(entries.all { it.tier in 0..255 })
        return runCatching {
            val previous = handle.trackers().associateBy { it.url() }
            val list = entries.map { e ->
                (previous[e.url] ?: AnnounceEntry(e.url)).also { it.tier(e.tier.toShort()) }
            }
            handle.replaceTrackers(list)
            true
        }.getOrDefault(false)
    }
}
