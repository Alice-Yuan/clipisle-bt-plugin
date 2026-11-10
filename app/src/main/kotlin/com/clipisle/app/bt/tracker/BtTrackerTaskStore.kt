package com.clipisle.app.bt.tracker

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A task's applied/pending policy is committed independently of mutable subscription caches. */
internal class BtTrackerTaskStore(private val directory: File) {
    data class Policy(
        val taskId: String, val generation: Int, val contentKey: String,
        val privacy: BtTrackerPolicy.Privacy,
        val originals: List<BtTrackerPolicy.Original>,
        val unowned: List<BtTrackerPolicy.Original> = emptyList(),
        val enabled: Boolean = true, val revision: Int = 1,
        val catalogRevision: Long = 0,
        val supplements: List<BtTrackerPolicy.Supplement> = emptyList(),
        val pending: Boolean = false, val lastAppliedAt: Long = 0,
        val lastStates: List<BtTrackerStatus.Entry> = emptyList(),
    ) {
        /** Revocations survive a crash before the live handle has accepted them. */
        fun revoked(globalEnabled: Boolean, disabledKeys: List<String>): Policy {
            val kept = if (globalEnabled) supplements.filterNot { BtTrackerPolicy.disabled(it.url, disabledKeys) }
                else emptyList()
            return if (kept == supplements) this else copy(supplements = kept, revision = revision + 1,
                pending = true, lastAppliedAt = 0)
        }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return File(directory, "$id.policy")
    }

    fun load(id: String): Policy? {
        val target = file(id)
        if (!target.exists()) return null
        require(target.isFile && !Files.isSymbolicLink(target.toPath()) && target.length() in 33..4_194_304)
        val bytes = target.readBytes()
        val body = bytes.copyOfRange(32, bytes.size)
        require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(body)))
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            val version = input.readInt(); require(version in 1..2)
            val taskId = input.readUTF(); val generation = input.readInt(); val key = input.readUTF()
            val privacy = BtTrackerPolicy.Privacy.valueOf(input.readUTF())
            fun originals(): List<BtTrackerPolicy.Original> {
                val count = input.readInt(); require(count in 0..20000)
                return List(count) { BtTrackerPolicy.Original(input.readUTF(), input.readUTF(), input.readInt(), input.readUTF()) }
            }
            val original = originals(); val unowned = originals()
            val enabled = input.readBoolean(); val revision = input.readInt(); val catalog = input.readLong()
            val count = input.readInt(); require(count in 0..20)
            val supplements = List(count) {
                val url = input.readUTF(); val canonical = input.readUTF(); val size = input.readInt()
                require(size in 0..10)
                BtTrackerPolicy.Supplement(url, canonical, List(size) { input.readUTF() })
            }
            val pending = input.readBoolean(); val lastApplied = input.readLong()
            val statuses = if (version == 1) emptyList() else {
                val size = input.readInt(); require(size in 0..20020)
                List(size) {
                    val id = input.readUTF(); val host = input.readUTF(); val protocol = input.readUTF()
                    val origin = input.readUTF(); val state = BtTrackerStatus.AnnounceState.valueOf(input.readUTF())
                    val peers = input.readInt(); val error = input.readUTF()
                    BtTrackerStatus.Entry(id, host, protocol, origin, "", state, peers,
                        error.takeIf { it.isNotEmpty() }?.let { BtTrackerStatus.ErrorKind.valueOf(it) },
                        input.readLong(), input.readInt())
                }
            }
            require(input.available() == 0 && taskId == id && generation > 0 && revision > 0)
            Policy(taskId, generation, key, privacy, original, unowned, enabled, revision,
                catalog, supplements, pending, lastApplied, statuses).also { validate(it) }
        }
    }

    private fun validate(policy: Policy) {
        require(policy.supplements.size <= 20 && policy.revision > 0 && policy.generation > 0)
        require((policy.originals + policy.unowned).all {
            it.url.isNotBlank() && it.canonicalKey == BtTrackerPolicy.keyOf(it.url) && it.tier in 0..255
        })
        require(policy.supplements.all {
            it.canonicalKey == BtTrackerPolicy.keyOf(it.url) && BtTrackerUrl.parse(it.url) is BtTrackerUrl.Result.Ok
        })
        require(policy.privacy == BtTrackerPolicy.Privacy.public || policy.supplements.isEmpty())
        require(policy.lastStates.size <= 20020 && policy.lastStates.all {
            it.canonicalKey.matches(Regex("[0-9a-f]{64}")) && it.displayHost.length <= 512 &&
                !it.displayHost.contains('/') && !it.displayHost.contains('?') && !it.displayHost.contains('@') &&
                it.tier in 0..255 && it.lastPeerCount >= -1
        })
    }

    fun save(policy: Policy) {
        validate(policy)
        require(directory.isDirectory || directory.mkdirs())
        require(!Files.isSymbolicLink(directory.toPath()))
        val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(2); out.writeUTF(policy.taskId); out.writeInt(policy.generation)
            out.writeUTF(policy.contentKey); out.writeUTF(policy.privacy.name)
            fun originals(entries: List<BtTrackerPolicy.Original>) {
                out.writeInt(entries.size)
                entries.forEach { out.writeUTF(it.url); out.writeUTF(it.canonicalKey); out.writeInt(it.tier); out.writeUTF(it.from) }
            }
            originals(policy.originals); originals(policy.unowned)
            out.writeBoolean(policy.enabled); out.writeInt(policy.revision); out.writeLong(policy.catalogRevision)
            out.writeInt(policy.supplements.size)
            policy.supplements.forEach { s ->
                out.writeUTF(s.url); out.writeUTF(s.canonicalKey); out.writeInt(s.sourceIds.size)
                s.sourceIds.forEach { out.writeUTF(it) }
            }
            out.writeBoolean(policy.pending); out.writeLong(policy.lastAppliedAt)
            out.writeInt(policy.lastStates.size)
            policy.lastStates.forEach { s ->
                out.writeUTF(s.canonicalKey); out.writeUTF(s.displayHost); out.writeUTF(s.protocol)
                out.writeUTF(s.origin); out.writeUTF(s.state.name); out.writeInt(s.lastPeerCount)
                out.writeUTF(s.errorKind?.name ?: ""); out.writeLong(s.observedAt); out.writeInt(s.tier)
            }
        } }.toByteArray()
        require(body.size < 4_194_272)
        val target = file(policy.taskId)
        val temporary = File(directory, "${policy.taskId}.pending-write")
        require(!Files.isSymbolicLink(temporary.toPath()) && !Files.isSymbolicLink(target.toPath()))
        FileOutputStream(temporary).use { output ->
            output.write(MessageDigest.getInstance("SHA-256").digest(body)); output.write(body); output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun remove(id: String) { Files.deleteIfExists(file(id).toPath()) }
}
