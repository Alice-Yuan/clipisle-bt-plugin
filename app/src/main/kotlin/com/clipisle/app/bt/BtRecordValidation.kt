package com.clipisle.app.bt

import java.io.File
import java.io.IOException

/** Validate persisted metadata again before it can direct native filesystem writes. */
internal object BtRecordValidation {
    fun validate(record: BtTaskRecord) {
        fun requireValid(condition: Boolean) {
            if (!condition) throw IOException("Invalid BT task record")
        }
        requireValid(record.taskId.isNotBlank() && record.taskId.length <= 128)
        requireValid(record.generation > 0 && record.actualState in BtState.values)
        requireValid(record.sourceType in setOf("torrent", "magnet"))
        requireValid(record.targetRoot.isNotBlank() && File(record.targetRoot).isAbsolute && '\u0000' !in record.targetRoot)
        requireValid(record.taskDirName.isNotBlank() && '/' !in record.taskDirName && '\\' !in record.taskDirName)
        requireValid(BtPathGuard.sanitize(record.taskDirName) is BtPathGuard.Result.Ok)
        requireValid(record.infoHashV1Hex == null || record.infoHashV1Hex.matches(Regex("[0-9a-fA-F]{40}")))
        requireValid(record.infoHashV2Hex == null || record.infoHashV2Hex.matches(Regex("[0-9a-fA-F]{64}")))
        requireValid(record.infoHashV1Hex != null || record.infoHashV2Hex != null)
        requireValid(record.dedupeKey.isNotBlank())
        requireValid(record.files.isNotEmpty() && record.files.size <= BtPathGuard.MAX_FILES)
        requireValid(record.files.all { it.fileIndex >= 0 && it.lengthBytes >= 0 })
        requireValid(record.files.map { it.fileIndex }.toSet().size == record.files.size)
        requireValid(record.files.all { BtPathGuard.sanitize(it.path) is BtPathGuard.Result.Ok })
        val realFiles = record.files.filterNot { it.isPadding }
        requireValid(BtPathGuard.sanitizeAll(realFiles.map { it.path }) is BtPathGuard.AllResult.Ok)
        requireValid(record.selectedFileIndices.isNotEmpty())
        val validIndices = realFiles.map { it.fileIndex }.toSet()
        requireValid(record.selectedFileIndices.all { it in validIndices })
        requireValid(record.hiddenFileIndices.all { it in record.selectedFileIndices })
        val total = exactSum(realFiles.map { it.lengthBytes })
        val selected = exactSum(realFiles.filter { it.fileIndex in record.selectedFileIndices }.map { it.lengthBytes })
        requireValid(record.totalBytes == total && record.selectedBytes == selected)
        requireValid(record.downloadedBytes in 0..selected)
        requireValid(record.createdAt >= 0 && record.completedAt >= 0)
    }

    private fun exactSum(values: List<Long>): Long = try {
        values.fold(0L) { total, value -> Math.addExact(total, value) }
    } catch (e: ArithmeticException) {
        throw IOException("BT file sizes exceed supported range", e)
    }
}
