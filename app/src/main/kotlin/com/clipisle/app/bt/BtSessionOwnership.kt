package com.clipisle.app.bt

/** The foreground download owner and an interactive metadata preview can share a session. */
internal object BtSessionOwnership {
 fun shouldPause(hasDownloadOwner: Boolean, hasMetadataPreview: Boolean): Boolean =
  !hasDownloadOwner && !hasMetadataPreview
}
