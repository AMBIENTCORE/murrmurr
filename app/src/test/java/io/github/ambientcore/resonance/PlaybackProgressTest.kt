package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressTest {
    private val sample = PlayingUi(connected = true, playing = true, position = 1000, duration = 10_000,
        positionSampleTimeMs = 5000)

    @Test fun advancesBetweenPositionSamplesWithoutHalfSecondSteps() {
        assertEquals(1000L, playbackPositionAt(sample, 5000))
        assertEquals(1016L, playbackPositionAt(sample, 5016))
        assertEquals(1032L, playbackPositionAt(sample, 5032))
        assertEquals(1499L, playbackPositionAt(sample, 5499))
        assertEquals(1240L, playbackPositionAt(sample.copy(playbackSpeed = 1.5f), 5160))
    }

    @Test fun pauseBufferingAndDisconnectStopExtrapolation() {
        assertEquals(1000L, playbackPositionAt(sample.copy(playing = false), 6000))
        assertEquals(1000L, playbackPositionAt(sample.copy(buffering = true), 6000))
        assertEquals(1000L, playbackPositionAt(sample.copy(connected = false), 6000))
    }

    @Test fun seekingUsesTheNewSnapshotImmediately() {
        assertEquals(8000L, playbackPositionAt(sample.copy(position = 8000, positionSampleTimeMs = 5500), 5500))
        assertEquals(2000L, playbackPositionAt(sample.copy(position = 2000, positionSampleTimeMs = 5500), 5500))
        assertEquals(0L, playbackPositionAt(sample.copy(position = 0, positionSampleTimeMs = 5500), 5500))
    }

    @Test fun positionStaysWithinDurationAndDoesNotRunBeforeTheSampleTime() {
        assertEquals(10_000L, playbackPositionAt(sample, 100_000))
        assertEquals(1000L, playbackPositionAt(sample, 4000))
        assertEquals(0L, playbackPositionAt(sample.copy(duration = 0), 6000))
        assertEquals(0L, playbackPositionAt(sample.copy(position = -100, playing = false), 6000))
    }
}
