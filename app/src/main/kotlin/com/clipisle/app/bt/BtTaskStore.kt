package com.clipisle.app.bt

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** A failed load/write must never become an empty successful save. */
class BtTaskStore(context: Context) {
    companion object {
        private const val TAG = "BtTaskStore"
        private const val SCHEMA_VERSION = 1
        private const val MAX_RECORD_BYTES = 16 * 1024 * 1024
    }

    private val dir = File(context.filesDir, "bt").apply {
        if (!isDirectory && !mkdirs()) throw IOException("Cannot create BT state directory")
    }
    private val atomic = AtomicFile(File(dir, "bt_tasks.json"))
    private val previous = AtomicFile(File(dir, "bt_tasks.previous.json"))
    private val blobs = BtBlobStore(File(dir, "checkpoints"))
    private var initialized = false
    private var lastGoodBytes: ByteArray? = null

    @Synchronized
    fun load(): List<BtTaskRecord> {
        var failure: Exception? = null
        for (candidate in listOf(atomic, previous)) {
            try {
                val bytes = readManifest(candidate) ?: continue
                val records = decode(bytes)
                lastGoodBytes = bytes
                initialized = true
                if (candidate === previous) Log.w(TAG, "Recovered previous valid task manifest")
                return records
            } catch (e: UnsupportedSchemaException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        if (failure != null) throw IOException("BT task records are damaged; files were preserved", failure)
        initialized = true
        return emptyList()
    }

    /** Acknowledgement is only safe after this returns. All failures propagate. */
    @Synchronized
    fun save(tasks: List<BtTaskRecord>) {
        if (!initialized) load()
        validateRecords(tasks)
        val root = JSONObject().put("schemaVersion", SCHEMA_VERSION)
            .put("tasks", JSONArray().apply { tasks.forEach { put(toJson(it)) } })
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECORD_BYTES) throw IOException("BT task manifest exceeds size limit")
        lastGoodBytes?.let { writeManifest(previous, it) }
        writeManifest(atomic, bytes)
        lastGoodBytes = bytes
    }

    fun saveMetadata(taskId: String, bytes: ByteArray) = blobs.save(taskId, "metadata", bytes, 16 * 1024 * 1024)
    fun readMetadata(taskId: String): ByteArray? = blobs.read(taskId, "metadata", 16 * 1024 * 1024)
    fun metadataFile(taskId: String): File? = blobs.file(taskId, "metadata", 16 * 1024 * 1024)
    fun saveResume(taskId: String, bytes: ByteArray) = blobs.save(taskId, "resume", bytes, 32 * 1024 * 1024)
    fun readResume(taskId: String): ByteArray? = blobs.read(taskId, "resume", 32 * 1024 * 1024)
    fun resumeFile(taskId: String): File? = blobs.file(taskId, "resume", 32 * 1024 * 1024)

    /** Removes only private engine state, never user payload files. */
    fun removePrivateFiles(taskId: String) = blobs.remove(taskId)

    fun deleteAll() {
        atomic.delete()
        previous.delete()
        lastGoodBytes = null
        initialized = true
    }

    private fun readManifest(source: AtomicFile): ByteArray? = try {
        source.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RECORD_BYTES) throw IOException("BT task manifest exceeds size limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (e: FileNotFoundException) {
        if (source.baseFile.exists() || File(source.baseFile.path + ".bak").exists()) throw e
        null
    }

    private fun writeManifest(target: AtomicFile, bytes: ByteArray) {
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            target.finishWrite(stream)
            if (readManifest(target)?.contentEquals(bytes) != true) {
                throw IOException("BT task manifest commit could not be verified")
            }
        } catch (e: Exception) {
            target.failWrite(stream)
            throw IOException("BT task records could not be saved", e)
        }
    }

