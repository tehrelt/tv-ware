package com.tvware.launcher.data

import com.tvware.launcher.ui.screens.idleDriftOffset
import com.tvware.launcher.ui.screens.shouldShowDreamMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleDreamTest {
    @Test
    fun driftOffsetStaysBoundedAndMovesAcrossSteps() {
        val points = (0 until 12).map(::idleDriftOffset)
        points.forEach { (dx, dy) ->
            assertTrue(dx in -20f..20f)
            assertTrue(dy in -20f..20f)
        }
        assertNotEquals(points[0], points[3])
        assertNotEquals(points[3], points[6])
    }

    @Test
    fun dreamMediaShowsOnlyForPlayingOrPausedSession() {
        val base = MediaSessionInfo(
            sessionKey = "s1",
            packageName = "com.spotify.tv.android",
            appLabel = "Spotify",
            title = "Track",
            artist = "Artist",
            album = "Album",
            isPlaying = false,
            isPaused = false,
            durationMs = 180_000L,
            positionMs = 10_000L,
            playbackSpeed = 0f,
            positionUpdateTimeMs = 0L,
            canPlayPause = true,
            canSkipPrevious = true,
            canSkipNext = true,
        )
        assertFalse(shouldShowDreamMedia(null))
        assertFalse(shouldShowDreamMedia(base))
        assertTrue(shouldShowDreamMedia(base.copy(isPlaying = true)))
        assertTrue(shouldShowDreamMedia(base.copy(isPaused = true)))
    }
}
