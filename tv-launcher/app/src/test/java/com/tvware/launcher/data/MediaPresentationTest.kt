package com.tvware.launcher.data

import com.tvware.launcher.ui.screens.mediaControlsHideAfterMs
import com.tvware.launcher.ui.screens.warpArtworkMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaPresentationTest {
    private val first = MediaTrackKey("session", "id-1", "Title", "Artist", "Album")
    private val second = first.copy(mediaId = "id-2")

    @Test
    fun sharedUriDoesNotReuseAnotherTracksArtwork() {
        assertNotEquals(first.artworkKey("content://art/current", null), second.artworkKey("content://art/current", null))
    }

    @Test
    fun replacementBitmapDoesNotReuseStaleArtwork() {
        assertNotEquals(first.artworkKey("content://art/current", 1), first.artworkKey("content://art/current", 2))
    }

    @Test
    fun missingArtworkHasNoCacheKey() {
        assertEquals(null, first.artworkKey(null, null))
    }

    @Test
    fun controlsOnlyTimeOutDuringVisiblePlayback() {
        assertEquals(5_000L, mediaControlsHideAfterMs(isVisible = true, isPlaying = true))
        assertEquals(null, mediaControlsHideAfterMs(isVisible = false, isPlaying = true))
        assertEquals(null, mediaControlsHideAfterMs(isVisible = true, isPlaying = false))
    }

    @Test
    fun warpMovesInteriorAndKeepsOuterEdgesFixed() {
        val start = FloatArray(18)
        val later = FloatArray(18)
        warpArtworkMesh(start, 2, 100f, 100f, 0f)
        warpArtworkMesh(later, 2, 100f, 100f, 0.25f)
        for (index in start.indices) {
            if (index !in 8..9) assertEquals(start[index], later[index], 0.001f)
        }
        assertNotEquals(start[8], later[8])
    }
}