    private fun decode(bytes: ByteArray): List<BtTaskRecord> {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = root.getInt("schemaVersion")
        if (version > SCHEMA_VERSION) throw UnsupportedSchemaException()
        if (version != SCHEMA_VERSION) throw IOException("Unsupported BT manifest schema")
        val arr = root.getJSONArray("tasks")
        val records = (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        validateRecords(records)
        return records
    }

    private fun validateRecords(records: List<BtTaskRecord>) {
        if (records.map { it.taskId }.toSet().size != records.size) throw IOException("Duplicate BT task identifiers")
        records.forEach(BtRecordValidation::validate)
    }

    private fun toJson(t: BtTaskRecord): JSONObject = JSONObject().apply {
        put("taskId", t.taskId)
        put("generation", t.generation)
        put("dedupeKey", t.dedupeKey)
        put("magnet", t.magnet)
        t.infoHashV1Hex?.let { put("infoHashV1", it) }
        t.infoHashV2Hex?.let { put("infoHashV2", it) }
        put("displayName", t.displayName)
        put("sourceType", t.sourceType)
        put("targetRoot", t.targetRoot)
        put("taskDirName", t.taskDirName)
        put("selectedIndices", JSONArray(t.selectedFileIndices.toList()))
        put("createdAt", t.createdAt)
        put("completedAt", t.completedAt)
        put("requestedRunning", t.requestedRunning)
        put("actualState", t.actualState)
        put("pauseReason", t.pauseReason)
        put("historyHidden", t.historyHidden)
        put("totalBytes", t.totalBytes)
        put("selectedBytes", t.selectedBytes)
        put("downloadedBytes", t.downloadedBytes)
        put("completionNotified", t.completionNotified)
        put("errorCode", t.errorCode)
        put("hiddenFileIndices", JSONArray(t.hiddenFileIndices.toList()))
        put("files", JSONArray().apply {
            for (f in t.files) put(JSONObject().apply {
                put("fileIndex", f.fileIndex)
                put("path", f.path)
                put("lengthBytes", f.lengthBytes)
                put("isPadding", f.isPadding)
            })
        })
    }

    private fun fromJson(o: JSONObject): BtTaskRecord {
        val filesArr = o.getJSONArray("files")
        val files = (0 until filesArr.length()).map { i ->
            val f = filesArr.getJSONObject(i)
            BtFileRecord(f.getInt("fileIndex"), f.getString("path"), f.getLong("lengthBytes"), f.optBoolean("isPadding", false))
        }
        val selArr = o.getJSONArray("selectedIndices")
        val selected = (0 until selArr.length()).map { selArr.getInt(it) }
        if (selected.toSet().size != selected.size) throw IOException("Duplicate selected file indices")
        val hiddenArr = o.optJSONArray("hiddenFileIndices") ?: JSONArray()
        val hidden = (0 until hiddenArr.length()).map { hiddenArr.getInt(it) }.toSet()
        return BtTaskRecord(
            taskId = o.getString("taskId"),
            generation = o.optInt("generation", 1),
            dedupeKey = o.getString("dedupeKey"),
            magnet = o.optString("magnet", ""),
            infoHashV1Hex = o.optString("infoHashV1").takeIf { it.isNotEmpty() },
            infoHashV2Hex = o.optString("infoHashV2").takeIf { it.isNotEmpty() },
            displayName = o.getString("displayName"),
            sourceType = o.optString("sourceType", "magnet"),
            targetRoot = o.getString("targetRoot"),
            taskDirName = o.getString("taskDirName"),
            selectedFileIndices = selected.toSet(),
            createdAt = o.getLong("createdAt"),
            completedAt = o.optLong("completedAt", 0),
            requestedRunning = o.optBoolean("requestedRunning", false),
            actualState = o.optString("actualState", BtState.PAUSED),
            pauseReason = o.optString("pauseReason"),
            historyHidden = o.optBoolean("historyHidden", false),
            totalBytes = o.getLong("totalBytes"),
            selectedBytes = o.getLong("selectedBytes"),
            files = files,
            downloadedBytes = o.optLong("downloadedBytes", 0),
            completionNotified = o.optBoolean("completionNotified", false),
            errorCode = o.optString("errorCode", ""),
            hiddenFileIndices = hidden,
        )
    }

    private class UnsupportedSchemaException : IOException("BT records were created by a newer application")
}

data class BtTaskRecord(
    val taskId: String,
    val generation: Int,
    val dedupeKey: String,
    val magnet: String,
    val infoHashV1Hex: String?,
    val infoHashV2Hex: String?,
    val displayName: String,
    val sourceType: String,
    val targetRoot: String,
    val taskDirName: String,
    val selectedFileIndices: Set<Int>,
    val createdAt: Long,
    val completedAt: Long,
    val requestedRunning: Boolean,
    val actualState: String,
    val pauseReason: String,
    val historyHidden: Boolean,
    val totalBytes: Long,
    val selectedBytes: Long,
    val files: List<BtFileRecord>,
    val downloadedBytes: Long = 0,
    val completionNotified: Boolean = false,
    val errorCode: String = "",
    val hiddenFileIndices: Set<Int> = emptySet(),
)

data class BtFileRecord(
    val fileIndex: Int,
    val path: String,
    val lengthBytes: Long,
    val isPadding: Boolean,
)

object BtState {
    const val QUEUED = "queued"
    const val CHECKING = "checking"
    const val DOWNLOADING = "downloading"
    const val WAITING_PEERS = "waitingForPeers"
    const val PAUSING = "pausing"
    const val PAUSED = "paused"
    const val VERIFYING = "verifying"
    const val COMMITTING = "committing"
    const val COMPLETED = "completed"
    const val WAITING_NETWORK = "waitingForNetwork"
    const val BLOCKED_STORAGE = "blockedStorage"
    const val INTERRUPTED = "interrupted"
    const val FAILED = "failed"
    const val CANCELING = "canceling"
    const val CANCELED = "canceled"

    val values = setOf(QUEUED, CHECKING, DOWNLOADING, WAITING_PEERS, PAUSING, PAUSED,
        VERIFYING, COMMITTING, COMPLETED, WAITING_NETWORK, BLOCKED_STORAGE, INTERRUPTED,
        FAILED, CANCELING, CANCELED)

    fun isTerminal(s: String): Boolean = s == COMPLETED || s == CANCELED || s == FAILED
    fun isActive(s: String): Boolean = s == CHECKING || s == DOWNLOADING ||
        s == WAITING_PEERS || s == VERIFYING || s == COMMITTING
}
