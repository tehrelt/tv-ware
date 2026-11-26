package com.tvware.launcher.data

internal data class MediaTrackKey(
    val sessionKey: String,
    val mediaId: String?,
    val title: String,
    val artist: String,
    val album: String,
) {
    fun artworkKey(uri: String?, bitmapGeneration: Int?): String? {
        if (uri == null && bitmapGeneration == null) return null
        return listOf(sessionKey, mediaId, title, artist, album, uri, bitmapGeneration?.toString())
            .joinToString("") { part -> if (part == null) "-1:" else "${part.length}:$part" }
    }
}
