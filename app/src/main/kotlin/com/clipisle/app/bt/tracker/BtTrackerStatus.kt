package com.clipisle.app.bt.tracker

/**
 * Tracker 运行时状态投影。
 *
 * 任务书第 8 节的四种事实必须分开记录，不能混为一谈：
 *  1. 订阅获取成功 → 只代表列表更新
 *  2. Tracker 已响应 → 只代表协议返回
 *  3. 有 peers → 不等于已连接
 *  4. 已连接 → 不等于有可用分片
 */
object BtTrackerStatus {

    /** 单条 Tracker 的 announce 状态 */
    enum class AnnounceState {
        /** 还没接近过 */
        notAttempted,

        /** 正在联系 */
        contacting,

        /** 收到有效协议响应（返回 0 节点也算） */
        responded,

        /** 暂时失败 */
        temporarilyFailed,
    }

    /** 错误枚举。任务书 8：只映射有界枚举，不输出原始 message（可能含 passkey）。 */
    enum class ErrorKind {
        timeout,
        dns,
        connection,
        http,
        protocol,
        rejected,
        unknown,
    }

    /** 单条状态快照 */
    data class Entry(
        val canonicalKey: String,
        /** 展示用 host:port（不含 path/query） */
        val displayHost: String,
        val protocol: String,
        /** tracker 或 supplement */
        val origin: String,
        /** 来源 ID；原始项为空 */
        val sourceId: String,
        val state: AnnounceState,
        /** 最后一次回复的节点数；-1 表示没有回复 */
        val lastPeerCount: Int = -1,
        val errorKind: ErrorKind? = null,
        /** 采集时间 */
        val observedAt: Long = 0,
        /** 所在 tier */
        val tier: Int = 0,
    ) {
        val hasResponded: Boolean get() = state == AnnounceState.responded
    }

    /** 任务级汇总 */
    data class TaskSummary(
        val entries: List<Entry>,
        /** 引擎给出的已连接节点数（不求和 numPeers） */
        val connectedPeers: Int,
        /** 已响应 Tracker 数 */
        val respondedTrackers: Int,
        /** 总 Tracker 数 */
        val totalTrackers: Int,
        val observedAt: Long,
    ) {
        companion object {
            /**
             * 从条目列表汇总。
             *
             * 注意：`respondedTrackers` 只数真正收到协议响应的；
             * `connectedPeers` 由调用方从引擎直接取，不在这里累加
             * —— 不同 Tracker 返回的节点可能重复（任务书 4.2）。
             */
            fun of(
                entries: List<Entry>,
                connectedPeers: Int,
                observedAt: Long = System.currentTimeMillis(),
            ): TaskSummary = TaskSummary(
                entries = entries,
                connectedPeers = connectedPeers,
                respondedTrackers = entries.count { it.hasResponded },
                totalTrackers = entries.size,
                observedAt = observedAt,
            )
        }
    }

    /**
     * 网络变化后，旧的健康结论必须作废。
     *
     * 任务书 8：Wi-Fi/蜂窝、VPN/默认网络变化后旧样本标为历史，
     * 当前状态重新为未知。**不是只在 true/false 翻转时换代** ——
     * Wi-Fi 换 Wi-Fi 或 VPN 切换同样要作废。
     */
    fun resetForNewNetwork(entries: List<Entry>): List<Entry> = entries.map {
        it.copy(
            state = AnnounceState.notAttempted,
            lastPeerCount = -1,
            errorKind = null,
            observedAt = 0,
        )
    }

    /**
     * 把 libtorrent 的错误码映射到有界枚举。
     *
     * 不把原始 `message()` 传出去：里面可能含 passkey（任务书 8）。
     */
    fun classifyError(code: Int, errorMessage: String?): ErrorKind {
        // libtorrent 的 error_code 值域较宽，这里按关键字做保守归纳。
        val m = errorMessage?.lowercase() ?: ""
        return when {
            m.contains("timed out") || m.contains("timeout") -> ErrorKind.timeout
            m.contains("host not found") || m.contains("dns") -> ErrorKind.dns
            m.contains("connection") || m.contains("refused") ||
                m.contains("unreachable") -> ErrorKind.connection
            m.contains("http") -> ErrorKind.http
            m.contains("protocol") || m.contains("parse") -> ErrorKind.protocol
            m.contains("denied") || m.contains("unauthorized") ||
                m.contains("not authorized") -> ErrorKind.rejected
            code == 0 -> ErrorKind.unknown
            else -> ErrorKind.unknown
        }
    }

    /** 展示文案 key（由 Dart 侧翻译） */
    fun messageKey(state: AnnounceState): String = when (state) {
        AnnounceState.notAttempted -> "tracker.state.notAttempted"
        AnnounceState.contacting -> "tracker.state.contacting"
        AnnounceState.responded -> "tracker.state.responded"
        AnnounceState.temporarilyFailed -> "tracker.state.temporarilyFailed"
    }

    fun messageKey(kind: ErrorKind): String = "tracker.error.${kind.name}"
}
