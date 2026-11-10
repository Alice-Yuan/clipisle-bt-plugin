package com.clipisle.btplugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewFormatTest {
 @Test fun formatsDecimalSizesIndependentlyOfDeviceLocale() {
  assertEquals("0 B", PreviewFormat.bytes(0))
  assertEquals("117 B", PreviewFormat.bytes(117))
  assertEquals("1.00 KB", PreviewFormat.bytes(1000))
  assertEquals("1.05 MB", PreviewFormat.bytes(1_048_909))
  assertEquals("1.00 GB", PreviewFormat.bytes(1_000_000_000))
  assertEquals("9.22 EB", PreviewFormat.bytes(Long.MAX_VALUE))
 }
 @Test(expected = IllegalArgumentException::class)
 fun rejectsNegativeSizes() { PreviewFormat.bytes(-1) }
 @Test fun stripsOnlyARealSharedTorrentRoot() {
  assertEquals("album/", PreviewFormat.commonRootPrefix("album", listOf("album/a.mp3", "album/disc/b.mp3")))
  assertEquals("", PreviewFormat.commonRootPrefix("album", listOf("album/a.mp3", "elsewhere/b.mp3")))
  assertEquals("", PreviewFormat.commonRootPrefix("album", listOf("album")))
  assertEquals("", PreviewFormat.commonRootPrefix("", listOf("album/a.mp3")))
 }
}
