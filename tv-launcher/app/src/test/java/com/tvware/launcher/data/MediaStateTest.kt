package com.tvware.launcher.data

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStateTest {

    @Test
    fun playbackStateClassification() {
        assertTrue(isPlaybackActiveState(PlaybackState.STATE_PLAYING))
        assertTrue(isPlaybackActiveState(PlaybackState.STATE_BUFFERING))
        assertTrue(isPlaybackActiveState(PlaybackState.STATE_CONNECTING))
        assertFalse(isPlaybackActiveState(PlaybackState.STATE_PAUSED))
        assertFalse(isPlaybackActiveState(PlaybackState.STATE_STOPPED))
        assertFalse(isPlaybackActiveState(PlaybackState.STATE_NONE))

        assertTrue(isPlaybackPausedState(PlaybackState.STATE_PAUSED))
        assertFalse(isPlaybackPausedState(PlaybackState.STATE_PLAYING))
    }

    @Test
    fun selectPreferredSessionPrefersPlayingThenCurrentPaused() {
        assertEquals(
            1,
            selectPreferredSessionIndex(
                listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING),
                currentActiveIndex = 0,
            ),
        )
        assertEquals(
            1,
            selectPreferredSessionIndex(
                listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PAUSED),
                currentActiveIndex = 1,
            ),
        )
        assertEquals(
            0,
            selectPreferredSessionIndex(
                listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED),
                currentActiveIndex = 1,
            ),
        )
        assertNull(
            selectPreferredSessionIndex(
                listOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE),
                currentActiveIndex = 0,
            ),
        )
        assertNull(selectPreferredSessionIndex(emptyList(), currentActiveIndex = null))
    }

    @Test
    fun computePlaybackPositionExtrapolatesAndClamps() {
        assertEquals(
            15_000L,
            computePlaybackPositionMs(
                positionMs = 10_000L,
                durationMs = 60_000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 6_000L,
                isPlaying = true,
            ),
        )
        assertEquals(
            60_000L,
            computePlaybackPositionMs(
                positionMs = 58_000L,
                durationMs = 60_000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 10_000L,
                isPlaying = true,
            ),
        )
        assertEquals(
            10_000L,
            computePlaybackPositionMs(
                positionMs = 10_000L,
                durationMs = 60_000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 6_000L,
                isPlaying = false,
            ),
        )
        assertEquals(
            0L,
            computePlaybackPositionMs(
                positionMs = -500L,
                durationMs = 60_000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedMs = 0L,
                nowElapsedMs = 6_000L,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun formatMediaDurationFormatsMinutesAndHours() {
        assertEquals("0:00", formatMediaDuration(-100L))
        assertEquals("0:09", formatMediaDuration(9_000L))
        assertEquals("3:45", formatMediaDuration(225_000L))
        assertEquals("1:02:03", formatMediaDuration(3_723_000L))
    }

    @Test
    fun reducerShowsOnResumeWhenPlayingAndHidesWhenPaused() {
        val playing = MediaScreenReducer.onLauncherResumed(
            state = MediaScreenVisibilityState(),
            hasSession = true,
            isPlaying = true,
            enabled = true,
            nowMs = 1_000L,
        )
        assertTrue(playing.isVisible)
        assertFalse(playing.userDismissed)

        val paused = MediaScreenReducer.onLauncherResumed(
            state = MediaScreenVisibilityState(),
            hasSession = true,
            isPlaying = false,
            enabled = true,
            nowMs = 2_000L,
        )
        assertFalse(paused.isVisible)
    }

    @Test
    fun reducerAutoDismissesQuickPauseAfterResumeUnlessUserInteracted() {
        val resumed = MediaScreenReducer.onLauncherResumed(
            state = MediaScreenVisibilityState(),
            hasSession = true,
            isPlaying = true,
            enabled = true,
            nowMs = 1_000L,
        )
        val quickPaused = MediaScreenReducer.onMediaUpdated(
            state = resumed,
            hasSession = true,
            isPlaying = false,
            isPaused = true,
            enabled = true,
            nowMs = 1_300L,
        )
        assertFalse(quickPaused.isVisible)

        val interacted = MediaScreenReducer.onUserMediaControl(resumed)
        val userPaused = MediaScreenReducer.onMediaUpdated(
            state = interacted,
            hasSession = true,
            isPlaying = false,
            isPaused = true,
            enabled = true,
            nowMs = 1_300L,
        )
        assertTrue(userPaused.isVisible)
    }

    @Test
    fun reducerStaysDismissedAcrossTrackChangesAndBriefFlicker() {
        val shown = MediaScreenReducer.onMediaUpdated(
            state = MediaScreenVisibilityState(),
            hasSession = true,
            isPlaying = true,
            isPaused = false,
            enabled = true,
            nowMs = 1_000L,
        )
        assertTrue(shown.isVisible)

        val dismissed = MediaScreenReducer.onDismissToApps(shown)
        assertFalse(dismissed.isVisible)
        assertTrue(dismissed.userDismissed)

        val trackChanged = MediaScreenReducer.onMediaUpdated(
            state = dismissed,
            hasSession = true,
            isPlaying = true,
            isPaused = false,
            enabled = true,
            nowMs = 5_000L,
        )
        assertFalse(trackChanged.isVisible)

        val briefPause = MediaScreenReducer.onMediaUpdated(
            state = trackChanged,
            hasSession = true,
            isPlaying = false,
            isPaused = true,
            enabled = true,
            nowMs = 180_000L,
        )
        val resumedQuickly = MediaScreenReducer.onMediaUpdated(
            state = briefPause,
            hasSession = true,
            isPlaying = true,
            isPaused = false,
            enabled = true,
            nowMs = 180_300L,
        )
        assertFalse(resumedQuickly.isVisible)

        val longPause = MediaScreenReducer.onMediaUpdated(
            state = resumedQuickly,
            hasSession = true,
            isPlaying = false,
            isPaused = true,
            enabled = true,
            nowMs = 181_000L,
        )
        val restartedLater = MediaScreenReducer.onMediaUpdated(
            state = longPause,
            hasSession = true,
            isPlaying = true,
            isPaused = false,
            enabled = true,
            nowMs = 183_000L,
        )
        assertTrue(restartedLater.isVisible)
    }

    @Test
    fun reducerHidesWhenSessionStopsAndRespectsManualOpenAndDisabledSetting() {
        val shown = MediaScreenReducer.onMediaUpdated(
            state = MediaScreenVisibilityState(),
            hasSession = true,
            isPlaying = true,
            isPaused = false,
            enabled = true,
            nowMs = 1_000L,
        )
        val stopped = MediaScreenReducer.onMediaUpdated(
            state = shown,
            hasSession = false,
            isPlaying = false,
            isPaused = false,
            enabled = true,
            nowMs = 2_000L,
        )
        assertFalse(stopped.isVisible)

        val dismissed = MediaScreenReducer.onDismissToApps(shown)
        val reopened = MediaScreenReducer.onOpenMediaScreen(
            state = dismissed,
            hasSession = true,
            isPlaying = true,
            isPaused = false,
            enabled = true,
        )
        assertTrue(reopened.isVisible)

        val disabled = MediaScreenReducer.onMediaUpdated(
            state = reopened,
            hasSession = true,
            isPlaying = true,
            isPaused = false,
            enabled = false,
            nowMs = 3_000L,
        )
        assertFalse(disabled.isVisible)
    }
}
