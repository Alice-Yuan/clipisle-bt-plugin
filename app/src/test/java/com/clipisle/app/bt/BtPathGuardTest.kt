package com.clipisle.app.bt

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class BtPathGuardTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preservesAcceptedEnginePathsExactly() {
        val path = "作品/第二集 (1080p)/字幕.srt"
        assertEquals(BtPathGuard.Result.Ok(path), BtPathGuard.sanitize(path))
    }

    @Test fun rejectsTraversalAndImplicitPathRewrites() {
        for (path in listOf("../outside", "/absolute", "C:/disk/file", "a\\b", "a//b", "a/./b", "a/../b", "a/", "a.", "a ", "a\tb", "a\u0000b")) {
            assertTrue(path, BtPathGuard.sanitize(path) is BtPathGuard.Result.Reject)
        }
    }

    @Test fun rejectsNamesWhichWouldCollideOnExternalStorage() {
        for (paths in listOf(listOf("a.txt", "A.txt"), listOf("é.txt", "e\u0301.txt"), listOf("same", "same"))) {
            assertTrue(BtPathGuard.sanitizeAll(paths) is BtPathGuard.AllResult.Reject)
        }
    }

    @Test fun rejectsFileDirectoryConflictsInEitherOrder() {
        assertTrue(BtPathGuard.sanitizeAll(listOf("folder", "folder/video.mp4")) is BtPathGuard.AllResult.Reject)
        assertTrue(BtPathGuard.sanitizeAll(listOf("folder/video.mp4", "Folder")) is BtPathGuard.AllResult.Reject)
    }

    @Test fun appliesUtf8ByteAndDepthBounds() {
        assertTrue(BtPathGuard.sanitize("片".repeat(86)) is BtPathGuard.Result.Reject)
        assertTrue(BtPathGuard.sanitize((1..33).joinToString("/") { "a" }) is BtPathGuard.Result.Reject)
        assertTrue(BtPathGuard.sanitizeAll(List(20001) { "file$it" }) is BtPathGuard.AllResult.Reject)
    }

    @Test fun checksCanonicalContainment() {
        val root = temporary.newFolder("owned")
        assertTrue(BtPathGuard.isInside(root, File(root, "sub/file")))
        assertFalse(BtPathGuard.isInside(root, File(root, "../outside")))
    }

    @Test fun detectsDanglingSymlinkBeforeCheckingExistence() {
        val root = temporary.newFolder("links")
        val link = File(root, "dangling").toPath()
        val created = runCatching { Files.createSymbolicLink(link, File(root, "missing").toPath()) }.isSuccess
        assumeTrue("Host must support symbolic links", created)
        assertTrue(BtPathGuard.hasSymlinkOnPath(root, "dangling/file"))
    }

    @Test fun rejectsAFileWhereParentDirectoryIsRequired() {
        val root = temporary.newFolder("parent")
        File(root, "not-a-directory").writeText("existing")
        assertTrue(BtPathGuard.hasSymlinkOnPath(root, "not-a-directory/file"))
    }
}
