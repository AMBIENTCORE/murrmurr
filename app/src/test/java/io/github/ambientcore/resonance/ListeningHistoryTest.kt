package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryTest {
    @Test fun longTracksCountAtThirtySecondsOnlyOncePerPass() {
        val clock = ListeningClock()
        clock.start(0)
        assertFalse(clock.sample(0, true, 180_000))
        assertFalse(clock.sample(29_999, true, 180_000))
        assertTrue(clock.sample(30_000, true, 180_000))
        assertFalse(clock.sample(90_000, true, 180_000))
    }

    @Test fun shortTracksCountAfterHalfTheirDuration() {
        val clock = ListeningClock()
        clock.start(100)
        assertFalse(clock.sample(100, true, 8000))
        assertFalse(clock.sample(4099, true, 8000))
        assertTrue(clock.sample(4100, true, 8000))
    }

    @Test fun pausesAndPreparedButUnplayedTimeDoNotCount() {
        val clock = ListeningClock()
        clock.start(0)
        assertFalse(clock.sample(100_000, false, 60_000))
        assertFalse(clock.sample(100_000, true, 60_000))
        assertFalse(clock.sample(110_000, false, 60_000))
        assertFalse(clock.sample(500_000, false, 60_000))
        assertFalse(clock.sample(500_000, true, 60_000))
        assertFalse(clock.sample(519_999, true, 60_000))
        assertTrue(clock.sample(520_000, true, 60_000))
    }

    @Test fun seekingCannotAddPlayheadTimeToTheListeningClock() {
        val clock = ListeningClock()
        clock.start(0)
        clock.sample(0, true, 240_000)
        assertFalse(clock.sample(100, false, 240_000))
        assertFalse(clock.sample(200, true, 240_000))
        assertFalse(clock.sample(300, false, 240_000))
    }

    @Test fun newPassesResetQualificationAndUnknownDurationUsesThirtySeconds() {
        val clock = ListeningClock()
        clock.start(0)
        clock.sample(0, true, 0)
        assertTrue(clock.sample(30_000, true, 0))
        clock.start(30_000)
        clock.sample(30_000, true, 0)
        assertFalse(clock.sample(59_999, true, 0))
        assertTrue(clock.sample(60_000, true, 0))
    }

    @Test fun backwardClockSamplesCannotDoubleCountTime() {
        val clock = ListeningClock()
        clock.start(1000)
        clock.sample(1000, true, 60_000)
        assertFalse(clock.sample(0, true, 60_000))
        assertFalse(clock.sample(30_999, true, 60_000))
        assertTrue(clock.sample(31_000, true, 60_000))
    }

    @Test fun historyKeepsLifetimeCountsButBoundsRecentEvents() {
        var history = ListeningHistory()
        repeat(1002) { index -> history = history.record(ListenEvent("one", "Track", "Artist", "Album", index + 1L)) }
        assertEquals(1002L, history.counts["one"])
        assertEquals(1002L, history.lastPlayed["one"])
        assertEquals(1000, history.recent.size)
        assertEquals(1002L, history.recent.first().playedAt)
        assertEquals(3L, history.recent.last().playedAt)
    }

    @Test fun importedRatingsNeverOverrideManualRatingsOrClears() {
        val first = Song("one", "One", modified = 10, size = 100)
        val second = Song("two", "Two", modified = 10, size = 100)
        val third = Song("three", "Three", modified = 10, size = 100)
        val data = LibraryData(songs = listOf(first, second, third), ratings = mapOf("one" to 2),
            ratingOverrides = setOf("one", "two"))
        val result = RatingImportResult(mapOf("one" to 5, "two" to 5, "three" to 4),
            data.songs.associate { it.uri to it.ratingStamp() }, 3, 0, 0, 0, 0, false, emptyList())
        val merged = mergeImportedRatings(data, result)
        assertEquals(mapOf("one" to 2, "three" to 4), merged.ratings)
        assertFalse(merged.ratings.containsKey("two"))
        assertEquals(data.ratingOverrides, merged.ratingOverrides)
    }

    @Test fun staleTagReadsAndRemovedTracksCannotOverwriteCurrentData() {
        val song = Song("one", "One", modified = 20, size = 100)
        val data = LibraryData(songs = listOf(song), ratings = mapOf("one" to 4), ratingOverrides = emptySet())
        val stale = RatingImportResult(mapOf("one" to 1, "removed" to 5),
            mapOf("one" to RatingStamp(10, 100), "removed" to RatingStamp(10, 100)),
            2, 0, 0, 0, 0, false, emptyList())
        assertEquals(data, mergeImportedRatings(data, stale))
    }

    @Test fun removedFileTagsClearOnlyPreviouslyImportedRatings() {
        val song = Song("one", "One", modified = 20, size = 100)
        val data = LibraryData(songs = listOf(song), ratings = mapOf("one" to 4), ratingOverrides = emptySet())
        val result = RatingImportResult(mapOf("one" to null), mapOf("one" to song.ratingStamp()),
            1, 0, 0, 0, 0, false, emptyList())
        assertTrue(mergeImportedRatings(data, result).ratings.isEmpty())
    }
}
