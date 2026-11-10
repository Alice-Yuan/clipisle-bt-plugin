package com.clipisle.app.bt

import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class BtRecordValidationTest {
    private fun record() = BtTaskRecord(
        taskId = "fixture", generation = 1, dedupeKey = "a".repeat(40), magnet = "",
        infoHashV1Hex = "a".repeat(40), infoHashV2Hex = null, displayName = "Fixture",
        sourceType = "torrent", targetRoot = File(".").absolutePath, taskDirName = "Fixture_12345678",
        selectedFileIndices = setOf(0), createdAt = 1, completedAt = 0, requestedRunning = false,
        actualState = BtState.PAUSED, pauseReason = "user", historyHidden = false,
        totalBytes = 5_000_000_000L, selectedBytes = 5_000_000_000L,
        files = listOf(BtFileRecord(0, "large.bin", 5_000_000_000L, false)),
    )

    private fun rejected(record: BtTaskRecord) {
        try {
            BtRecordValidation.validate(record)
            fail("Invalid persisted task must be rejected")
        } catch (_: IOException) { }
    }

    @Test fun acceptsLongFileSizesAndHiddenCompletedIndexes() {
        BtRecordValidation.validate(record().copy(historyHidden = true, actualState = BtState.COMPLETED,
            downloadedBytes = 5_000_000_000L, completedAt = 2))
    }

    @Test fun rejectsInvalidSelectionsAndDirectoryNames() {
        rejected(record().copy(selectedFileIndices = setOf(999)))
        rejected(record().copy(selectedFileIndices = emptySet()))
        rejected(record().copy(hiddenFileIndices = setOf(1)))
        rejected(record().copy(taskDirName = "../outside"))
        rejected(record().copy(files = listOf(BtFileRecord(0, "../outside", 5_000_000_000L, false))))
    }

    @Test fun rejectsSizeMismatchAndCounterOverflow() {
        rejected(record().copy(selectedBytes = 1))
        rejected(record().copy(downloadedBytes = 5_000_000_001L))
        rejected(record().copy(files = listOf(BtFileRecord(0, "a", Long.MAX_VALUE, false), BtFileRecord(1, "b", 1, false))))
    }

    @Test fun paddingIsNotASelectableRealFile() {
        val r = record().copy(files = record().files + BtFileRecord(1, ".pad/1024", 1024, true))
        BtRecordValidation.validate(r)
        rejected(r.copy(selectedFileIndices = setOf(0, 1)))
    }
}
