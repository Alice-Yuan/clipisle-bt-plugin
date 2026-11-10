package com.clipisle.app.bt

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Raw, checksummed metadata/resume blobs with an atomic current/previous pointer. */
internal class BtBlobStore(private val directory: File) {
    private val digestPattern = Regex("[0-9a-f]{64}")

    @Synchronized
    fun save(taskId: String, kind: String, bytes: ByteArray, maxBytes: Int) {
        if (bytes.isEmpty() || bytes.size > maxBytes) throw IOException("Invalid BT checkpoint size")
        ensureDirectory()
        val prefix = prefix(taskId, kind)
        // A fresh checkpoint after a successful recheck can repair a damaged pointer.
        // Readers still report corruption; this explicit save never deletes payload data.
        val previousHashes = try { readPointer(prefix) } catch (_: IOException) { emptyList() }
        val newHash = hash(bytes)
        val target = File(directory, "$prefix.$newHash")
        rejectLink(target)
        writeAtomic(AtomicFile(target), bytes)
        val oldValid = previousHashes.firstOrNull { it != newHash && validFile(prefix, it, maxBytes) != null }
        val pointerBytes = listOfNotNull(newHash, oldValid).joinToString("\n").toByteArray(Charsets.US_ASCII)
        writeAtomic(pointer(prefix), pointerBytes)
        // Only after pointer commit may we discard older, unreferenced generations.
        val keep = setOfNotNull(newHash, oldValid)
        directory.listFiles()?.forEach { candidate ->
            val suffix = candidate.name.removePrefix("$prefix.")
            if (candidate.name.startsWith("$prefix.") && digestPattern.matches(suffix) && suffix !in keep) {
                // Stale cleanup is best-effort; it cannot turn a successful durable save into failure.
                runCatching { Files.deleteIfExists(candidate.toPath()) }
            }
        }
    }

    @Synchronized
    fun read(taskId: String, kind: String, maxBytes: Int): ByteArray? =
        file(taskId, kind, maxBytes)?.let { boundedRead(it, maxBytes) }

    @Synchronized
    fun file(taskId: String, kind: String, maxBytes: Int): File? {
        val prefix = prefix(taskId, kind)
        if (!directory.exists()) return null
        ensureDirectory()
        val hashes = readPointer(prefix)
        if (hashes.isEmpty()) return null
        hashes.forEach { digest -> validFile(prefix, digest, maxBytes)?.let { return it } }
        throw IOException("BT $kind checkpoint is damaged; downloaded files were preserved")
    }

    @Synchronized
    fun remove(taskId: String) {
        if (!directory.exists()) return
        ensureDirectory()
        for (kind in listOf("metadata", "resume")) {
            val prefix = prefix(taskId, kind)
            directory.listFiles()?.filter { it.name.startsWith("$prefix.") }?.forEach {
                if (!it.isDirectory || Files.isSymbolicLink(it.toPath())) Files.deleteIfExists(it.toPath())
            }
        }
    }

    private fun prefix(taskId: String, kind: String): String {
        if (taskId.isBlank() || taskId.length > 128 || kind !in setOf("metadata", "resume")) {
            throw IOException("Invalid BT checkpoint identifier")
        }
        return hash(taskId.toByteArray(Charsets.UTF_8)) + "." + kind
    }

    private fun pointer(prefix: String): AtomicFile = AtomicFile(File(directory, "$prefix.pointer"))

    private fun readPointer(prefix: String): List<String> {
        val pointer = pointer(prefix)
        rejectLink(pointer.baseFile)
        val bytes = try {
            pointer.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(130)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 129) throw IOException("Invalid BT checkpoint pointer")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (e: FileNotFoundException) {
            if (pointer.baseFile.exists() || File(pointer.baseFile.path + ".bak").exists()) throw e
            return emptyList()
        }
        val hashes = bytes.toString(Charsets.US_ASCII).split('\n')
        if (hashes.size !in 1..2 || hashes.any { !digestPattern.matches(it) }) throw IOException("Invalid BT checkpoint pointer")
        return hashes
    }

    private fun validFile(prefix: String, digest: String, maxBytes: Int): File? {
        val file = File(directory, "$prefix.$digest")
        return try {
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile) return null
            val bytes = boundedRead(file, maxBytes)
            if (hash(bytes) == digest) file else null
        } catch (_: IOException) {
            null
        }
    }

    private fun ensureDirectory() {
        rejectLink(directory)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create BT checkpoint directory")
    }

    private fun rejectLink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("BT checkpoint path is a symbolic link")
    }

    private fun boundedRead(file: File, maxBytes: Int): ByteArray = file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw IOException("BT checkpoint exceeds size limit")
            output.write(buffer, 0, count)
        }
        if (output.size() == 0) throw IOException("Empty BT checkpoint")
        output.toByteArray()
    }

    private fun writeAtomic(target: AtomicFile, bytes: ByteArray) {
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            target.finishWrite(stream)
            if (!boundedRead(target.baseFile, bytes.size).contentEquals(bytes)) {
                throw IOException("BT checkpoint commit could not be verified")
            }
        } catch (e: Exception) {
            target.failWrite(stream)
            throw IOException("BT checkpoint could not be saved", e)
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
