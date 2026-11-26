package com.tvware.launcher.data

import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

@Immutable
data class MediaSessionInfo(
    val sessionKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val album: String,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val playbackSpeed: Float,
    val positionUpdateTimeMs: Long,
    val canPlayPause: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
    val artwork: ImageBitmap? = null,
    val artworkKey: String? = null,
    val mediaId: String? = null,
)

@Immutable
data class MediaMonitorState(
    val hasAccess: Boolean = false,
    val activeMedia: MediaSessionInfo? = null,
)

@Immutable
data class MediaScreenVisibilityState(
    val isVisible: Boolean = false,
    val userDismissed: Boolean = false,
    val lastResumedAtMs: Long = 0L,
    val lastPlayingAtMs: Long = 0L,
    val wasPlaying: Boolean = false,
    val userInteractedOnMedia: Boolean = false,
)

fun isPlaybackActiveState(state: Int): Boolean = when (state) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_SKIPPING_TO_NEXT,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_CONNECTING -> true
    else -> false
}

fun isPlaybackPausedState(state: Int): Boolean = state == PlaybackState.STATE_PAUSED

fun selectPreferredSessionIndex(states: List<Int>, currentActiveIndex: Int?): Int? {
    val playingIndex = states.indexOfFirst(::isPlaybackActiveState)
    if (playingIndex >= 0) return playingIndex

    if (currentActiveIndex != null &&
        currentActiveIndex in states.indices &&
        isPlaybackPausedState(states[currentActiveIndex])
    ) {
        return currentActiveIndex
    }

    val pausedIndex = states.indexOfFirst(::isPlaybackPausedState)
    return if (pausedIndex >= 0) pausedIndex else null
}

fun computePlaybackPositionMs(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    lastUpdateElapsedMs: Long,
    nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    isPlaying: Boolean,
): Long {
    val safePos = positionMs.coerceAtLeast(0L)
    val raw = if (isPlaying && lastUpdateElapsedMs > 0L && nowElapsedMs > lastUpdateElapsedMs) {
        val elapsed = nowElapsedMs - lastUpdateElapsedMs
        safePos + (elapsed * playbackSpeed.coerceAtLeast(0f)).toLong()
    } else {
        safePos
    }
    return if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
}

fun formatMediaDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

object MediaScreenReducer {
    const val PLAYBACK_GAP_RESET_MS = 1_500L
    const val QUICK_PAUSE_AFTER_RESUME_MS = 1_200L

    fun onMediaUpdated(
        state: MediaScreenVisibilityState,
        hasSession: Boolean,
        isPlaying: Boolean,
        isPaused: Boolean,
        enabled: Boolean,
        nowMs: Long,
    ): MediaScreenVisibilityState {
        if (!enabled) {
            return state.copy(
                isVisible = false,
                wasPlaying = isPlaying,
                lastPlayingAtMs = if (isPlaying || state.wasPlaying) nowMs else state.lastPlayingAtMs,
            )
        }

        if (!hasSession || (!isPlaying && !isPaused)) {
            return state.copy(
                isVisible = false,
                userDismissed = false,
                wasPlaying = false,
                lastPlayingAtMs = 0L,
                userInteractedOnMedia = false,
            )
        }

        if (isPlaying) {
            val stoppedLongEnough = !state.wasPlaying &&
                (state.lastPlayingAtMs == 0L || nowMs - state.lastPlayingAtMs >= PLAYBACK_GAP_RESET_MS)
            return if (stoppedLongEnough) {
                state.copy(
                    isVisible = true,
                    userDismissed = false,
                    wasPlaying = true,
                    lastPlayingAtMs = nowMs,
                    userInteractedOnMedia = false,
                )
            } else {
                state.copy(
                    isVisible = !state.userDismissed,
                    wasPlaying = true,
                    lastPlayingAtMs = nowMs,
                )
            }
        }

        val updatedLastPlayingAtMs = if (state.wasPlaying) nowMs else state.lastPlayingAtMs
        val quickPauseAfterResume = state.isVisible &&
            !state.userInteractedOnMedia &&
            state.lastResumedAtMs > 0L &&
            (nowMs - state.lastResumedAtMs) <= QUICK_PAUSE_AFTER_RESUME_MS

        return if (quickPauseAfterResume) {
            state.copy(
                isVisible = false,
                userDismissed = true,
                wasPlaying = false,
                lastPlayingAtMs = updatedLastPlayingAtMs,
            )
        } else {
            state.copy(
                wasPlaying = false,
                lastPlayingAtMs = updatedLastPlayingAtMs,
            )
        }
    }

    fun onLauncherResumed(
        state: MediaScreenVisibilityState,
        hasSession: Boolean,
        isPlaying: Boolean,
        enabled: Boolean,
        nowMs: Long,
    ): MediaScreenVisibilityState {
        if (!enabled) {
            return state.copy(isVisible = false, lastResumedAtMs = nowMs)
        }
        return if (hasSession && isPlaying) {
            state.copy(
                isVisible = true,
                userDismissed = false,
                lastResumedAtMs = nowMs,
                lastPlayingAtMs = nowMs,
                wasPlaying = true,
                userInteractedOnMedia = false,
            )
        } else {
            state.copy(
                isVisible = false,
                lastResumedAtMs = nowMs,
                userInteractedOnMedia = false,
            )
        }
    }

    fun onDismissToApps(state: MediaScreenVisibilityState): MediaScreenVisibilityState =
        state.copy(isVisible = false, userDismissed = true)

    fun onOpenMediaScreen(
        state: MediaScreenVisibilityState,
        hasSession: Boolean,
        isPlaying: Boolean,
        isPaused: Boolean,
        enabled: Boolean,
    ): MediaScreenVisibilityState {
        if (!enabled || !hasSession || (!isPlaying && !isPaused)) return state
        return state.copy(
            isVisible = true,
            userDismissed = false,
            userInteractedOnMedia = true,
        )
    }

    fun onUserMediaControl(state: MediaScreenVisibilityState): MediaScreenVisibilityState =
        state.copy(userInteractedOnMedia = true)
}
