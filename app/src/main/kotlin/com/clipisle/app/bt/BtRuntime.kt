package com.clipisle.app.bt

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import com.clipisle.app.bt.tracker.BtTrackerPolicy
import com.clipisle.app.bt.tracker.BtTrackerProber
import com.clipisle.app.bt.tracker.BtTrackerRanker
import com.clipisle.app.bt.tracker.BtTrackerStatus
import com.clipisle.app.bt.tracker.BtTrackerStore
import com.clipisle.app.bt.tracker.BtTrackerTaskStore
import com.clipisle.app.bt.tracker.BtTrackerSourceClient
import com.frostwire.jlibtorrent.alerts.TrackerErrorAlert
import com.frostwire.jlibtorrent.alerts.TrackerReplyAlert
import com.frostwire.jlibtorrent.alerts.TrackerWarningAlert
import com.clipisle.app.bt.tracker.BtTrackerUrl
import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.alerts.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.*

/** Native queue owns the session, durable state and periodic progress even without a Flutter UI. */
class BtRuntime private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "BtRuntime"
        private const val MAX_METADATA = 16 * 1024 * 1024
        @Volatile private var instance: BtRuntime? = null
        fun get(context: Context): BtRuntime = instance ?: synchronized(this) {
            instance ?: BtRuntime(context.applicationContext).also { instance = it }
        }
        internal fun resetForTest() = synchronized(this) {
            instance?.closeForTest()
            instance = null
        }
    }
    private val queue = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bt-runtime").apply { isDaemon = true } }
    private val metadataWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "bt-metadata").apply { isDaemon = true } }
    private val store = BtTaskStore(appContext)
    private val prefs = appContext.getSharedPreferences("clipisle_bt", Context.MODE_PRIVATE)
    private val networkPolicy = BtNetworkPolicy(appContext)

    // ---- Tracker ----
    private val trackerStore = BtTrackerStore(appContext)
    private val taskTrackerStore = BtTrackerTaskStore(File(appContext.filesDir, "bt/trackers/tasks"))
    private var appForeground = true
    private var trackerNetworkEpoch = 0L
    private val trackerBindings = ConcurrentHashMap<Long, TrackerBinding>()
    private data class TrackerBinding(val taskId: String, val generation: Int, val handleGeneration: String)
    private val trackerClient = com.clipisle.app.bt.tracker.BtTrackerSourceClient()
    private val trackerProber = com.clipisle.app.bt.tracker.BtTrackerProber()

    /** Tracker 健康度，key = url。只在队列线程读写。 */
    private val trackerHealth = HashMap<String, com.clipisle.app.bt.tracker.BtTrackerProber.Health>()

    /** 上一个任务用过哪些补充项，用于给「复用」加分，减少刷新抖动。 */
    private var previousSupplementUrls: Set<String> = emptySet()
    private var customTrackerValidation: CustomTrackerValidation? = null
    private data class CustomTrackerValidation(val revision: Int, val networkEpoch: Long,
        val urls: List<String>, val inputLines: List<Int>, val duplicates: Int, val validateOnly: Boolean,
        val worker: Future<BtTrackerSourceClient.PublicValidation>,
        val result: CompletableFuture<Map<String, Any>>)

    /** 订阅编排。网络 I/O 走它自己的线程池，不占 Runtime 串行队列。 */
    val trackerService = com.clipisle.app.bt.tracker.BtTrackerService(appContext, trackerStore, trackerClient)

    /** 全局配置。启动时从磁盘读，损坏时保留文件并降级。 */
    private var trackerConfig: BtTrackerStore.Config = BtTrackerStore.Config.defaults()

    /** 来源缓存与合并池。可能为 null（从未同步过或损坏）。 */
    private var trackerCatalog: BtTrackerStore.Catalog? = null

    /** 配置损坏标记：为 true 时关闭全部公共补充并报错（任务书 5.2 第 10 条）。 */
    private var trackerConfigDamaged = false
    private var networkHandle: AutoCloseable? = null
    private var session: SessionManager? = null
    private val epoch = UUID.randomUUID().toString()
    private var sequence = 0L
    private var loaded = false
    @Volatile private var closed = false
    private var owner: String? = null
    private var pendingOwner: String? = null
    private var discoveryEnabled = true
    private var lastCheckpoint = 0L
    private var lastNotification = 0L
    private var maxActive = prefs.getInt("maxActive", 1).coerceIn(1, 2)
    private var downloadLimit = prefs.getInt("downloadLimit", 0).coerceAtLeast(0)
    private var uploadLimit = prefs.getInt("uploadLimit", 128000).coerceAtLeast(0)
    private val tasks = LinkedHashMap<String, Task>()
    private val metadata = LinkedHashMap<String, Metadata>()
    private var resolving: Resolve? = null
    private val deleting = HashMap<String, CompletableFuture<Map<String, Any>>>()

    private data class Task(var record: BtTaskRecord, var handle: TorrentHandle? = null,
        var revision: Long = 0, var rate: Long = 0, var uploadRate: Long = 0,
        var peers: Int = 0, var checkpointPending: Boolean = false,
        var finalFlush: Boolean = false, var checkpointAt: Long = 0,
        var lastProgressAt: Long = System.currentTimeMillis()) {

        /**
         * Tracker 的最近状态。
         *
         * 只保留最近一次响应与有限信息，**不记录无限时间序列**
         * （任务书第 8 节）。key 是 canonicalKey。
         */
        var trackerStates: MutableMap<String, BtTrackerStatus.Entry> = mutableMapOf()

        /** 状态采集时间，供 UI 显示「2 分钟前」 */
        var trackerStatesAt: Long = 0
        var trackerPolicy: BtTrackerTaskStore.Policy? = null
        var handleGeneration: String = ""
    }
    private data class Metadata(val bytes: ByteArray, val info: TorrentInfo, val files: List<BtFileRecord>,
        val key: String, val v1: String?, val v2: String?, val name: String, val source: String, val magnet: String) {

        /**
         * 隐私状态。
         *
         * 任务书第 6 节：只有**成功校验完整元数据后**才能判定。
         * 有完整元数据时，`isPrivate()==false` 是正常的公开种子；
         * 缺元数据或读取异常才是 unknown，不能默认 public。
         */
        val privacy: BtTrackerPolicy.Privacy
            get() = try {
                if (info.isPrivate) BtTrackerPolicy.Privacy.private
                else BtTrackerPolicy.Privacy.public
            } catch (_: Throwable) {
                BtTrackerPolicy.Privacy.unknown
            }

        /**
         * 原始 Tracker 快照。
         *
         * 从**元数据本身**读取，不从已合并的 handle 列表反推 ——
         * 否则无法区分「种子自带」与「我们加的」（任务书 7.1）。
         */
        val originals: List<BtTrackerPolicy.Original>
            get() {
                val result = info.trackers().map { entry ->
                    val url = entry.url() ?: ""
                    if (url.isEmpty()) return@map null
                    val tier = entry.tier().toInt()
                    require(tier in 0..255)
                    val key = when (val p = BtTrackerUrl.parse(url)) {
                        is BtTrackerUrl.Result.Ok -> p.value.canonicalKey
                        is BtTrackerUrl.Result.Reject -> url
                    }
                    BtTrackerPolicy.Original(
                        url = url,
                        canonicalKey = key,
                        tier = tier,
                        from = if (source == "torrent") "torrent" else "magnet",
                    )
                }.filterNotNull().toMutableList()
                val originalMagnet = (BtInput.parse(magnet) as? BtInput.ParseResult.Ok)?.info
                originalMagnet?.trackers?.forEachIndexed { index, url ->
                    if (result.none { it.canonicalKey == BtTrackerPolicy.keyOf(url) }) {
                        result.add(BtTrackerPolicy.Original(url, BtTrackerPolicy.keyOf(url), index.coerceAtMost(255), "magnet"))
                    }
                }
                return result
            }
    }
    private data class Resolve(val id: String, val result: CompletableFuture<Map<String, Any>>,
        var worker: Future<*>? = null, var canceled: Boolean = false)

    init {
        networkPolicy.unmeteredOnly = prefs.getBoolean("unmeteredOnly", true)

        // Tracker 配置与缓存。
        //
        // 任务书 5.2 第 10 条：全局配置两份都坏才关闭全部公共补充并报错；
        // **保留损坏文件**，不以默认空配置覆盖。
        when (val cfg = trackerStore.loadConfig()) {
            is BtTrackerStore.LoadResult.Ok -> trackerConfig = cfg.value
            is BtTrackerStore.LoadResult.Absent -> {
                trackerConfig = BtTrackerStore.Config.defaults()
                runCatching { trackerStore.saveConfig(trackerConfig) }
            }
            is BtTrackerStore.LoadResult.Damaged -> {
                trackerConfig = BtTrackerStore.Config.defaults()
                    .copy(supplementsEnabled = false)
                trackerConfigDamaged = true
                Log.w(TAG, "tracker config damaged; supplements disabled, files kept")
            }
        }
        when (val cat = trackerStore.loadCatalog()) {
            is BtTrackerStore.LoadResult.Ok -> trackerCatalog = cat.value
            is BtTrackerStore.LoadResult.Damaged -> {
                // 缓存坏了只隔离缓存：仍可用原始 Tracker 与 DHT 下载
                trackerCatalog = null
                Log.w(TAG, "tracker catalog damaged; kept on disk")
            }
            is BtTrackerStore.LoadResult.Absent -> trackerCatalog = null
        }

        queue.scheduleWithFixedDelay({
            if (!closed) try { tick() } catch (e: Exception) {
                Log.e(TAG, "runtime tick failed: " + e.javaClass.simpleName)
                stopForStorageFailure()
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
        trackerService.bind({ trackerConfig }, { action -> dispatch(action) },
            { appForeground && networkPolicy.isSatisfied() })
        networkHandle = networkPolicy.registerEvents { event -> dispatch {
            if (trackerNetworkEpoch != event.epoch) {
                trackerNetworkEpoch = event.epoch
                tasks.values.forEach { t ->
                    t.trackerStates = BtTrackerStatus.resetForNewNetwork(t.trackerStates.values.toList())
                        .associateBy { it.canonicalKey }.toMutableMap()
                }
                trackerService.invalidate()
                cancelCustomTrackerValidation("CANCELED")
            }
            if (!event.satisfied) { trackerService.invalidate(); cancelCustomTrackerValidation("WAITING_NETWORK") }
            if (loaded) onNetworkChanged(event.satisfied)
        } }
    }
    private fun <T> post(block: () -> T): Future<T> = queue.submit(Callable { block() })
    private fun dispatch(block: () -> Unit) { if (!closed) runCatching { queue.execute(block) } }
    private fun fail(code: String, message: String) = mapOf<String, Any>("ok" to false, "code" to code, "message" to message)
    private fun success() = mapOf<String, Any>("ok" to true)
    private fun change(t: Task, record: BtTaskRecord) {
        if (t.record != record) { t.record = record; t.revision = ++sequence }
    }
    private fun persist() = store.save(tasks.values.map { it.record })
    private fun load() {
        if (loaded) return
        var markHistoricalCompletions = false
        for (r in store.load()) {
            var restored = if (!BtState.isTerminal(r.actualState) && r.actualState != BtState.PAUSED)
                r.copy(actualState = BtState.INTERRUPTED, pauseReason = "app_restarted") else r
            if (restored.actualState == BtState.COMPLETED && !restored.completionNotified) {
                restored = restored.copy(completionNotified = true)
                markHistoricalCompletions = true
            }
            tasks[r.taskId] = Task(restored, revision = ++sequence)
        }
        loaded = true
        // Loading historical records is not a new completion event.
        if (markHistoricalCompletions) persist()
    }
    fun loadFromDisk(): Future<Int> = post { load(); tasks.size }
    fun capabilities(): Future<Map<String, Any>> = post {
        BtEngine.capabilities() + mapOf("backgroundRestricted" to isBackgroundRestricted())
    }
    private fun isBackgroundRestricted(): Boolean = Build.VERSION.SDK_INT >= 28 &&
        runCatching { appContext.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true }
            .getOrDefault(false)
    private fun hash(value: String?): String? = value?.lowercase()?.takeIf { it.isNotBlank() && it.any { c -> c != '0' } }
    private fun same(r: BtTaskRecord, v1: String?, v2: String?) =
        (v1 != null && r.infoHashV1Hex == v1) || (v2 != null && r.infoHashV2Hex == v2)

    private fun ensureSession(): SessionManager {
        val allowDiscovery = networkPolicy.isSatisfied() && (owner != null || resolving != null)
        session?.let { setDiscoveryEnabled(allowDiscovery); return it }
        val settings = SettingsPack().apply {
            setEnableLsd(false); setEnableDht(allowDiscovery); setMaxMetadataSize(MAX_METADATA)
            setBoolean(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.prefer_udp_trackers.swigValue(), false)
            connectionsLimit(80); downloadRateLimit(downloadLimit); uploadRateLimit(uploadLimit)
        }
        return SessionManager().also { s ->
            s.start(SessionParams(settings))
            s.addListener(object : AlertListener {
                override fun types(): IntArray = intArrayOf(AlertType.SAVE_RESUME_DATA.swig(), AlertType.SAVE_RESUME_DATA_FAILED.swig(),
                    AlertType.TORRENT_DELETED.swig(), AlertType.TORRENT_DELETE_FAILED.swig())
                override fun alert(alert: Alert<*>) {
                    // Copy borrowed JNI views before returning from the callback.
                    try {
                        if (alert is TorrentDeletedAlert) {
                            val hashes = alert.getInfoHashes()
                            val v1 = if (hashes.hasV1()) hash(hashes.getV1().toHex()) else null
                            val v2 = if (hashes.hasV2()) hash(hashes.getV2().toHex()) else null
                            dispatch { finishDelete(v1, v2) }
                            return
                        }
                        if (alert is TorrentDeleteFailedAlert) {
                            val best = alert.getInfoHash().toHex()
                            dispatch { failDelete(best) }
                            return
                        }
                        val status = (alert as TorrentAlert<*>).handle().status()
                        val v1 = hash(status.infoHashV1()?.toHex())
                        val v2 = hash(status.infoHashV2()?.toHex())
                        val bytes = if (alert is SaveResumeDataAlert)
                            AddTorrentParams.writeResumeData(alert.params()).bencode() else null
                        dispatch { onCheckpoint(v1, v2, bytes) }
                    } catch (_: Exception) { Log.w(TAG, "resume alert could not be copied") }
                }
            })

            // ---- 独立的 Tracker 监听器 ----
            //
            // 任务书第 8 节明确警告：**不能只把 Tracker 类型加进上面那个
            // 保存/删除 listener**。上面那个默认分支会把非
            // SaveResumeDataAlert 强转成 TorrentAlert 再传 bytes=null，
            // 恰逢保存时会把 Tracker 事件误报成存储失败。
            //
            // 所以这里单开一个 listener，只处理 Tracker 状态。
            s.addListener(object : AlertListener {
                override fun types(): IntArray = intArrayOf(
                    AlertType.TRACKER_ANNOUNCE.swig(),
                    AlertType.TRACKER_REPLY.swig(),
                    AlertType.TRACKER_ERROR.swig(),
                    AlertType.TRACKER_WARNING.swig(),
                )

                override fun alert(alert: Alert<*>) {
                    try {
                        // 在 JNI 回调内只复制普通值，不保存包装对象
                        val url: String
                        val peers: Int
                        val state: BtTrackerStatus.AnnounceState
                        val errorKind: BtTrackerStatus.ErrorKind?

                        when (alert) {
                            is TrackerAnnounceAlert -> {
                                url = alert.trackerUrl() ?: ""
                                peers = -1
                                state = BtTrackerStatus.AnnounceState.contacting
                                errorKind = null
                            }
                            is TrackerReplyAlert -> {
                                url = alert.trackerUrl() ?: ""
                                peers = alert.numPeers()
                                state = BtTrackerStatus.AnnounceState.responded
                                errorKind = null
                            }
                            is TrackerErrorAlert -> {
                                url = alert.trackerUrl() ?: ""
                                peers = -1
                                state = BtTrackerStatus.AnnounceState.temporarilyFailed
                                // 只取枚举，不传原始 message（可能含 passkey）
                                errorKind = BtTrackerStatus.classifyError(
                                    alert.error().value(),
                                    alert.errorMessage(),
                                )
                            }
                            is TrackerWarningAlert -> return // A warning is not an unsuccessful announce.
                            else -> return
                        }
                        if (url.isEmpty()) return

                        // 归属到这个 alert 的 torrent
                        val nativeId = (alert as TorrentAlert<*>).handle().swig().id()
                        val binding = trackerBindings[nativeId] ?: return
                        val eventNetwork = networkPolicy.epoch
                        dispatch { onTrackerAlert(binding, eventNetwork, url, state, peers, errorKind) }
                    } catch (_: Exception) {
                        Log.w(TAG, "tracker alert could not be copied")
                    }
                }
            })

            session = s
            discoveryEnabled = allowDiscovery
        }
    }

    // fetchMagnet's timeout is SECONDS. Interrupting its latch also runs its handle cleanup.
    fun resolveMagnet(magnet: String, requestId: String, timeoutMs: Int = 90000): Future<Map<String, Any>> {
        val result = CompletableFuture<Map<String, Any>>()
        dispatch {
            try {
                load()
                val parsed = BtInput.parse(magnet)
                if (parsed is BtInput.ParseResult.Fail) { result.complete(fail(parsed.code, parsed.message)); return@dispatch }
                val info = (parsed as BtInput.ParseResult.Ok).info
                autoSyncTrackers()
                val cached = metadata.values.firstOrNull {
                    (info.infoHashV1Hex != null && it.v1 == info.infoHashV1Hex) ||
                        (info.infoHashV2Hex != null && it.v2 == info.infoHashV2Hex)
                }
                if (cached != null) {
                    result.complete(if (info.matchesHashes(cached.v1, cached.v2)) payload(cached, requestId)
                        else fail("UNSUPPORTED_METADATA", "磁力标识与元数据不一致"))
                    return@dispatch
                }
                val existing = tasks.values.firstOrNull { same(it.record, info.infoHashV1Hex, info.infoHashV2Hex) }
                // A damaged private metadata blob must still allow the original
                // magnet to be resolved again; existing payload files stay intact.
                val stored = existing?.let { runCatching { store.readMetadata(it.record.taskId) }.getOrNull() }
                if (stored != null) {
                    val restored = cacheMetadata(stored, "magnet", info.magnet)
                    result.complete(if (info.matchesHashes(restored.v1, restored.v2)) payload(restored, requestId)
                        else fail("UNSUPPORTED_METADATA", "磁力标识与元数据不一致"))
                    return@dispatch
                }
                if (!networkPolicy.isSatisfied()) { result.complete(fail("WAITING_NETWORK", "当前网络不符合下载策略")); return@dispatch }
                if (resolving != null) { result.complete(fail("METADATA_BUSY", "正在获取文件列表，请稍后重试")); return@dispatch }
                val s = ensureSession()
                s.resume()
                val request = Resolve(requestId, result)
                resolving = request
                setDiscoveryEnabled(true)
                val directory = File(appContext.cacheDir, "bt-metadata").apply { mkdirs() }
                request.worker = metadataWorker.submit {
                    val bytes = runCatching { s.fetchMagnet(info.magnet,
                        ((timeoutMs.coerceIn(1000, 90000) + 999) / 1000), directory) }.getOrNull()
                    dispatch {
                        // Do not release the concurrency slot until upstream removed its temporary handle.
                        if (resolving === request) resolving = null
                        if (!request.canceled) {
                            try {
                                if (bytes == null || bytes.isEmpty()) result.complete(fail("METADATA_TIMEOUT", "暂时未取得文件列表，可重试或导入种子"))
                                else {
                                    val m = cacheMetadata(bytes, "magnet", info.magnet)
                                    check(info.matchesHashes(m.v1, m.v2))
                                    result.complete(payload(m, requestId))
                                }
                            } catch (_: Exception) { result.complete(fail("UNSUPPORTED_METADATA", "元数据无效或包含不安全路径")) }
                        }
                        releaseIfIdle()
                    }
                }
            } catch (_: Exception) { result.complete(fail("ENGINE_UNAVAILABLE", "无法获取元数据，请重试")) }
        }
        return result
    }
    fun cancelResolve(requestId: String): Future<Map<String, Any>> = post {
        resolving?.takeIf { it.id == requestId }?.let { cancelPreview(it) }
        success()
    }
    private fun cancelPreview(r: Resolve) {
        // If cancel wins before the executor starts, its body/finally never run.
        // A single worker still serializes cleanup before the next real fetch.
        if (resolving === r) resolving = null
        r.canceled = true; r.result.complete(fail("CANCELED", "操作已取消")); r.worker?.cancel(true)
    }
    fun onAppForeground() { dispatch { appForeground = true } }
    fun cancelPreviewRequests() { dispatch {
        appForeground = false; trackerService.cancelAll()
        cancelCustomTrackerValidation("CANCELED")
        resolving?.let { cancelPreview(it) }
    } }
    fun importTorrent(bytes: ByteArray, requestId: String, displayNameHint: String = ""): Future<Map<String, Any>> = post {
        autoSyncTrackers()
        try { payload(cacheMetadata(bytes, "torrent", ""), requestId) }
        catch (_: Exception) { fail("INVALID_TORRENT", "种子文件无效、过大或包含不安全路径") }
    }
    private fun cacheMetadata(bytes: ByteArray, source: String, magnet: String): Metadata {
        require(bytes.isNotEmpty() && bytes.size <= MAX_METADATA)
        val info = TorrentInfo(bytes)
        require(info.isValid && info.numFiles() in 1..20000)
        val fs = info.files()
        val paths = (0 until info.numFiles()).map { fs.filePath(it) }
        require(paths.all { BtPathGuard.sanitize(it) is BtPathGuard.Result.Ok })
        require(BtPathGuard.sanitizeAll(paths.filterIndexed { i, _ -> !fs.padFileAt(i) }) is BtPathGuard.AllResult.Ok)
        require(BtPathGuard.sanitizeAll((0 until info.numFiles()).filter { !fs.padFileAt(it) }
            .map { info.origFiles().filePath(it) }) is BtPathGuard.AllResult.Ok)
        val files = paths.mapIndexed { i, path ->
            require(!fs.fileFlags(i).and_(FileStorage.FLAG_SYMLINK).nonZero())
            require(fs.fileSize(i) >= 0)
            BtFileRecord(i, path, fs.fileSize(i), fs.padFileAt(i))
        }
        files.fold(0L) { total, file -> Math.addExact(total, file.lengthBytes) }
        val v1 = hash(info.infoHashV1()?.toHex()); val v2 = hash(info.infoHashV2()?.toHex())
        require(v1 != null || v2 != null)
        val key = v1 ?: ("v2:" + v2)
        val m = Metadata(bytes, info, files, key, v1, v2, info.name().ifBlank { "torrent" }, source, magnet)
        metadata[key] = m
        while (metadata.size > 8 || metadata.values.sumOf { it.bytes.size.toLong() } > 32L * 1024 * 1024) {
            if (metadata.size == 1) break
            metadata.remove(metadata.keys.first())
        }
        return m
    }
    private fun payload(m: Metadata, requestId: String): Map<String, Any> = mapOf(
        "ok" to true, "requestId" to requestId, "dedupeKey" to m.key, "displayName" to m.name, "sourceType" to m.source,
        "infoHashV1" to (m.v1 ?: ""), "infoHashV2" to (m.v2 ?: ""),
        "totalBytes" to m.files.filterNot { it.isPadding }.sumOf { it.lengthBytes },
        "fileCount" to m.files.count { !it.isPadding }, "pieceLength" to m.info.pieceLength(),
        "files" to m.files.filterNot { it.isPadding }.map { mapOf("fileIndex" to it.fileIndex,
            "path" to it.path, "lengthBytes" to it.lengthBytes, "isPadding" to false) })

    fun enqueue(magnet: String, selectedIndices: List<Int>, targetRoot: String,
                taskId: String, metadataKey: String? = null): Future<Map<String, Any>> = post {
        load()
        autoSyncTrackers()
        if (!taskId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return@post fail("INVALID_ARGUMENT", "任务标识无效")
        val parsed = if (magnet.isNotBlank()) BtInput.parse(magnet) else null
        if (parsed is BtInput.ParseResult.Fail) return@post fail(parsed.code, parsed.message)
        val key = metadataKey ?: (parsed as? BtInput.ParseResult.Ok)?.info?.dedupeKey
        val m = metadata[key] ?: return@post fail("METADATA_MISSING", "请先重新查看文件列表")
        if (parsed is BtInput.ParseResult.Ok && !parsed.info.matchesHashes(m.v1, m.v2))
            return@post fail("UNSUPPORTED_METADATA", "磁力标识与元数据不一致")
        val selected = selectedIndices.toSet()
        if (selected.isEmpty()) return@post fail("NO_FILES_SELECTED", "至少选择一个文件")
        if (!selected.all { index -> m.files.any { it.fileIndex == index && !it.isPadding } })
            return@post fail("INVALID_ARGUMENT", "文件选择无效")
        val existing = tasks.values.firstOrNull { same(it.record, m.v1, m.v2) }
        if (existing != null) {
            store.saveMetadata(existing.record.taskId, m.bytes)
            return@post fail("DUPLICATE_TASK", "这个内容已有任务，请查看或继续原任务") + mapOf("existingTaskId" to existing.record.taskId)
        }
        if (tasks.containsKey(taskId)) return@post fail("DUPLICATE_TASK", "任务标识已存在")
        val root = File(targetRoot)
        if (!root.isAbsolute || !root.isDirectory || !root.canWrite()) return@post fail("STORAGE_UNAVAILABLE", "保存目录不可用，请重新选择")
        val bytes = m.files.filter { it.fileIndex in selected }.sumOf { it.lengthBytes }
        if (root.usableSpace < bytes || root.usableSpace - bytes < 64L * 1024 * 1024)
            return@post fail("INSUFFICIENT_SPACE", "保存位置空间不足")
        val baseName = sanitizeDirName(m.name) + "_" + BtInput.shortId(m.key)
        var dir = File(root.canonicalFile, baseName)
        var suffix = 1
        while (Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) dir = File(root.canonicalFile, baseName + "_" + suffix++)
        if (!dir.mkdir()) return@post fail("STORAGE_UNAVAILABLE", "无法创建任务目录")
        try {
            val probe = File(dir, ".clipisle-write-probe")
            RandomAccessFile(probe, "rw").use { it.seek(4096); it.write(71); it.seek(4096); check(it.read() == 71); it.fd.sync() }
            check(probe.delete())
            File(dir, ".clipisle-owner").writeText(taskId)
            store.saveMetadata(taskId, m.bytes)
        } catch (_: Exception) { return@post fail("STORAGE_UNAVAILABLE", "保存位置无法正常写入") }
        val record = BtTaskRecord(taskId, 1, m.key, m.magnet.ifEmpty { magnet }, m.v1, m.v2,
            m.name, m.source, root.canonicalPath, dir.name, selected, System.currentTimeMillis(), 0,
            true, BtState.QUEUED, "", false, m.files.filterNot { it.isPadding }.sumOf { it.lengthBytes }, bytes, m.files)
        tasks[taskId] = Task(record, revision = ++sequence)
        try { taskPolicy(tasks.getValue(taskId), m, isNew = true); persist() } catch (e: Exception) { tasks.remove(taskId); throw e }
        requestExecution()
        if (tasks[taskId]?.record?.errorCode == "BACKGROUND_START_DENIED" || tasks[taskId]?.record?.errorCode == "BACKGROUND_RESTRICTED")
            return@post fail("BACKGROUND_START_DENIED", "系统未允许启动后台传输，请在插件中重试") + mapOf("taskId" to taskId)
        success() + mapOf("taskId" to taskId, "taskDir" to dir.absolutePath, "selectedBytes" to bytes, "fileCount" to selected.size)
    }

    private fun requestExecution() {
        if (owner != null && ownerIsRunning(owner!!)) { reconcile(); return }
        owner = null
        val t = tasks.values.firstOrNull { it.record.requestedRunning && !BtState.isTerminal(it.record.actualState) &&
            it.record.actualState != BtState.BLOCKED_STORAGE } ?: return
        pendingOwner = if (Build.VERSION.SDK_INT >= 34) BtTransferJobService.OWNER else BtForegroundService.OWNER
        val ok = if (Build.VERSION.SDK_INT >= 34) BtTransferJobService.schedule(appContext,
            (t.record.selectedBytes - t.record.downloadedBytes).coerceAtLeast(0), networkPolicy.unmeteredOnly)
        else if (!networkPolicy.isSatisfied()) false else BtForegroundService.start(appContext, t.record.taskId, t.record.displayName, "", 0, true)
        if (!ok) {
            pendingOwner = null
            for (task in tasks.values.filter { it.record.requestedRunning && !BtState.isTerminal(it.record.actualState) }) {
                change(task, task.record.copy(actualState = if (networkPolicy.isSatisfied()) BtState.INTERRUPTED else BtState.WAITING_NETWORK,
                    pauseReason = if (networkPolicy.isSatisfied()) "background_start_denied" else "network_policy",
                    errorCode = if (isBackgroundRestricted()) "BACKGROUND_RESTRICTED"
                        else if (networkPolicy.isSatisfied()) "BACKGROUND_START_DENIED" else ""))
            }
            persist()
        }
    }
    private fun ownerIsRunning(value: String): Boolean = when (value) {
        BtTransferJobService.OWNER -> BtTransferJobService.isRunning
        BtForegroundService.OWNER -> BtForegroundService.isRunning
        else -> false
    }

    private fun setDiscoveryEnabled(enabled: Boolean) {
        val current = session ?: return
        if (discoveryEnabled == enabled) return
        current.applySettings(SettingsPack().apply { setEnableDht(enabled) })
        discoveryEnabled = enabled
    }

    fun onExecutionStarted(owner: String) { dispatch {
        // A start callback can be queued while the system has already revoked that owner.
        if (!ownerIsRunning(owner)) return@dispatch
        load(); this.owner = owner; pendingOwner = null
        reconcile(); releaseIfIdle()
    } }
    fun onExecutionStopped(owner: String, reason: String) { dispatch {
        // A replacement Job may have started before this older stop callback reaches our queue.
        if (ownerIsRunning(owner)) return@dispatch
        val wasPendingFailure = pendingOwner == owner && reason == "notification_failed"
        if (this.owner != owner && !wasPendingFailure) return@dispatch
        this.owner = null
        val systemRetry = owner == BtTransferJobService.OWNER && reason.startsWith("system_stop_") &&
            reason != "system_stop_${android.app.job.JobParameters.STOP_REASON_USER}" &&
            reason != "system_stop_${android.app.job.JobParameters.STOP_REASON_CANCELLED_BY_APP}" &&
            reason != "system_stop_${android.app.job.JobParameters.STOP_REASON_BACKGROUND_RESTRICTION}"
        val backgroundRestricted = reason == "system_stop_${android.app.job.JobParameters.STOP_REASON_BACKGROUND_RESTRICTION}"
        pendingOwner = if (systemRetry) owner else null
        for (t in tasks.values.filter { BtState.isActive(it.record.actualState) || it.record.actualState == BtState.PAUSING ||
            ((wasPendingFailure || backgroundRestricted) && it.record.requestedRunning &&
                !BtState.isTerminal(it.record.actualState) && it.record.actualState != BtState.BLOCKED_STORAGE) }) {
            pauseHandle(t)
            change(t, t.record.copy(actualState = if (t.record.requestedRunning) BtState.INTERRUPTED else BtState.PAUSED,
                pauseReason = reason, errorCode = if (backgroundRestricted) "BACKGROUND_RESTRICTED"
                    else if (wasPendingFailure) "BACKGROUND_START_DENIED" else t.record.errorCode))
            checkpoint(t)
        }
        setDiscoveryEnabled(false)
        session?.pause()
        persist(); releaseIfIdle()
    } }
    private fun onNetworkChanged(satisfied: Boolean) {
        if (!satisfied) {
            resolving?.let { cancelPreview(it) }
            for (t in tasks.values.filter { it.record.requestedRunning && !BtState.isTerminal(it.record.actualState) }) {
                pauseHandle(t)
                change(t, t.record.copy(actualState = BtState.WAITING_NETWORK, pauseReason = "network_policy"))
                checkpoint(t)
            }
            setDiscoveryEnabled(false)
            session?.pause(); persist()
        } else reconcile()
    }
    private fun reconcile() {
        if (owner == null || !ownerIsRunning(owner!!) || !networkPolicy.isSatisfied()) return
        setDiscoveryEnabled(true)
        var active = 0
        for (t in tasks.values) {
            if (!t.record.requestedRunning || BtState.isTerminal(t.record.actualState) || t.record.actualState == BtState.BLOCKED_STORAGE) continue
            if (t.record.actualState == BtState.PAUSING || t.record.actualState == BtState.VERIFYING) { active++; continue }
            if (active >= maxActive) { pauseHandle(t); change(t, t.record.copy(actualState = BtState.QUEUED)); continue }
            try {
                if (t.handle == null) restoreHandle(t)
                else {
                    val policy = taskPolicy(t)
                    if (policy.pending && applyPolicy(t, policy)["ok"] != true) continue
                }
                session?.resume(); t.handle?.resume()
                if (!BtState.isActive(t.record.actualState)) change(t, t.record.copy(actualState = BtState.CHECKING, pauseReason = "", errorCode = ""))
                active++
            } catch (_: Exception) {
                pauseHandle(t)
                change(t, t.record.copy(actualState = BtState.BLOCKED_STORAGE, pauseReason = "storage_or_metadata", errorCode = "RESUME_CORRUPT"))
            }
        }
        persist()
    }
    private fun taskDirectory(r: BtTaskRecord, allowMissing: Boolean = false): File {
        require(BtPathGuard.sanitize(r.taskDirName) is BtPathGuard.Result.Ok && '/' !in r.taskDirName)
        val root = File(r.targetRoot).canonicalFile
        val dir = File(root, r.taskDirName)
        require(!Files.isSymbolicLink(dir.toPath()) && dir.canonicalFile.parentFile == root &&
            (dir.isDirectory || (allowMissing && !dir.exists())))
        return dir
    }
    private fun safeFile(r: BtTaskRecord, f: BtFileRecord, allowMissingDirectory: Boolean = false): File {
        require(BtPathGuard.sanitize(f.path) is BtPathGuard.Result.Ok)
        val dir = taskDirectory(r, allowMissingDirectory)
        require(!BtPathGuard.hasSymlinkOnPath(dir, f.path))
        var cursor = dir
        for (part in f.path.split('/')) { cursor = File(cursor, part); require(!Files.isSymbolicLink(cursor.toPath())) }
        require(cursor.canonicalPath.startsWith(dir.canonicalPath + File.separator))
        return cursor
    }
    /**
     * 计算某任务当前的有效 Tracker 列表。
     *
     * **新建与恢复都必须走这里**（任务书 7.2）——只改新建路径会导致
     * 重启后旧地址复活，或者新任务有补充而恢复的任务没有。
     */
    private fun effectiveTrackers(p: BtTrackerTaskStore.Policy): BtTrackerPolicy.Effective =
        BtTrackerPolicy.effective(p.originals, p.unowned,
            p.supplements.filterNot { BtTrackerPolicy.disabled(it.url, trackerConfig.disabledSupplementKeys) },
            p.privacy, p.enabled && trackerConfig.supplementsEnabled && !trackerConfigDamaged)

    private fun catalogRevision(): Long = trackerCatalog?.poolUpdatedAt ?: 0L
    private fun taskMetadata(t: Task): Metadata {
        val r = t.record
        val bytes = store.readMetadata(r.taskId) ?: metadata[r.dedupeKey]?.bytes
            ?: throw IllegalStateException("TRACKER_METADATA_UNKNOWN")
        return cacheMetadata(bytes, r.sourceType, r.magnet).also { require(same(r, it.v1, it.v2) && it.files == r.files) }
    }
    private fun taskPolicy(t: Task, knownMetadata: Metadata? = null, isNew: Boolean = false): BtTrackerTaskStore.Policy {
        t.trackerPolicy?.let { return reconcileRevocations(t, it) }
        val m = knownMetadata ?: taskMetadata(t)
        val r = t.record
        val existing = taskTrackerStore.load(r.taskId)
        if (existing != null) {
            require(existing.generation == r.generation && existing.contentKey == r.dedupeKey && existing.privacy == m.privacy)
            require(existing.originals == m.originals)
            effectiveTrackers(existing)
            t.trackerPolicy = existing
            if (BtState.isTerminal(r.actualState)) {
                t.trackerStates.putAll(existing.lastStates.associateBy { it.canonicalKey })
                t.trackerStatesAt = existing.lastStates.maxOfOrNull { it.observedAt } ?: 0L
            }
            return reconcileRevocations(t, existing)
        }
        val originalKeys = m.originals.map { it.canonicalKey }.toSet()
        val unowned = if (isNew) emptyList() else BtTorrentEngine.resumeTrackers(
            runCatching { store.readResume(r.taskId) }.getOrNull())
            .filter { BtTrackerPolicy.keyOf(it.url) !in originalKeys }
            .map { BtTrackerPolicy.Original(it.url, BtTrackerPolicy.keyOf(it.url), it.tier, "resume") }
        val candidates = if (isNew && m.privacy == BtTrackerPolicy.Privacy.public && trackerConfig.supplementsEnabled)
            buildSupplements(m, trackerCatalog, trackerConfig) else emptyList()
        val policy = BtTrackerTaskStore.Policy(r.taskId, r.generation, r.dedupeKey, m.privacy,
            m.originals, unowned, trackerConfig.supplementsEnabled, catalogRevision = catalogRevision(), supplements = candidates)
        effectiveTrackers(policy)
        storeTaskPolicy(t, policy)
        return policy
    }
    /** A crash after saving a global revocation must not resurrect its old task list. */
    private fun reconcileRevocations(t: Task, policy: BtTrackerTaskStore.Policy): BtTrackerTaskStore.Policy {
        if (BtState.isTerminal(t.record.actualState)) return policy
        val revoked = policy.revoked(trackerConfig.supplementsEnabled && !trackerConfigDamaged,
            trackerConfig.disabledSupplementKeys)
        if (revoked != policy) storeTaskPolicy(t, revoked)
        return revoked
    }
    private fun storeTaskPolicy(t: Task, policy: BtTrackerTaskStore.Policy) {
        taskTrackerStore.save(policy)
        t.trackerPolicy = policy
    }
    private fun entries(e: BtTrackerPolicy.Effective) = e.urls.zip(e.tiers).map { TrackerEntry(it.first, it.second) }
    private fun signature(entries: List<TrackerEntry>): List<String> = entries
        .map { "${it.tier}:${BtTrackerPolicy.idForUrl(it.url)}" }.sorted()
    private fun trackerPolicyFailure(t: Task) {
        pauseHandle(t)
        change(t, t.record.copy(actualState = BtState.BLOCKED_STORAGE,
            pauseReason = "tracker_policy", errorCode = "TRACKER_APPLY_PENDING"))
        runCatching { persist() }
        releaseIfIdle()
    }
    private fun applyPolicy(t: Task, desired: BtTrackerTaskStore.Policy, userChange: Boolean = false): Map<String, Any> {
        val pending = desired.copy(pending = true)
        try {
            storeTaskPolicy(t, pending)
            val wanted = entries(effectiveTrackers(pending))
            val handle = t.handle
            if (handle != null) {
                val current = BtTorrentEngine.currentTrackers(handle)
                if (signature(current) != signature(wanted)) {
                    if (current.all { it in wanted }) {
                        wanted.filter { it !in current }.forEach { check(BtTorrentEngine.addTracker(handle, it.url, it.tier)) }
                    } else check(BtTorrentEngine.replaceTrackers(handle, wanted))
                    check(signature(BtTorrentEngine.currentTrackers(handle)) == signature(wanted))
                }
            }
            storeTaskPolicy(t, pending.copy(pending = false,
                lastAppliedAt = if (userChange) System.currentTimeMillis() else pending.lastAppliedAt))
            t.trackerStates.keys.retainAll(wanted.map { BtTrackerPolicy.idForUrl(it.url) }.toSet())
            if (handle != null) checkpoint(t)
            return success() + mapOf("status" to if (handle == null) "saved" else "applied", "pending" to false)
        } catch (_: Exception) {
            trackerPolicyFailure(t)
            return fail("TRACKER_APPLY_PENDING", "Tracker 配置尚未应用，任务已暂停") + mapOf("pending" to true)
        }
    }
    private fun revokeSupplements(): Boolean {
        load()
        var complete = true
        tasks.values.filter { !BtState.isTerminal(it.record.actualState) }.forEach { t ->
            try {
                val current = taskPolicy(t)
                val kept = if (trackerConfig.supplementsEnabled) current.supplements
                    .filterNot { BtTrackerPolicy.disabled(it.url, trackerConfig.disabledSupplementKeys) } else emptyList()
                if (kept != current.supplements || current.pending) {
                    if (applyPolicy(t, current.copy(supplements = kept, revision = current.revision + 1,
                        lastAppliedAt = 0))["ok"] != true) complete = false
                }
            } catch (_: Exception) { complete = false; trackerPolicyFailure(t) }
        }
        return complete
    }

    /** 从目录池 + 用户公共地址构造候选。 */
    private fun buildSupplements(
        m: Metadata,
        catalog: BtTrackerStore.Catalog?,
        config: BtTrackerStore.Config,
    ): List<BtTrackerPolicy.Supplement> {
        val originalKeys = m.originals.map { it.canonicalKey }.toSet()

        // 用户公共地址
        val userPublic = (if (config.customDnsValidated) config.customPublicTrackers else emptyList()).mapNotNull { raw ->
            when (val p = BtTrackerUrl.parse(raw)) {
                is BtTrackerUrl.Result.Ok -> BtTrackerPolicy.Supplement(
                    url = p.value.raw,
                    canonicalKey = p.value.canonicalKey,
                    sourceIds = listOf("custom"),
                )
                is BtTrackerUrl.Result.Reject -> null
            }
        }

        // 订阅来源：按启用顺序。
        //
        // 黑名单源（kind=blacklist）不参与候选，只用于排除，见下方 blocked。
        val bySource = LinkedHashMap<String, List<BtTrackerPolicy.Supplement>>()
        for (src in config.sources) {
            if (!src.enabled) continue
            if (src.isBlacklist) continue          // 黑名单不是候选
            val cache = catalog?.sources?.get(src.id) ?: continue
            if (!BtTrackerStore.usable(src, cache)) continue
            if (cache.urls.isEmpty()) continue
            bySource[src.id] = cache.urls.mapNotNull { raw ->
                when (val p = BtTrackerUrl.parse(raw)) {
                    is BtTrackerUrl.Result.Ok -> BtTrackerPolicy.Supplement(
                        url = p.value.raw,
                        canonicalKey = p.value.canonicalKey,
                        sourceIds = listOf(src.id),
                    )
                    is BtTrackerUrl.Result.Reject -> null
                }
            }
        }

        // ---- 黑名单 ----
        //
        // 上游维护者维护的劣质地址清单。与「用户禁用地址」语义不同：
        //  - 禁用是用户对**某个补充项**的排除
        //  - 黑名单是全局质量信号，不覆盖原始种子自带的地址
        // 所以黑名单只作用于补充候选，不影响 originals。
        val blocked = if (config.blacklistEnabled) {
            val keys = HashSet<String>()
            for (src in config.sources) {
                if (!src.isBlacklist || !src.enabled) continue
                val cache = catalog?.sources?.get(src.id) ?: continue
                if (!BtTrackerStore.usable(src, cache)) continue
                for (raw in cache.urls) {
                    val p = BtTrackerUrl.parse(raw)
                    if (p is BtTrackerUrl.Result.Ok) keys.add(p.value.canonicalKey)
                }
            }
            keys
        } else {
            emptySet()
        }

        // ---- 排序 ----
        //
        // 按健康度排：可达的、延迟低的、上轮用过的优先。
        // 这不是门禁——不可达的仍然留在池里，只是排在后面。
        val routeKey = networkPolicy.routeKey()
        val rankedUser = BtTrackerRanker.rank(
            userPublic.map { it.url }, trackerHealth, routeKey, previousSupplementUrls,
        )
        val orderedUser = rankedUser.mapNotNull { url -> userPublic.firstOrNull { it.url == url } }

        val orderedBySource = LinkedHashMap<String, List<BtTrackerPolicy.Supplement>>()
        for ((id, list) in bySource) {
            val ranked = BtTrackerRanker.rank(
                list.map { it.url }, trackerHealth, routeKey, previousSupplementUrls,
            )
            orderedBySource[id] = ranked.mapNotNull { url -> list.firstOrNull { it.url == url } }
        }

        // 排除：用户禁用 + 黑名单 + 连续失败进冷却的
        val excludedKeys = (orderedUser + orderedBySource.values.flatten())
            .filter {
                BtTrackerPolicy.disabled(it.url, config.disabledSupplementKeys) ||
                    it.canonicalKey in blocked ||
                    trackerHealth[it.url]?.let { h ->
                        h.routeKey == routeKey && h.isFresh && h.inCooldown
                    } == true
            }
            .map { it.canonicalKey }
            .toSet()

        return BtTrackerPolicy.pickSupplements(
            userPublic = orderedUser,
            bySource = orderedBySource,
            originalKeys = originalKeys,
            excludedKeys = excludedKeys,
            limit = config.maxSupplements.coerceIn(5, 50),
        )
    }

    /**
     * 处理一条 Tracker alert（已在 Runtime 串行队列上）。
     *
     * 状态按 `taskId + trackerKey` 记录，且每次 handle 重建更换代次，
     * 迟到事件不得更新新的 handle（任务书第 8 节）。
     */
    private fun onTrackerAlert(binding: TrackerBinding, networkEpoch: Long, url: String,
        state: BtTrackerStatus.AnnounceState, peers: Int, errorKind: BtTrackerStatus.ErrorKind?) {
        val t = tasks[binding.taskId] ?: return
        if (t.record.generation != binding.generation || t.handleGeneration != binding.handleGeneration ||
            networkEpoch != trackerNetworkEpoch || t.handle == null || BtState.isTerminal(t.record.actualState)) return
        val policy = t.trackerPolicy ?: return
        if (effectiveTrackers(policy).urls.none { BtTrackerPolicy.keyOf(it) == BtTrackerPolicy.keyOf(url) }) return
        val key = BtTrackerPolicy.idForUrl(url)
        val previous = t.trackerStates[key]
        if (state != BtTrackerStatus.AnnounceState.contacting && previous?.state != BtTrackerStatus.AnnounceState.contacting) return
        t.trackerStates[key] = BtTrackerStatus.Entry(key, displayFor(url), protocolOf(url),
            if (policy.originals.any { it.canonicalKey == BtTrackerPolicy.keyOf(url) }) "original" else "supplement",
            "", state, if (peers >= 0) peers else previous?.lastPeerCount ?: -1,
            errorKind, System.currentTimeMillis())
        t.trackerStatesAt = System.currentTimeMillis()
    }
    private fun displayFor(url: String): String = runCatching {
        val uri = java.net.URI(url)
        val host = uri.host ?: return@runCatching "Tracker"
        host + if (uri.port >= 0) ":${uri.port}" else ""
    }.getOrDefault("Tracker")
    private fun protocolOf(url: String): String = runCatching {
        java.net.URI(url).scheme?.uppercase()?.takeIf { it in setOf("UDP", "HTTP", "HTTPS") } ?: ""
    }.getOrDefault("")

    // ==================== Tracker ====================

    /** 读取全局 Tracker 配置摘要（不含完整自定义 URL）。 */
    fun getTrackerConfig(): Future<Map<String, Any>> = post {
        val cat = trackerCatalog
        val now = System.currentTimeMillis()
        mapOf(
            "ok" to true,
            "revision" to trackerConfig.revision,
            "catalogRevision" to catalogRevision(),
            "supplementsEnabled" to trackerConfig.supplementsEnabled,
            "autoUpdateEnabled" to trackerConfig.autoUpdateEnabled,
            "damaged" to trackerConfigDamaged,
            "candidateCount" to catalogRows(false).count { it["disabled"] != true },
            "lastSuccessfulCheckAt" to (
                cat?.sources?.values?.maxOfOrNull { it.lastSuccessfulCheckAt } ?: 0L
                ),
            "sourceCount" to trackerConfig.sources.count { it.enabled },
            "customCount" to trackerConfig.customPublicTrackers.size,
            "disabledCount" to trackerConfig.disabledSupplementKeys.size,
            "blacklistEnabled" to trackerConfig.blacklistEnabled,
            "maxSupplements" to trackerConfig.maxSupplements,
            "syncIntervalHours" to trackerConfig.syncIntervalHours,
            "lastAutoSyncAt" to trackerConfig.lastAutoSyncAt,
            "nextAutoSyncAt" to (
                if (trackerConfig.lastAutoSyncAt <= 0) 0L
                else trackerConfig.lastAutoSyncAt + trackerConfig.syncIntervalHours * 60L * 60_000L
                ),
            "blacklistCount" to trackerConfig.sources
                .filter { it.isBlacklist && it.enabled }
                .sumOf { cat?.sources?.get(it.id)?.urls?.size ?: 0 },
            "healthyCount" to trackerHealth.count { it.value.status == "reachable" || it.value.status == "slow" },
            "unreachableCount" to trackerHealth.count { it.value.status == "unreachable" },
            "sources" to trackerConfig.sources.map { s ->
                val cache = cat?.sources?.get(s.id)
                mapOf(
                    "id" to s.id,
                    "name" to s.name,
                    "enabled" to s.enabled,
                    "builtin" to (s.id in setOf("xiu2_best", "ngosang_best", "xiu2_black")),
                    "kind" to s.kind,
                    "expiresAt" to (cache?.expiresAt ?: 0L),
                    "count" to (cache?.urls?.size ?: 0),
                    "lastSuccessfulCheckAt" to (cache?.lastSuccessfulCheckAt ?: 0L),
                    "lastError" to (cache?.lastError ?: ""),
                    "consecutiveFailures" to (cache?.consecutiveFailures ?: 0),
                    "nextAttemptAt" to (cache?.nextAttemptAt ?: 0L),
                )
            },
            "now" to now,
        )
    }

    private fun commitTrackerConfig(next: BtTrackerStore.Config): Map<String, Any> {
        cancelCustomTrackerValidation("TRACKER_CONFIG_CHANGED")
        // Finish obligations from the previous durable config before lifting any restriction.
        // Otherwise off -> process death -> on would revive a stale applied list.
        val revoked = revokeSupplements()
        val liftsRestriction = !trackerConfig.supplementsEnabled && next.supplementsEnabled ||
            trackerConfig.disabledSupplementKeys.any { it !in next.disabledSupplementKeys }
        if (!revoked && liftsRestriction) return trackerConfigPending(saved = false)
        trackerStore.saveConfig(next)
        trackerConfig = next
        trackerConfigDamaged = false
        trackerService.invalidate()
        pendingPlans.clear()
        return if (revokeSupplements()) success() + mapOf("revision" to trackerConfig.revision, "pending" to false)
            else trackerConfigPending(saved = true)
    }
    private fun trackerConfigPending(saved: Boolean): Map<String, Any> =
        fail("TRACKER_APPLY_PENDING", "部分任务的 Tracker 配置尚未应用，受影响任务已暂停") +
            mapOf("revision" to trackerConfig.revision, "pending" to true, "saved" to saved, "status" to "pending")
    fun updateTrackerConfig(patch: Map<String, Any?>, expectedRevision: Int?): Future<Map<String, Any>> = post {
        if (expectedRevision != null && expectedRevision != trackerConfig.revision)
            return@post fail("TRACKER_CONFIG_CHANGED", "配置已被修改")
        var next = trackerConfig
        (patch["supplementsEnabled"] as? Boolean)?.let { next = next.copy(supplementsEnabled = it) }
        (patch["autoUpdateEnabled"] as? Boolean)?.let { next = next.copy(autoUpdateEnabled = it) }
        (patch["blacklistEnabled"] as? Boolean)?.let { next = next.copy(blacklistEnabled = it) }
        (patch["syncIntervalHours"] as? Number)?.let {
            next = next.copy(syncIntervalHours = it.toInt().coerceIn(1, 168))
        }
        (patch["maxSupplements"] as? Number)?.let {
            // 任务书原值 20；这里允许 5–50，超出钳制而不是报错
            next = next.copy(maxSupplements = it.toInt().coerceIn(5, 50))
        }
        (patch["sourceEnabled"] as? Map<*, *>)?.let { value ->
            val id = value["id"] as? String ?: throw IllegalArgumentException()
            val enabled = value["enabled"] as? Boolean ?: throw IllegalArgumentException()
            require(next.sources.any { it.id == id })
            next = next.copy(sources = next.sources.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
        (patch["disabledSupplementKeys"] as? List<*>)?.let { value ->
            require(value.size <= 1000 && value.all { it is String && it.matches(Regex("[0-9a-f]{64}")) })
            next = next.copy(disabledSupplementKeys = value.filterIsInstance<String>().distinct())
        }
        (patch["disableSupplement"] as? Map<*, *>)?.let { value ->
            val key = value["key"] as? String ?: throw IllegalArgumentException()
            val disabled = value["disabled"] as? Boolean ?: throw IllegalArgumentException()
            require(key.matches(Regex("[0-9a-f]{64}")))
            next = next.copy(disabledSupplementKeys = if (disabled) (next.disabledSupplementKeys + key).distinct()
                else next.disabledSupplementKeys.filterNot { it == key })
        }
        commitTrackerConfig(next.copy(revision = next.revision + 1))
    }
    fun getCustomTrackers(): Future<Map<String, Any>> = post {
        success() + mapOf("revision" to trackerConfig.revision, "urls" to trackerConfig.customPublicTrackers,
            "dnsValidated" to trackerConfig.customDnsValidated)
    }
    fun saveCustomTrackers(urls: List<String>, expectedRevision: Int?, validateOnly: Boolean = false): Future<Map<String, Any>> {
        val completion = CompletableFuture<Map<String, Any>>()
        dispatch {
            try {
                if (expectedRevision != null && expectedRevision != trackerConfig.revision) {
                    completion.complete(fail("TRACKER_CONFIG_CHANGED", "配置已被修改")); return@dispatch
                }
                val input = BtTrackerPolicy.parseCustomInput(urls)
                val valid = input.urls; val inputLines = input.inputLines; val duplicates = input.duplicateCount
                val invalid = input.invalidLines.map { mapOf("line" to it, "reason" to "TRACKER_SOURCE_INVALID") }
                if (valid.size > 20) { completion.complete(fail("TRACKER_CUSTOM_LIMIT", "自定义地址最多20个")); return@dispatch }
                if (invalid.isNotEmpty()) {
                    completion.complete(fail("TRACKER_SOURCE_INVALID", "部分地址无效") + mapOf("validCount" to valid.size,
                        "duplicateCount" to duplicates, "invalidCount" to invalid.size, "invalid" to invalid)); return@dispatch
                }
                if (customTrackerValidation != null) {
                    completion.complete(fail("BUSY", "正在检查公共地址，请稍后重试")); return@dispatch
                }
                if (valid.isEmpty()) {
                    val committed = if (validateOnly) success() else commitTrackerConfig(trackerConfig.copy(
                        customPublicTrackers = emptyList(), customDnsValidated = true, revision = trackerConfig.revision + 1))
                    completion.complete(committed + mapOf("revision" to trackerConfig.revision, "validCount" to 0,
                        "duplicateCount" to duplicates, "invalidCount" to 0, "invalid" to emptyList<Any>(),
                        "count" to 0, "validateOnly" to validateOnly)); return@dispatch
                }
                if (valid.isNotEmpty() && (!appForeground || !networkPolicy.isSatisfied())) {
                    completion.complete(fail("WAITING_NETWORK", "当前网络不符合下载策略")); return@dispatch
                }
                val request = CustomTrackerValidation(trackerConfig.revision, trackerNetworkEpoch, valid,
                    inputLines, duplicates, validateOnly, trackerClient.validatePublicTrackers(valid, 8000), completion)
                customTrackerValidation = request
                pollCustomTrackerValidation(request)
            } catch (_: Exception) { completion.complete(fail("TRACKER_SOURCE_INVALID", "公共地址校验失败")) }
        }
        return completion
    }
    private fun cancelCustomTrackerValidation(code: String) {
        val request = customTrackerValidation ?: return
        customTrackerValidation = null
        request.worker.cancel(true)
        request.result.complete(fail(code, "地址校验已取消，请重新检查"))
    }
    private fun pollCustomTrackerValidation(request: CustomTrackerValidation) {
        if (customTrackerValidation !== request) return
        if (!request.worker.isDone) {
            queue.schedule({ pollCustomTrackerValidation(request) }, 50, TimeUnit.MILLISECONDS)
            return
        }
        customTrackerValidation = null
        try {
            if (request.revision != trackerConfig.revision) {
                request.result.complete(fail("TRACKER_CONFIG_CHANGED", "配置已被修改")); return
            }
            if (request.urls.isNotEmpty() && (request.networkEpoch != trackerNetworkEpoch ||
                !appForeground || !networkPolicy.isSatisfied())) {
                request.result.complete(fail("WAITING_NETWORK", "网络已变化，请重新检查")); return
            }
            val checked = request.worker.get() // isDone: never blocks Runtime.
            val invalid = checked.rejected.map { item -> mapOf<String, Any>(
                "line" to request.inputLines[item.index], "reason" to item.reason) }
            val summary = mapOf("validCount" to checked.accepted.size, "duplicateCount" to request.duplicates,
                "invalidCount" to invalid.size, "invalid" to invalid)
            if (invalid.isNotEmpty() || checked.accepted != request.urls) {
                request.result.complete(fail("TRACKER_SOURCE_INVALID", "公共地址无法验证，请检查无效行") + summary); return
            }
            if (!request.validateOnly) {
                val committed = commitTrackerConfig(trackerConfig.copy(customPublicTrackers = request.urls,
                    customDnsValidated = true, revision = trackerConfig.revision + 1))
                if (committed["ok"] != true) { request.result.complete(committed + summary); return }
            }
            request.result.complete(success() + summary + mapOf("revision" to trackerConfig.revision,
                "count" to request.urls.size, "validateOnly" to request.validateOnly))
        } catch (_: Exception) { request.result.complete(fail("TRACKER_SOURCE_INVALID", "公共地址校验失败")) }
    }
    fun getTrackerSource(id: String): Future<Map<String, Any>> = post {
        val source = trackerConfig.sources.firstOrNull { it.id == id } ?: return@post fail("TRACKER_SOURCE_INVALID", "来源不存在")
        val defaults = BtTrackerStore.Config.defaults().sources
        success() + mapOf("revision" to trackerConfig.revision, "id" to source.id, "name" to source.name,
            "enabled" to source.enabled, "endpoints" to source.customEndpoints.ifEmpty { source.endpoints },
            "builtin" to defaults.any { it.id == id }, "projectUrl" to when(id) {
                "xiu2_best" -> "https://github.com/XIU2/TrackersListCollection"
                "ngosang_best" -> "https://github.com/ngosang/trackerslist"
                else -> ""
            })
    }
    fun saveTrackerSource(id: String?, name: String, endpoints: List<String>, enabled: Boolean,
        expectedRevision: Int?, delete: Boolean = false): Future<Map<String, Any>> = post {
        if (expectedRevision != null && expectedRevision != trackerConfig.revision)
            return@post fail("TRACKER_CONFIG_CHANGED", "配置已被修改")
        val actualId = id?.takeIf { it.isNotBlank() } ?: "custom_" + UUID.randomUUID().toString()
        val previous = trackerConfig.sources.firstOrNull { it.id == actualId }
        val committed = if (delete) {
            require(previous != null)
            commitTrackerConfig(trackerConfig.copy(sources = trackerConfig.sources.filterNot { it.id == actualId }, revision = trackerConfig.revision + 1))
        } else {
            if (name.isBlank() || name.length > 80 || endpoints.size !in 1..2 || endpoints.any {
                BtTrackerUrl.validateSubscriptionUrl(it) !is BtTrackerUrl.Result.Ok
            }) return@post fail("TRACKER_SOURCE_INVALID", "来源名称或 HTTPS 地址无效")
            val builtin = BtTrackerStore.Config.defaults().sources.map { it.id }.toSet()
            if (previous == null && actualId !in builtin && trackerConfig.sources.count { it.id !in builtin } >= 3)
                return@post fail("TRACKER_SOURCE_LIMIT", "最多3个自定义来源")
            val source = BtTrackerStore.SourceConfig(actualId, name.trim(), enabled, endpoints.map { it.trim() })
            val sources = if (previous == null) trackerConfig.sources + source else trackerConfig.sources.map { if (it.id == actualId) source else it }
            commitTrackerConfig(trackerConfig.copy(sources = sources, revision = trackerConfig.revision + 1))
        }
        if (committed["ok"] != true) return@post committed + mapOf("id" to actualId)
        val changedEndpoints = previous?.effectiveEndpoints() != endpoints.map { it.trim() }
        if (delete || changedEndpoints) trackerCatalog?.let { catalog ->
            val next = catalog.copy(sources = catalog.sources - actualId, poolUpdatedAt = nextCatalogRevision())
            trackerStore.saveCatalog(next)
            trackerCatalog = next
        }
        success() + mapOf("revision" to trackerConfig.revision, "id" to actualId)
    }
    fun restoreRecommendedSources(expectedRevision: Int?): Future<Map<String, Any>> = post {
        if (expectedRevision != null && expectedRevision != trackerConfig.revision) return@post fail("TRACKER_CONFIG_CHANGED", "配置已被修改")
        val defaults = BtTrackerStore.Config.defaults().sources
        val keys = defaults.map { it.id }.toSet()
        commitTrackerConfig(trackerConfig.copy(sources = defaults + trackerConfig.sources.filterNot { it.id in keys }, revision = trackerConfig.revision + 1))
    }
    private fun nextCatalogRevision(): Long = maxOf(System.currentTimeMillis(), catalogRevision() + 1)
    private fun autoSyncTrackers() {
        if (!appForeground || trackerConfigDamaged || !trackerConfig.supplementsEnabled ||
            !trackerConfig.autoUpdateEnabled || !networkPolicy.isSatisfied()) return

        // 间隔判断。
        //
        // 之前漏了这一步：只要有前台事件就同步，等于把用户设的
        // 「每 N 小时」变成「每次回来都拉一遍」。既浪费流量，
        // 也让上游看到远超必要的请求。
        val intervalMs = trackerConfig.syncIntervalHours.coerceIn(1, 168) * 60L * 60_000L
        val since = System.currentTimeMillis() - trackerConfig.lastAutoSyncAt
        if (trackerConfig.lastAutoSyncAt > 0 && since < intervalMs) return

        runCatching {
            trackerService.sync(trackerConfig, trackerCatalog, false) { updated ->
                trackerCatalog = updated
                probeCatalogAsync(updated)
                // 记录本次自动同步时间，避免短时间内重复拉动。
                //
                // 不走 commitTrackerConfig：那条路径会触发撤销协调与
                // revision 递增，而这里只是记一个时间戳，不该改变
                // 用户可见的配置版本。
                runCatching {
                    val stamped = trackerConfig.copy(lastAutoSyncAt = System.currentTimeMillis())
                    trackerStore.saveConfig(stamped)
                    trackerConfig = stamped
                }
            }
        }
    }

    /** 同步订阅。返回 operationId，实际请求在 I/O 线程池。 */
    fun syncTrackers(manual: Boolean): Future<Map<String, Any>> = post {
        if (!appForeground || !networkPolicy.isSatisfied()) {
            return@post fail("WAITING_NETWORK", "当前网络不符合下载策略")
        }
        trackerService.sync(trackerConfig, trackerCatalog, manual) { updated ->
            trackerCatalog = updated
            probeCatalogAsync(updated)
        }
    }

    /**
     * 异步探测目录里的地址健康度。
     *
     * 探测在独立线程池跑，**不阻塞 Runtime 串行队列**；
     * 结果回到队列再写入 [trackerHealth]。
     *
     * 只探测候选源，不探测黑名单（黑名单不需要健康度，它只用于排除）。
     */
    private fun probeCatalogAsync(catalog: BtTrackerStore.Catalog) {
        val urls = ArrayList<String>()
        for (src in trackerConfig.sources) {
            if (!src.enabled || src.isBlacklist) continue
            val cache = catalog.sources[src.id] ?: continue
            if (!BtTrackerStore.usable(src, cache)) continue
            urls.addAll(cache.urls)
        }
        if (urls.isEmpty()) return

        // 一次最多探 40 个：手机不需要把上千个全部测一遍，
        // 候选池本来就只取 20 个。
        val sample = urls.distinct().take(40)
        val routeKey = networkPolicy.routeKey()
        val snapshot = HashMap(trackerHealth)

        probeFuture = trackerProber.probe(sample, routeKey, previous = snapshot).also { future ->
            Thread({
                val results = try {
                    future.get(90, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                    emptyList()
                }
                if (results.isEmpty()) return@Thread
                dispatch {
                    for (h in results) trackerHealth[h.key] = h
                    // 只保留当前网络代次的结论，避免无限增长
                    trackerHealth.entries.removeAll {
                        it.value.routeKey != networkPolicy.routeKey()
                    }
                    Log.i(TAG, "probed ${results.size} trackers, " +
                        "reachable=${results.count { it.status == "reachable" || it.status == "slow" }}")
                    refreshTaskPoliciesAfterProbe()
                }
            }, "bt-probe-wait").apply { isDaemon = true }.start()
        }
    }

    /** 探测结果变化后，重算「还没下载完、且用户没手动锁过」的任务策略。 */
    private fun refreshTaskPoliciesAfterProbe() {
        for (t in tasks.values) {
            if (BtState.isTerminal(t.record.actualState)) continue
            if (t.record.actualState == BtState.COMPLETED) continue
            val policy = t.trackerPolicy ?: continue
            // 用户已应用过补充项的任务不自动改，避免刷新列表时抖动
            if (policy.supplements.isNotEmpty() && !policy.pending) continue
            runCatching {
                val desired = reconcileRevocations(t, policy)
                applyPolicy(t, desired)
            }
        }
    }

    private var probeFuture: Future<*>? = null

    fun getTrackerSyncState(operationId: String): Future<Map<String, Any>> =
        post { trackerService.syncState(operationId) }

    fun cancelTrackerSync(operationId: String): Future<Map<String, Any>> = post {
        trackerService.cancel(operationId)
        success()
    }

    private fun catalogRows(disabledOnly: Boolean): List<Map<String, Any>> {
        val rows = linkedMapOf<String, MutableMap<String, Any>>()
        fun add(url: String, source: String) {
            val key = BtTrackerPolicy.idForUrl(url)
            val disabled = BtTrackerPolicy.disabled(url, trackerConfig.disabledSupplementKeys)
            if (disabledOnly && !disabled) return
            val row = rows.getOrPut(key) { mutableMapOf("canonicalKey" to key, "displayHost" to displayFor(url),
                "protocol" to protocolOf(url), "sourceIds" to arrayListOf<String>(), "disabled" to disabled) }
            @Suppress("UNCHECKED_CAST") val ids = row["sourceIds"] as ArrayList<String>
            if (source !in ids) ids.add(source)
        }
        if (trackerConfig.customDnsValidated) trackerConfig.customPublicTrackers.forEach { add(it, "custom") }
        trackerConfig.sources.filter { it.enabled }.forEach { source ->
            trackerCatalog?.sources?.get(source.id)?.takeIf { BtTrackerStore.usable(source, it) }?.urls?.forEach { add(it, source.id) }
        }
        if (disabledOnly) trackerConfig.disabledSupplementKeys.forEach { key ->
            rows.putIfAbsent(key, mutableMapOf("canonicalKey" to key, "displayHost" to "Tracker ${key.take(8)}",
                "protocol" to "", "sourceIds" to emptyList<String>(), "disabled" to true))
        }
        return rows.values.toList()
    }
    fun getTrackerCatalog(cursor: Int, pageSize: Int, disabledOnly: Boolean = false): Future<Map<String, Any>> = post {
        val all = catalogRows(disabledOnly)
        val from = cursor.coerceIn(0, all.size); val to = (from + pageSize.coerceIn(1, 50)).coerceAtMost(all.size)
        success() + mapOf("revision" to trackerConfig.revision, "catalogRevision" to catalogRevision(),
            "entries" to all.subList(from, to), "total" to all.size, "nextCursor" to if (to < all.size) to else -1)
    }

    fun getTaskTrackers(taskId: String, taskGeneration: Int? = null, cursor: Int = 0,
        pageSize: Int = 50): Future<Map<String, Any>> = post {
        load()
        val t = tasks[taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        if (taskGeneration != null && taskGeneration != t.record.generation) return@post fail("TRACKER_PLAN_STALE", "任务已变化")
        val policy = try { taskPolicy(t) } catch (_: Exception) {
            if (!BtState.isTerminal(t.record.actualState)) trackerPolicyFailure(t)
            return@post fail("TRACKER_METADATA_UNKNOWN", "任务配置需要恢复，已保留文件")
        }
        val effective = if (BtState.isTerminal(t.record.actualState)) BtTrackerPolicy.effective(
            policy.originals, policy.unowned, policy.supplements, policy.privacy, policy.enabled)
            else effectiveTrackers(policy)
        val original = policy.originals.map { BtTrackerPolicy.idForUrl(it.url) }.toSet()
        val all = effective.urls.mapIndexed { index, url ->
            val key = BtTrackerPolicy.idForUrl(url)
            val status = t.trackerStates[key]
            mapOf<String, Any>("canonicalKey" to key, "displayHost" to displayFor(url), "protocol" to protocolOf(url),
                "origin" to if (key in original) "original" else if (policy.unowned.any { it.url == url }) "unowned" else "supplement",
                "sourceIds" to (effective.usedSupplements.firstOrNull { it.url == url }?.sourceIds ?: emptyList<String>()),
                "tier" to effective.tiers[index], "state" to (status?.state?.name ?: "notAttempted"),
                "lastPeerCount" to (status?.lastPeerCount ?: -1), "errorKind" to (status?.errorKind?.name ?: ""),
                "observedAt" to (status?.observedAt ?: 0L))
        }
        val from = cursor.coerceIn(0, all.size); val to = (from + pageSize.coerceIn(1, 50)).coerceAtMost(all.size)
        success() + mapOf("taskId" to taskId, "taskGeneration" to t.record.generation,
            "runtimeEpoch" to epoch, "handleGeneration" to t.handleGeneration, "networkEpoch" to trackerNetworkEpoch,
            "privacy" to policy.privacy.name, "supplementEnabled" to policy.enabled,
            "policyRevision" to policy.revision, "catalogRevision" to catalogRevision(),
            "appliedCatalogRevision" to policy.catalogRevision, "pending" to policy.pending,
            "terminal" to BtState.isTerminal(t.record.actualState), "originalCount" to original.size,
            "supplementCount" to effective.usedSupplements.size, "totalCount" to all.size,
            "connectedPeers" to if (BtState.isActive(t.record.actualState)) t.peers else 0,
            "respondedCount" to all.count { it["state"] == "responded" }, "observedAt" to t.trackerStatesAt,
            "entries" to all.subList(from, to), "nextCursor" to if (to < all.size) to else -1,
            "skipReason" to (effective.skipReason ?: ""))
    }

    fun previewTaskTrackerUpdate(taskId: String, taskGeneration: Int? = null, catalogRevision: Long? = null,
        desiredMode: String = "latest"): Future<Map<String, Any>> = post {
        load()
        val t = tasks[taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        if (BtState.isTerminal(t.record.actualState) || t.record.actualState == BtState.CANCELING ||
            taskGeneration != null && taskGeneration != t.record.generation ||
            catalogRevision != null && catalogRevision != catalogRevision()) return@post fail("TRACKER_PLAN_STALE", "任务或列表已变化")
        require(desiredMode in setOf("latest", "enabled", "disabled"))
        val m = taskMetadata(t)
        if (m.privacy != BtTrackerPolicy.Privacy.public) return@post fail(
            if (m.privacy == BtTrackerPolicy.Privacy.private) "TRACKER_PRIVATE_PROTECTED" else "TRACKER_METADATA_UNKNOWN", "仅公开种子可补充")
        val policy = taskPolicy(t, m)
        val enabled = when (desiredMode) { "enabled" -> true; "disabled" -> false; else -> policy.enabled }
        val desired = policy.copy(enabled = enabled, revision = policy.revision + 1,
            catalogRevision = catalogRevision(), supplements = if (enabled && trackerConfig.supplementsEnabled)
                buildSupplements(m, trackerCatalog, trackerConfig) else emptyList())
        val current = t.handle?.let { BtTorrentEngine.currentTrackers(it) } ?: entries(effectiveTrackers(policy))
        val wanted = entries(effectiveTrackers(desired))
        val changed = policy.enabled != desired.enabled || signature(current) != signature(wanted) ||
            policy.catalogRevision != desired.catalogRevision
        val token = UUID.randomUUID().toString()
        pendingPlans[token] = PendingPlan(taskId, planVersion(t, policy, current), desired, System.currentTimeMillis() + 120000)
        while (pendingPlans.size > 10) pendingPlans.remove(pendingPlans.keys.first())
        success() + mapOf("planToken" to token, "added" to wanted.count { it !in current },
            "removed" to current.count { it !in wanted }, "keptOriginal" to policy.originals.size, "policyChanged" to changed)
    }

    fun applyTaskTrackerUpdate(planToken: String): Future<Map<String, Any>> = post {
        val plan = pendingPlans.remove(planToken) ?: return@post fail("TRACKER_PLAN_STALE", "请重新预览")
        val t = tasks[plan.taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        val policy = taskPolicy(t)
        val current = t.handle?.let { BtTorrentEngine.currentTrackers(it) } ?: entries(effectiveTrackers(policy))
        if (!BtTrackerPolicy.canApplyPlan(plan.version, planVersion(t, policy, current), plan.expiresAt,
            System.currentTimeMillis(), BtState.isTerminal(t.record.actualState) || t.record.actualState == BtState.CANCELING)) {
            return@post fail("TRACKER_PLAN_STALE", "任务或列表已变化，请重新预览")
        }
        if (plan.desired.enabled && signature(current) != signature(entries(effectiveTrackers(plan.desired))) &&
            System.currentTimeMillis() - policy.lastAppliedAt < 300000) return@post fail("TRACKER_COOLDOWN", "请稍后再应用")
        val changedAddresses = signature(current) != signature(entries(effectiveTrackers(plan.desired)))
        applyPolicy(t, if (!plan.desired.enabled) plan.desired.copy(lastAppliedAt = 0) else plan.desired,
            userChange = plan.desired.enabled && changedAddresses)
    }

    private val pendingPlans = LinkedHashMap<String, PendingPlan>()
    private fun planVersion(t: Task, policy: BtTrackerTaskStore.Policy, table: List<TrackerEntry>) =
        BtTrackerPolicy.PlanVersion(t.record.generation, t.handleGeneration, epoch, trackerNetworkEpoch,
            policy.revision, trackerConfig.revision, catalogRevision(), signature(table))
    private data class PendingPlan(val taskId: String, val version: BtTrackerPolicy.PlanVersion,
        val desired: BtTrackerTaskStore.Policy, val expiresAt: Long)

    // ==================== 快照 ====================

    private fun restoreHandle(t: Task) {
        val r = t.record
        val bytes = store.readMetadata(r.taskId) ?: metadata[r.dedupeKey]?.bytes ?: throw IllegalStateException("Metadata missing")
        val m = cacheMetadata(bytes, r.sourceType, r.magnet)
        require(same(r, m.v1, m.v2) && m.files == r.files)
        val dir = taskDirectory(r)
        require(dir.canWrite())
        r.files.forEach { safeFile(r, it) }
        val s = ensureSession()
        val resume = runCatching { store.readResume(r.taskId) }.getOrNull()

        // 统一在这里算有效 Tracker：新建与恢复同一条路径。
        // 不加 OVERRIDE_TRACKERS 的话，resume 里的旧地址会复活。
        val policy = taskPolicy(t, m)
        val effective = effectiveTrackers(policy)

        t.handle = BtTorrentEngine.add(
            session = s,
            info = m.info,
            directory = dir,
            selected = r.selectedFileIndices,
            resume = resume,
            trackers = effective.urls,
            tiers = effective.tiers,
            privateTorrent = m.privacy == BtTrackerPolicy.Privacy.private,
        )
        t.handleGeneration = UUID.randomUUID().toString()
        trackerBindings[t.handle!!.swig().id()] = TrackerBinding(r.taskId, r.generation, t.handleGeneration)
        t.trackerStates.clear()
        if (policy.pending) storeTaskPolicy(t, policy.copy(pending = false))
        t.lastProgressAt = System.currentTimeMillis()
    }
    private fun pauseHandle(t: Task) { t.handle?.let { BtTorrentEngine.pause(it) }; t.rate = 0; t.uploadRate = 0 }
    private fun checkpoint(t: Task) {
        val h = t.handle ?: return
        if (t.checkpointPending) return
        t.checkpointPending = true; t.checkpointAt = System.currentTimeMillis()
        t.finalFlush = t.record.actualState == BtState.VERIFYING
        h.saveResumeData(TorrentHandle.SAVE_INFO_DICT.or_(TorrentHandle.FLUSH_DISK_CACHE))
    }
    private fun onCheckpoint(v1: String?, v2: String?, bytes: ByteArray?) {
        val t = tasks.values.firstOrNull { same(it.record, v1, v2) && it.checkpointPending } ?: return
        t.checkpointPending = false
        val previouslyNotified = t.record.completionNotified
        var notifyCompletion = false
        try {
            if (bytes == null) throw IllegalStateException("Resume unavailable")
            store.saveResume(t.record.taskId, bytes)
            t.trackerPolicy?.let { storeTaskPolicy(t, it.copy(lastStates = t.trackerStates.values.toList())) }
            if (t.record.actualState == BtState.VERIFYING) {
                if (!t.finalFlush) { checkpoint(t); return }
                val h = t.handle ?: throw IllegalStateException("Handle missing")
                require(BtTorrentEngine.verifiedBytes(h, t.record) == t.record.selectedBytes)
                for (f in t.record.files.filter { it.fileIndex in t.record.selectedFileIndices }) {
                    val file = safeFile(t.record, f)
                    if (f.lengthBytes == 0L && !file.exists()) { file.parentFile?.mkdirs(); file.createNewFile() }
                    require(file.isFile && file.canRead() && file.length() == f.lengthBytes)
                }
                change(t, t.record.copy(actualState = BtState.COMPLETED, requestedRunning = false,
                    completedAt = System.currentTimeMillis(), downloadedBytes = t.record.selectedBytes,
                    errorCode = "", completionNotified = true))
                notifyCompletion = !previouslyNotified
            } else if (t.record.actualState == BtState.PAUSING) change(t, t.record.copy(actualState = BtState.PAUSED))
            persist()
            if (notifyCompletion) BtNotifications.completed(appContext, t.record.taskId,
                t.record.displayName, t.record.selectedFileIndices.size, t.record.selectedBytes)
        } catch (_: Exception) {
            pauseHandle(t)
            change(t, t.record.copy(actualState = BtState.BLOCKED_STORAGE, errorCode = "STORAGE_UNAVAILABLE",
                pauseReason = "checkpoint_failed", completionNotified = previouslyNotified))
            runCatching { persist() }
        }
        reconcile(); releaseIfIdle()
    }
    private fun tick() {
        if (!loaded) return
        val now = System.currentTimeMillis()
        var dirty = false
        for (t in tasks.values) {
            val h = t.handle ?: continue
            if (t.checkpointPending && now - t.checkpointAt > 20000) {
                t.checkpointPending = false; pauseHandle(t)
                change(t, t.record.copy(actualState = BtState.BLOCKED_STORAGE, pauseReason = "checkpoint_timeout", errorCode = "RESUME_CORRUPT")); dirty = true
            }
            if (!BtState.isActive(t.record.actualState) || t.record.actualState == BtState.VERIFYING) continue
            val status = h.status()
            if (status.errorCode().isError) {
                pauseHandle(t)
                change(t, t.record.copy(actualState = BtState.BLOCKED_STORAGE, pauseReason = "engine_io_error", errorCode = "STORAGE_UNAVAILABLE")); dirty = true; continue
            }
            val done = BtTorrentEngine.verifiedBytes(h, t.record)
            if (done != t.record.downloadedBytes) t.lastProgressAt = now
            t.rate = status.downloadRate().toLong(); t.uploadRate = status.uploadRate().toLong(); t.peers = status.numPeers()
            val checking = status.state() == TorrentStatus.State.CHECKING_FILES || status.state() == TorrentStatus.State.CHECKING_RESUME_DATA
            val state = when {
                checking -> BtState.CHECKING
                status.isFinished && done == t.record.selectedBytes -> BtState.VERIFYING
                t.peers == 0 -> BtState.WAITING_PEERS
                else -> BtState.DOWNLOADING
            }
            change(t, t.record.copy(actualState = state, downloadedBytes = done))
            if (state == BtState.VERIFYING) {
                pauseHandle(t); checkpoint(t); dirty = true
            } else if (!checking && now - t.lastProgressAt > 10 * 60 * 1000) {
                pauseHandle(t)
                change(t, t.record.copy(actualState = BtState.PAUSED, requestedRunning = false, pauseReason = "no_peers"))
                checkpoint(t); dirty = true
            }
        }
        if (now - lastCheckpoint >= 15000) {
            val activeTasks = tasks.values.filter { BtState.isActive(it.record.actualState) }
            activeTasks.forEach { checkpoint(it) }
            lastCheckpoint = now
            // An idle app must not rewrite both manifests every 15 seconds.
            if (activeTasks.isNotEmpty()) dirty = true
        }
        if (dirty) { persist(); reconcile() }
        if (now - lastNotification >= 1000) { updateNotification(); lastNotification = now }
        releaseIfIdle()
    }
    private fun updateNotification() {
        val t = tasks.values.firstOrNull { BtState.isActive(it.record.actualState) } ?: return
        val total = t.record.selectedBytes
        val percent = if (total > 0) (t.record.downloadedBytes.toDouble() * 100 / total).toInt().coerceIn(0, 99) else 0
        val text = formatBytes(t.record.downloadedBytes) + " / " + formatBytes(total) + " · " + formatBytes(t.rate) + "/s"
        val unknown = t.record.actualState in setOf(BtState.CHECKING, BtState.VERIFYING)
        if (owner == "uidt") BtTransferJobService.update(appContext, t.record.taskId, t.record.displayName, text, percent, unknown)
        else if (owner == "fgs") BtForegroundService.update(appContext, t.record.taskId, t.record.displayName, text, percent, unknown)
    }
    private fun releaseIfIdle() {
        val wanted = tasks.values.any { it.record.requestedRunning && !BtState.isTerminal(it.record.actualState) &&
            it.record.actualState != BtState.BLOCKED_STORAGE }
        if (!wanted && tasks.values.none { it.checkpointPending }) {
            val hadOwner = owner != null || pendingOwner != null ||
                BtTransferJobService.isRunning || BtForegroundService.isRunning
            owner = null
            pendingOwner = null
            if (hadOwner) {
                BtTransferJobService.finish(appContext); BtForegroundService.stop(appContext)
            }
        }
        // Interrupted tasks may still have an OS retry. Do not cancel that pending job,
        // but no owner means no native transfer or DHT activity may run in the meantime.
        if (owner == null && resolving == null) {
            setDiscoveryEnabled(false)
            session?.pause()
        }
    }
    private fun stopForStorageFailure() {
        for (t in tasks.values.filter { BtState.isActive(it.record.actualState) }) {
            runCatching { pauseHandle(t) }
            change(t, t.record.copy(actualState = BtState.BLOCKED_STORAGE, pauseReason = "storage_error", errorCode = "STORAGE_UNAVAILABLE"))
        }
        releaseIfIdle()
    }

    fun getSnapshot(): Future<Map<String, Any>> = post {
        load()
        success() + mapOf("sessionEpoch" to epoch, "sequence" to sequence, "tasks" to tasks.values.map { t ->
            val r = t.record
            mapOf("taskId" to r.taskId, "revision" to t.revision, "generation" to r.generation,
                "displayName" to r.displayName, "state" to r.actualState, "requestedRunning" to r.requestedRunning,
                "totalBytes" to r.totalBytes, "selectedBytes" to r.selectedBytes, "downloadedBytes" to r.downloadedBytes,
                "downloadRate" to t.rate, "uploadRate" to t.uploadRate, "peerCount" to t.peers,
                "fileCount" to r.selectedFileIndices.size, "allFileCount" to r.files.count { !it.isPadding },
                "pauseReason" to r.pauseReason, "errorCode" to r.errorCode, "historyHidden" to r.historyHidden,
                "createdAt" to r.createdAt, "completedAt" to r.completedAt,
                "taskDir" to File(r.targetRoot, r.taskDirName).absolutePath)
        })
    }
    fun pause(taskId: String): Future<Map<String, Any>> = post {
        load(); val t = tasks[taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        if (BtState.isTerminal(t.record.actualState)) return@post success()
        pauseInternal(t); persist(); releaseIfIdle(); success()
    }
    private fun pauseInternal(t: Task) {
        pauseHandle(t)
        change(t, t.record.copy(requestedRunning = false, actualState = if (t.handle != null) BtState.PAUSING else BtState.PAUSED, pauseReason = "user"))
        checkpoint(t)
    }
    fun pauseAllRunning(): Future<Int> = post {
        load(); val affected = tasks.values.filter { it.record.requestedRunning && !BtState.isTerminal(it.record.actualState) }
        affected.forEach { pauseInternal(it) }; persist(); releaseIfIdle(); affected.size
    }
    fun resume(taskId: String): Future<Map<String, Any>> = post {
        load(); val t = tasks[taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        if (t.record.actualState == BtState.COMPLETED) return@post success()
        if (t.record.actualState == BtState.PAUSING) return@post fail("BUSY", "正在保存暂停进度，请稍后继续")
        change(t, t.record.copy(requestedRunning = true, actualState = BtState.QUEUED, pauseReason = "", errorCode = "", historyHidden = false))
        persist(); requestExecution()
        if (t.record.errorCode == "BACKGROUND_START_DENIED" || t.record.errorCode == "BACKGROUND_RESTRICTED")
            fail("BACKGROUND_START_DENIED", "系统未允许启动后台传输，请在插件中重试") + mapOf("taskId" to taskId)
        else success()
    }
    fun cancel(taskId: String): Future<Map<String, Any>> = post {
        load(); val t = tasks[taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        if (t.record.actualState == BtState.COMPLETED) return@post success()
        pauseHandle(t)
        change(t, t.record.copy(requestedRunning = false, actualState = BtState.CANCELED, pauseReason = "user"))
        checkpoint(t); persist(); releaseIfIdle(); success()
    }
    fun removeHistory(taskId: String): Future<Map<String, Any>> = post {
        load(); val t = tasks[taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        if (t.record.actualState != BtState.COMPLETED) return@post fail("BUSY", "请先清理未完成数据，或保留任务以便重试")
        change(t, t.record.copy(historyHidden = true)); persist(); success()
    }
    fun removeArtifact(taskId: String, fileIndex: Int): Future<Map<String, Any>> = post {
        load(); val t = tasks[taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        if (t.record.actualState != BtState.COMPLETED || fileIndex !in t.record.selectedFileIndices)
            return@post fail("FILE_MISSING", "文件尚未完成")
        change(t, t.record.copy(hiddenFileIndices = t.record.hiddenFileIndices + fileIndex)); persist(); success()
    }
    fun deleteTask(taskId: String, deleteFiles: Boolean): Future<Map<String, Any>> {
        val result = CompletableFuture<Map<String, Any>>()
        dispatch {
            try {
                load()
                val t = tasks[taskId]
                if (t == null) { result.complete(success()); return@dispatch }
                if (!deleteFiles) {
                    if (t.record.actualState != BtState.COMPLETED) result.complete(fail("BUSY", "未完成数据需要保留管理记录"))
                    else { change(t, t.record.copy(historyHidden = true)); persist(); result.complete(success()) }
                    return@dispatch
                }
                if (t.record.requestedRunning || BtState.isActive(t.record.actualState) || t.checkpointPending || deleting.containsKey(taskId)) {
                    result.complete(fail("BUSY", "请先暂停任务，等待进度保存")); return@dispatch
                }
                val dir = taskDirectory(t.record)
                if (Files.isSymbolicLink(File(dir, ".clipisle-owner").toPath()) ||
                    File(dir, ".clipisle-owner").takeIf { it.isFile }?.readText() != taskId) {
                    result.complete(fail("UNSAFE_PATH", "无法确认目录归属，已保留文件")); return@dispatch
                }
                t.record.files.forEach { safeFile(t.record, it) }
                if (t.handle == null) restoreHandle(t)
                pauseHandle(t)
                change(t, t.record.copy(actualState = BtState.CANCELING, requestedRunning = false))
                persist()
                deleting[taskId] = result
                // The native disk worker removes only this torrent's files. Wait for its completion
                // alert before removing the index; unlinking immediately after remove() races I/O.
                trackerBindings.remove(t.handle!!.swig().id())
                session!!.remove(t.handle!!, SessionHandle.DELETE_FILES)
                t.handle = null
                queue.schedule({
                    deleting.remove(taskId)?.let { pending ->
                        tasks[taskId]?.let { task ->
                            change(task, task.record.copy(actualState = BtState.BLOCKED_STORAGE,
                                errorCode = "STORAGE_UNAVAILABLE", pauseReason = "delete_timeout"))
                            runCatching { persist() }
                        }
                        pending.complete(fail("STORAGE_UNAVAILABLE", "清理尚未确认完成，请检查保存目录"))
                    }
                }, 25, TimeUnit.SECONDS)
            } catch (_: Exception) {
                deleting.remove(taskId)
                result.complete(fail("STORAGE_UNAVAILABLE", "无法清理任务文件，记录已保留"))
            }
        }
        return result
    }
    private fun finishDelete(v1: String?, v2: String?) {
        val t = tasks.values.firstOrNull { same(it.record, v1, v2) && deleting.containsKey(it.record.taskId) } ?: return
        val id = t.record.taskId
        val result = deleting.remove(id) ?: return
        try {
            val dir = taskDirectory(t.record)
            val marker = File(dir, ".clipisle-owner")
            if (!Files.isSymbolicLink(marker.toPath()) && marker.readText() == id) marker.delete()
            // Preserve any unrelated files that a user placed in the directory.
            Files.walk(dir.toPath()).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.newDirectoryStream(path).use { entries ->
                            if (!entries.iterator().hasNext()) Files.deleteIfExists(path)
                        }
                    }
                }
            }
            tasks.remove(id)
            try { persist() } catch (e: Exception) { tasks[id] = t; throw e }
            taskTrackerStore.remove(id)
            store.removePrivateFiles(id)
            result.complete(success())
        } catch (_: Exception) { result.complete(fail("STORAGE_UNAVAILABLE", "清理记录失败，请检查保存目录")) }
        releaseIfIdle()
    }
    private fun failDelete(best: String) {
        val t = tasks.values.firstOrNull {
            deleting.containsKey(it.record.taskId) &&
                (it.record.infoHashV1Hex == best || it.record.infoHashV2Hex?.take(40) == best)
        } ?: return
        change(t, t.record.copy(actualState = BtState.BLOCKED_STORAGE, errorCode = "STORAGE_UNAVAILABLE", pauseReason = "delete_failed"))
        runCatching { persist() }
        deleting.remove(t.record.taskId)?.complete(fail("STORAGE_UNAVAILABLE", "任务文件清理失败，记录已保留"))
    }
    fun getTaskFiles(taskId: String): Future<Map<String, Any>> = post {
        load(); val t = tasks[taskId] ?: return@post fail("FILE_MISSING", "任务不存在")
        success() + mapOf("taskId" to taskId, "files" to t.record.files.filterNot { it.isPadding }.map { f ->
            val selected = f.fileIndex in t.record.selectedFileIndices
            val file = if (selected && t.record.actualState == BtState.COMPLETED) runCatching { safeFile(t.record, f, true) }.getOrNull() else null
            val available = file != null && file.isFile && file.canRead() && file.length() == f.lengthBytes
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.path.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            mapOf("fileIndex" to f.fileIndex, "path" to f.path, "lengthBytes" to f.lengthBytes, "selected" to selected,
                "hidden" to (f.fileIndex in t.record.hiddenFileIndices),
                "outputPath" to (file?.absolutePath ?: ""), "available" to available, "mime" to mime)
        })
    }
    fun getSettings(): Future<Map<String, Any>> = post { settingsMap() }
    fun updateSettings(unmeteredOnly: Boolean?, maxActive: Int?, downloadRateLimit: Int? = null,
                       uploadRateLimit: Int? = null): Future<Map<String, Any>> = post {
        val nextMax = (maxActive ?: this.maxActive).coerceIn(1, 2)
        val down = downloadRateLimit ?: downloadLimit; val up = uploadRateLimit ?: uploadLimit
        if (down < 0 || up < 0) return@post fail("INVALID_ARGUMENT", "限速不能为负数")
        val unmetered = unmeteredOnly ?: networkPolicy.unmeteredOnly
        val policyChanged = unmetered != networkPolicy.unmeteredOnly
        check(prefs.edit().putInt("maxActive", nextMax).putInt("downloadLimit", down).putInt("uploadLimit", up)
            .putBoolean("unmeteredOnly", unmetered).commit())
        this.maxActive = nextMax; downloadLimit = down; uploadLimit = up
        if (policyChanged && (owner != null || pendingOwner != null)) {
            tasks.values.filter { it.record.requestedRunning && !BtState.isTerminal(it.record.actualState) &&
                it.record.actualState != BtState.BLOCKED_STORAGE }.forEach {
                pauseHandle(it)
                change(it, it.record.copy(actualState = BtState.QUEUED, pauseReason = "network_policy_changed"))
                checkpoint(it)
            }
            owner = null; pendingOwner = null
            BtTransferJobService.finish(appContext); BtForegroundService.stop(appContext)
        }
        networkPolicy.unmeteredOnly = unmetered
        session?.downloadRateLimit(down); session?.uploadRateLimit(up)
        onNetworkChanged(networkPolicy.isSatisfied())
        // Settings edits are explicit foreground user actions. Rebuild a pending UIDT
        // so its OS constraint follows the newly selected metered-network policy.
        if (policyChanged && tasks.values.any { it.record.requestedRunning && !BtState.isTerminal(it.record.actualState) &&
            it.record.actualState != BtState.BLOCKED_STORAGE }) {
            requestExecution()
        }
        persist()
        settingsMap()
    }
    private fun settingsMap(): Map<String, Any> = success() + mapOf(
        "unmeteredOnly" to networkPolicy.unmeteredOnly, "maxActiveDownloads" to maxActive,
        "downloadRateLimit" to downloadLimit, "uploadRateLimit" to uploadLimit,
        "isWifi" to networkPolicy.isWifi(), "networkSatisfied" to networkPolicy.isSatisfied(),
        "backgroundRestricted" to isBackgroundRestricted(),
        "foregroundRunning" to (owner != null), "executionOwner" to (owner ?: "none"))
    private fun formatBytes(bytes: Long): String {
        var value = bytes.toDouble(); val units = arrayOf("B", "KB", "MB", "GB", "TB"); var i = 0
        while (value >= 1000 && i < units.lastIndex) { value /= 1000; i++ }
        return if (i == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[i])
    }
    private fun sanitizeDirName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .take(50).trim().trimEnd('.', ' ').ifBlank { "torrent" }
    private fun closeForTest() {
        queue.submit {
            cancelCustomTrackerValidation("CANCELED")
            resolving?.let { cancelPreview(it) }
            tasks.values.forEach { runCatching { pauseHandle(it) } }
            persist(); owner = null
            BtTransferJobService.finish(appContext); BtForegroundService.stop(appContext); networkHandle?.close()
        }.get(10, TimeUnit.SECONDS)
        trackerService.shutdown()
        metadataWorker.shutdownNow(); metadataWorker.awaitTermination(5, TimeUnit.SECONDS)
        queue.submit { session?.stop(); session = null; closed = true }.get(10, TimeUnit.SECONDS)
        queue.shutdownNow()
    }
}
