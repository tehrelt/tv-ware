package com.tvware.launcher.data

internal data class MediaArtworkSource<T>(val bitmap: T?, val uri: String?)

internal fun <T> selectMediaArtwork(
    albumBitmap: T?,
    albumUri: String?,
    trackBitmap: T?,
    trackUri: String?,
): MediaArtworkSource<T> = if (albumBitmap != null || albumUri != null) {
    MediaArtworkSource(albumBitmap, albumUri)
} else {
    MediaArtworkSource(trackBitmap, trackUri)
}
