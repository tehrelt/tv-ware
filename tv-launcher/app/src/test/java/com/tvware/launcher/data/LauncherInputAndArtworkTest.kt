package com.tvware.launcher.data

import com.tvware.launcher.ui.components.MenuConfirmInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherInputAndArtworkTest {
    @Test
    fun menuIgnoresTheHoldThatOpenedIt() {
        val input = MenuConfirmInput()
        assertTrue(input.consume(keyCode = 23, down = true, repeatCount = 2))
        assertTrue(input.consume(keyCode = 23, down = false, repeatCount = 0))
        assertFalse(input.consume(keyCode = 23, down = true, repeatCount = 0))
        assertTrue(input.consume(keyCode = 23, down = true, repeatCount = 1))
        assertFalse(input.consume(keyCode = 23, down = false, repeatCount = 0))
        assertTrue(input.consume(keyCode = 23, down = false, repeatCount = 0))
    }

    @Test
    fun albumUriTakesPriorityOverGenericArtBitmap() {
        assertEquals(
            MediaArtworkSource(null, "album-uri"),
            selectMediaArtwork(null, "album-uri", "generic-bitmap", "generic-uri"),
        )
    }

    @Test
    fun genericTrackArtRemainsAvailableWithoutAlbumArt() {
        assertEquals(
            MediaArtworkSource("track-bitmap", "track-uri"),
            selectMediaArtwork(null, null, "track-bitmap", "track-uri"),
        )
    }
}
