package com.clipisle.app.bt.tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Independent validated snapshots; failed writes propagate and cannot publish a successful save. */
class BtTrackerStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "bt/trackers"))

    companion object {
        const val MAX_POOL = 1000
        const val MAX_SOURCES = 6
        const val MAX_CUSTOM_SOURCES = 3
        const val MAX_SOURCE_ENDPOINTS = 4
        const val CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_DISK_BYTES = 8L * 1024 * 1024
        private val BUILTIN_IDS = setOf("xiu2_best", "ngosang_best", "xiu2_black")
    private val ID = Regex("[a-zA-Z0-9_-]{1,64}")
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun validateConfig(config: Config) {
            require(config.revision > 0)
            require(config.sources.size <= MAX_SOURCES && config.sources.map { it.id }.toSet().size == config.sources.size)
            require(config.maxSupplements in 5..BtTrackerPolicy.MAX_SUPPLEMENTS)
            // 内置源不计入「自定义」配额；黑名单源与候选源分开计数
        require(config.sources.count { it.id !in BUILTIN_IDS } <= MAX_CUSTOM_SOURCES)
        require(config.sources.count { it.isBlacklist } <= 2)
            config.sources.forEach { s ->
                require(ID.matches(s.id) && s.name.isNotBlank() && s.name.toByteArray().size <= 256 && s.name.none { it < ' ' })
                // 端点数量：CDN 镜像 + 原站 + 备用，最多 4 个。
                // 早先限制 2 个，加了 jsdelivr 镜像后放不开——大陆可达性
                // 需要「镜像优先、原站兜底」这条链，两个端点不够。
                require(s.endpoints.size in 1..MAX_SOURCE_ENDPOINTS && s.customEndpoints.size <= MAX_SOURCE_ENDPOINTS)
                (s.endpoints + s.customEndpoints).forEach { require(BtTrackerUrl.validateSubscriptionUrl(it) is BtTrackerUrl.Result.Ok) }
                require(s.effectiveEndpoints().distinct().size == s.effectiveEndpoints().size)
            }
            require(config.customPublicTrackers.size <= 20)
            val keys = config.customPublicTrackers.map { publicUrl(it).canonicalKey }
            require(keys.distinct().size == keys.size)
            require(config.disabledSupplementKeys.size <= MAX_POOL && config.disabledSupplementKeys.distinct().size == config.disabledSupplementKeys.size)
            require(config.disabledSupplementKeys.all { it.isNotBlank() && it.length <= 2048 && it.none { c -> c < ' ' } })
        }
        private fun publicUrl(url: String): BtTrackerUrl.Parsed {
            val p = (BtTrackerUrl.parse(url) as? BtTrackerUrl.Result.Ok)?.value ?: throw IllegalArgumentException("Invalid tracker URL")
            require(BtTrackerUrl.isPublicHost(p.host))
            return p
        }
        /** Preserve built-in fallback endpoints and kind when the user edits a source. */
        fun editSource(previous: SourceConfig?, id: String, name: String, enabled: Boolean,
                       endpoints: List<String>): SourceConfig? {
            if (previous != null && previous.id != id) return null
            val clean = endpoints.map { it.trim() }
            val unchanged = previous != null && clean == previous.effectiveEndpoints()
            val limit = if (id in BUILTIN_IDS && unchanged) MAX_SOURCE_ENDPOINTS else 2
            if (name.isBlank() || name.length > 80 || clean.size !in 1..limit || clean.distinct().size != clean.size ||
                clean.any { BtTrackerUrl.validateSubscriptionUrl(it) !is BtTrackerUrl.Result.Ok }) return null
            return if (previous == null) SourceConfig(id, name.trim(), enabled, clean)
            else if (unchanged) previous.copy(name = name.trim(), enabled = enabled)
            else previous.copy(name = name.trim(), enabled = enabled, customEndpoints = clean)
        }
        fun usable(source: SourceConfig, cache: SourceCache, now: Long = System.currentTimeMillis()): Boolean = source.enabled && cache.dnsValidated &&
            cache.sourceId == source.id && !cache.isExpired(now) &&
            (cache.sourceFingerprint == source.fingerprint() || cache.sourceFingerprint.isEmpty() && cache.endpoint in source.effectiveEndpoints())

        fun pool(config: Config, sources: Map<String, SourceCache>, now: Long = System.currentTimeMillis()): List<String> {
            val seen = LinkedHashMap<String, String>()
            config.sources.filter { it.enabled && !it.isBlacklist }.forEach { source ->
                sources[source.id]?.takeIf { usable(source, it, now) }?.urls?.forEach { url ->
                    val parsed = publicUrl(url)
                    if (parsed.canonicalKey !in config.disabledSupplementKeys && hash(parsed.canonicalKey) !in config.disabledSupplementKeys)
                        seen.putIfAbsent(parsed.canonicalKey, url)
                }
            }
            return seen.values.toList()
        }
    }

    /**
     * 订阅来源。
     *
     * [kind] 区分「候选源」与「黑名单源」——这也是借 Motrix 的分层：
     * 黑名单不是「禁用地址」的替代品。禁用地址是用户手动排除的补充项，
     * 黑名单是上游维护者维护的劣质地址清单，语义不同，不能混用同一个列表。
     */
    data class SourceConfig(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val endpoints: List<String>,
        val customEndpoints: List<String> = emptyList(),
        /** candidates 或 blacklist */
        val kind: String = "candidates",
    ) {
        fun effectiveEndpoints(): List<String> = customEndpoints.ifEmpty { endpoints }
        fun fingerprint(): String = hash(id + "\n" + effectiveEndpoints().joinToString("\n"))
        val isBlacklist: Boolean get() = kind == "blacklist"
    }
    data class Config(val revision: Int, val supplementsEnabled: Boolean, val autoUpdateEnabled: Boolean,
                      val sources: List<SourceConfig>, val customPublicTrackers: List<String>, val disabledSupplementKeys: List<String>,
                      val customDnsValidated: Boolean = false,
                      /** 黑名单总开关。默认开。 */
                      val blacklistEnabled: Boolean = true,
                      /** 每个任务最多补充多少个（任务书原值 20，这里做成可配 5–50）。 */
                      val maxSupplements: Int = 20,
                      /** 自动同步间隔（小时）。Motrix 允许 1–168。 */
                      val syncIntervalHours: Int = 24,
                      /** 上次成功同步的时间戳，用于间隔判断。 */
                      val lastAutoSyncAt: Long = 0) {
        companion object {
            /**
             * 内置来源。
             *
             * 与 Motrix 的差异是**有意**的：
             *  - 每个逻辑源一组端点，按顺序回退；不像 Motrix 那样把 raw 与
             *    CDN 算成两个独立源（那会让同一个列表被拉两次、重复计入池）
             *  - raw.githubusercontent.com 在大陆常年不通，所以 CDN 放前面
             *  - 不加 anime 源：那是特定内容域的列表，对本项目的中文短视频/
             *    影视用户没有对应收益，反而多一份网络请求
             */
            fun defaults() = Config(
                revision = 1,
                supplementsEnabled = true,
                autoUpdateEnabled = true,
                sources = listOf(
                    SourceConfig(
                        id = "xiu2_best",
                        name = "XIU2",
                        enabled = true,
                        endpoints = listOf(
                            // jsdelivr 镜像优先：大陆直连 raw.githubusercontent.com 多数不通
                            "https://cdn.jsdelivr.net/gh/XIU2/TrackersListCollection@master/best.txt",
                            "https://cf.trackerslist.com/best.txt",
                            "https://raw.githubusercontent.com/XIU2/TrackersListCollection/master/best.txt",
                        ),
                    ),
                    SourceConfig(
                        id = "ngosang_best",
                        name = "ngosang",
                        enabled = true,
                        endpoints = listOf(
                            "https://cdn.jsdelivr.net/gh/ngosang/trackerslist@master/trackers_best.txt",
                            "https://ngosang.github.io/trackerslist/trackers_best.txt",
                            "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt",
                        ),
                    ),
                    // 黑名单源：独立 kind，不参与候选池，只用于排除
                    SourceConfig(
                        id = "xiu2_black",
                        name = "XIU2 黑名单",
                        enabled = true,
                        endpoints = listOf(
                            "https://cdn.jsdelivr.net/gh/XIU2/TrackersListCollection@master/blacklist.txt",
                            "https://raw.githubusercontent.com/XIU2/TrackersListCollection/master/blacklist.txt",
                        ),
                        kind = "blacklist",
                    ),
                ),
                customPublicTrackers = emptyList(),
                disabledSupplementKeys = emptyList(),
            )
        }
    }
    data class EndpointCache(val urls: List<String>, val etag: String?, val lastModified: String?, val successfulAt: Long)
    data class SourceCache(val sourceId: String, val endpoint: String, val urls: List<String>, val etag: String?,
                           val lastModified: String?, val lastAttemptAt: Long, val lastSuccessfulCheckAt: Long,
                           val contentUpdatedAt: Long, val lastError: String? = null, val consecutiveFailures: Int = 0,
                           val nextAttemptAt: Long = 0, val sourceFingerprint: String = "", val retryAfterUntil: Long = 0,
                           val endpointCaches: Map<String, EndpointCache> = emptyMap(), val dnsValidated: Boolean = false) {
        fun isExpired(now: Long = System.currentTimeMillis()) = lastSuccessfulCheckAt <= 0 ||
            now < lastSuccessfulCheckAt || now - lastSuccessfulCheckAt >= CACHE_AGE_MS
        val expiresAt: Long get() = if (lastSuccessfulCheckAt <= 0) 0 else lastSuccessfulCheckAt + CACHE_AGE_MS
        fun endpointCache(url: String): EndpointCache? = endpointCaches[url] ?: if (endpoint == url && urls.isNotEmpty())
            EndpointCache(urls, etag, lastModified, lastSuccessfulCheckAt) else null
    }
    data class Catalog(val sources: Map<String, SourceCache>, val pool: List<String>, val poolUpdatedAt: Long,
                       val revision: Long = 0, val damagedSourceIds: Set<String> = emptySet())
    sealed class LoadResult<out T> {
        data class Ok<T>(val value: T) : LoadResult<T>()
        data object Absent : LoadResult<Nothing>()
        data object Damaged : LoadResult<Nothing>()
    }

    @Synchronized fun loadConfig(): LoadResult<Config> = loadPair("config.json", ::decodeConfig)
    @Synchronized fun saveConfig(config: Config) {
        validateConfig(config)
        if (loadConfig() == LoadResult.Damaged) throw IOException("Tracker configuration requires explicit recovery")
        savePair("config.json", encodeConfig(config), ::decodeConfig)
    }
    @Synchronized fun loadCatalog(): LoadResult<Catalog> {
        val current = read(File(dir, "catalog.json"), ::decodeCatalog)
        val previous = read(File(dir, "catalog.json.bak"), ::decodeCatalog)
        if (current != null) {
            val repaired = LinkedHashMap(current.sources)
            current.damagedSourceIds.forEach { id -> previous?.sources?.get(id)?.let { repaired[id] = it } }
            val damaged = current.damagedSourceIds - repaired.keys
            return LoadResult.Ok(withCandidatePool(current.copy(sources = repaired, damagedSourceIds = damaged)))
        }
        if (previous != null) return LoadResult.Ok(withCandidatePool(previous))
        return if (File(dir, "catalog.json").exists() || File(dir, "catalog.json.bak").exists()) LoadResult.Damaged else LoadResult.Absent
    }
    private fun withCandidatePool(catalog: Catalog): Catalog {
        val blacklistIds = (loadConfig() as? LoadResult.Ok)?.value?.sources
            ?.filter { it.isBlacklist }?.map { it.id }?.toSet().orEmpty()
        val candidates = catalog.sources.filterKeys { it !in blacklistIds }.values.flatMap { it.urls }.distinct().take(MAX_POOL)
        return catalog.copy(pool = candidates)
    }
    @Synchronized fun saveCatalog(catalog: Catalog) {
        require(catalog.sources.size <= MAX_SOURCES && catalog.pool.size <= MAX_POOL && catalog.poolUpdatedAt >= 0 && catalog.revision >= 0)
        catalog.sources.forEach { (id, value) -> require(id == value.sourceId); validateCache(value) }
        require(catalog.pool.map { publicUrl(it).canonicalKey }.distinct().size == catalog.pool.size)
        val json = encodeCatalog(catalog)
        decodeCatalog(json)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Tracker directory unavailable")
        // Back up the repaired snapshot, never overwrite a good previous source
        // with a partially corrupt current container.
        val previous = (loadCatalog() as? LoadResult.Ok)?.value
        if (previous != null) writeAtomic(File(dir, "catalog.json.bak"), encodeCatalog(previous).toString().toByteArray(Charsets.UTF_8))
        val current = File(dir, "catalog.json")
        if (current.exists()) {
            val decoded = read(current, ::decodeCatalog)
            if (decoded == null || decoded.damagedSourceIds.isNotEmpty())
                Files.copy(current.toPath(), File(dir, "catalog.json.corrupt-${UUID.randomUUID()}").toPath())
        }
        writeAtomic(current, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun validateCache(c: SourceCache) {
        require(ID.matches(c.sourceId) && c.urls.size <= 500 && c.endpointCaches.size <= MAX_SOURCE_ENDPOINTS)
        require(c.endpoint.isEmpty() || BtTrackerUrl.validateSubscriptionUrl(c.endpoint) is BtTrackerUrl.Result.Ok)
        require(listOf(c.lastAttemptAt, c.lastSuccessfulCheckAt, c.contentUpdatedAt, c.nextAttemptAt, c.retryAfterUntil).all { it >= 0 })
        require(c.consecutiveFailures >= 0 && c.sourceFingerprint.length <= 64)
        require(c.lastError == null || c.lastError.matches(Regex("[a-z0-9_]{1,80}")))
        require(c.etag == null || c.etag.length <= 2048 && c.etag.none { it < ' ' })
        require(c.lastModified == null || c.lastModified.length <= 2048 && c.lastModified.none { it < ' ' })
        require(c.urls.map { publicUrl(it).canonicalKey }.distinct().size == c.urls.size)
        c.endpointCaches.forEach { (url, cache) ->
            require(BtTrackerUrl.validateSubscriptionUrl(url) is BtTrackerUrl.Result.Ok && cache.urls.size in 1..500 && cache.successfulAt > 0)
            cache.urls.forEach { publicUrl(it) }
            require(cache.etag == null || cache.etag.length <= 2048 && cache.etag.none { it < ' ' })
            require(cache.lastModified == null || cache.lastModified.length <= 2048 && cache.lastModified.none { it < ' ' })
        }
    }
    private fun decodeConfig(o: JSONObject): Config {
        require(o.getInt("schemaVersion") == 1)
        return Config(o.getInt("revision"), bool(o, "supplementsEnabled"), bool(o, "autoUpdateEnabled"),
            objects(o, "sources").map { s -> SourceConfig(s.getString("id"), s.getString("name"), bool(s, "enabled"),
                strings(s, "endpoints"), strings(s, "customEndpoints"),
                // 老配置没有 kind 字段：默认按候选源处理，不改变既有行为
                s.optString("kind", "candidates").takeIf { it == "blacklist" } ?: "candidates") },
            strings(o, "customPublicTrackers"), strings(o, "disabledSupplementKeys"),
            if (o.has("customDnsValidated")) bool(o, "customDnsValidated") else false,
            if (o.has("blacklistEnabled")) bool(o, "blacklistEnabled") else true,
            if (o.has("maxSupplements")) o.getInt("maxSupplements").coerceIn(5, BtTrackerPolicy.MAX_SUPPLEMENTS) else 20,
            if (o.has("syncIntervalHours")) o.getInt("syncIntervalHours").coerceIn(1, 168) else 24,
            if (o.has("lastAutoSyncAt")) o.getLong("lastAutoSyncAt") else 0L,
        ).also(::validateConfig)
    }
    private fun encodeConfig(c: Config) = JSONObject().put("schemaVersion", 1).put("revision", c.revision)
        .put("supplementsEnabled", c.supplementsEnabled).put("autoUpdateEnabled", c.autoUpdateEnabled)
        .put("customDnsValidated", c.customDnsValidated)
        .put("blacklistEnabled", c.blacklistEnabled).put("maxSupplements", c.maxSupplements)
        .put("syncIntervalHours", c.syncIntervalHours).put("lastAutoSyncAt", c.lastAutoSyncAt)
        .put("customPublicTrackers", JSONArray(c.customPublicTrackers)).put("disabledSupplementKeys", JSONArray(c.disabledSupplementKeys))
        .put("sources", JSONArray(c.sources.map { s -> JSONObject().put("id", s.id).put("name", s.name).put("enabled", s.enabled)
            .put("kind", s.kind)
            .put("endpoints", JSONArray(s.endpoints)).put("customEndpoints", JSONArray(s.customEndpoints)) }))

    private fun decodeCache(s: JSONObject): SourceCache {
        val endpoints = LinkedHashMap<String, EndpointCache>()
        s.optJSONArray("endpointCaches")?.let { a -> for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            require(endpoints.put(e.getString("endpoint"), EndpointCache(strings(e, "urls"), optional(e, "etag"), optional(e, "lastModified"), e.getLong("successfulAt"))) == null)
        } }
        return SourceCache(s.getString("sourceId"), s.getString("endpoint"), strings(s, "urls"), optional(s, "etag"), optional(s, "lastModified"),
            s.getLong("lastAttemptAt"), s.getLong("lastSuccessfulCheckAt"), s.getLong("contentUpdatedAt"), optional(s, "lastError"),
            s.getInt("consecutiveFailures"), s.getLong("nextAttemptAt"), s.optString("sourceFingerprint", ""), s.optLong("retryAfterUntil", 0), endpoints,
            if (s.has("dnsValidated")) bool(s, "dnsValidated") else false).also(::validateCache)
    }
    private fun decodeCatalog(o: JSONObject): Catalog {
        require(o.getInt("schemaVersion") == 1)
        val sources = LinkedHashMap<String, SourceCache>(); val damaged = LinkedHashSet<String>()
        val array = o.getJSONArray("sources"); require(array.length() <= MAX_SOURCES)
        for (i in 0 until array.length()) {
            val s = array.getJSONObject(i); val id = s.getString("sourceId")
            require(ID.matches(id) && id !in sources && id !in damaged)
            try { sources[id] = decodeCache(s) } catch (_: Exception) { damaged.add(id) }
        }
        val time = o.getLong("poolUpdatedAt"); val revision = o.optLong("revision", 0)
        require(time >= 0 && revision >= 0)
        return Catalog(sources, sources.values.flatMap { it.urls }.distinct().take(MAX_POOL), time, revision, damaged)
    }
    private fun encodeCatalog(c: Catalog): JSONObject = JSONObject().put("schemaVersion", 1).put("poolUpdatedAt", c.poolUpdatedAt)
        .put("revision", c.revision).put("pool", JSONArray(c.pool)).put("sources", JSONArray(c.sources.values.map { s ->
            JSONObject().put("sourceId", s.sourceId).put("endpoint", s.endpoint).put("urls", JSONArray(s.urls)).put("etag", s.etag ?: "")
                .put("lastModified", s.lastModified ?: "").put("lastAttemptAt", s.lastAttemptAt).put("lastSuccessfulCheckAt", s.lastSuccessfulCheckAt)
                .put("contentUpdatedAt", s.contentUpdatedAt).put("lastError", s.lastError ?: "").put("consecutiveFailures", s.consecutiveFailures)
                .put("nextAttemptAt", s.nextAttemptAt).put("sourceFingerprint", s.sourceFingerprint).put("retryAfterUntil", s.retryAfterUntil)
                .put("dnsValidated", s.dnsValidated)
                .put("endpointCaches", JSONArray(s.endpointCaches.map { (url, cache) -> JSONObject().put("endpoint", url).put("urls", JSONArray(cache.urls))
                    .put("etag", cache.etag ?: "").put("lastModified", cache.lastModified ?: "").put("successfulAt", cache.successfulAt) }))
        }))

    private fun optional(o: JSONObject, key: String): String? = if (!o.has(key) || o.isNull(key)) null else o.getString(key).takeIf { it.isNotEmpty() }
    private fun bool(o: JSONObject, key: String): Boolean = (o.get(key) as? Boolean) ?: throw IllegalArgumentException("Invalid boolean")
    private fun strings(o: JSONObject, key: String): List<String> = o.getJSONArray(key).let { a -> (0 until a.length()).map { (a.get(it) as? String) ?: throw IllegalArgumentException("Invalid string") } }
    private fun objects(o: JSONObject, key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun <T> read(file: File, decode: (JSONObject) -> T): T? = try {
        if (!file.isFile || file.length() !in 1..MAX_DISK_BYTES) null else decode(JSONObject(file.readText(Charsets.UTF_8)))
    } catch (_: Exception) { null }
    private fun <T> loadPair(name: String, decode: (JSONObject) -> T): LoadResult<T> {
        read(File(dir, name), decode)?.let { return LoadResult.Ok(it) }
        read(File(dir, "$name.bak"), decode)?.let { return LoadResult.Ok(it) }
        return if (File(dir, name).exists() || File(dir, "$name.bak").exists()) LoadResult.Damaged else LoadResult.Absent
    }
    private fun <T> savePair(name: String, value: JSONObject, decode: (JSONObject) -> T) {
        decode(value)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Tracker directory unavailable")
        val current = File(dir, name)
        if (current.exists()) {
            if (read(current, decode) != null) writeAtomic(File(dir, "$name.bak"), current.readBytes())
            else Files.copy(current.toPath(), File(dir, "$name.corrupt-${UUID.randomUUID()}").toPath())
        }
        writeAtomic(current, value.toString().toByteArray(Charsets.UTF_8))
    }
    private fun writeAtomic(file: File, bytes: ByteArray) {
        val temp = File(dir, ".${file.name}.${UUID.randomUUID()}.new")
        try {
            FileOutputStream(temp).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            if (!file.readBytes().contentEquals(bytes)) throw IOException("Tracker write verification failed")
        } finally { temp.delete() }
    }
}
