package io.github.ambientcore.resonance

import android.os.SystemClock
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember

internal fun playbackPositionAt(state: PlayingUi, nowMs: Long): Long {
    val elapsed = if (state.connected && state.playing && !state.buffering)
        (nowMs - state.positionSampleTimeMs).coerceAtLeast(0) else 0
    return (state.position + elapsed * state.playbackSpeed.toDouble()).toLong()
        .coerceIn(0, state.duration.coerceAtLeast(0))
}

@Composable
internal fun rememberPlaybackPosition(
    state: PlayingUi,
    timeMillis: () -> Long = SystemClock::elapsedRealtime,
): State<Long> {
    val position = remember(state.song?.uri) { mutableLongStateOf(state.position) }
    LaunchedEffect(state) {
        position.longValue = playbackPositionAt(state, timeMillis())
        if (state.connected && state.playing && !state.buffering) {
            while (true) {
                withInfiniteAnimationFrameNanos {
                    position.longValue = playbackPositionAt(state, timeMillis())
                }
            }
        }
    }
    return position
}
