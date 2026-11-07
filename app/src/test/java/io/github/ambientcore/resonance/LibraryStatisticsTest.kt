package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test

class LibraryStatisticsTest {
    private val first = Song("one", "One", "Artist", "Album", genre = "Rock; Hard Rock; rock",
        duration = 30_000, size = 1024, fileName = "one.FLAC")
    private val second = first.copy(uri = "two", title = "Two", genre = "rock", duration = 60_000, size = 2048, fileName = "two.mp3")
    private val third = Song("three", "Three", "Other", "Album", fileName = "three.mp3", duration = -1, size = -1)

    @Test fun totalsUseCachedLibraryDataAndAlbumArtistIdentity() {
        val stats = libraryStatistics(listOf(first, second, third))
        assertEquals(3, stats.tracks)
        assertEquals(2, stats.albums)
        assertEquals(2, stats.artists)
        assertEquals(90_000L, stats.duration)
        assertEquals(3072L, stats.bytes)
        assertEquals(listOf(StatCount("MP3", 2), StatCount("FLAC", 1)), stats.formats)
    }

    @Test fun genreCountsRespectSemicolonMembershipWithoutDoubleCounting() {
        val stats = libraryStatistics(listOf(first, second, third))
        assertEquals(StatCount("Rock", 2), stats.genres.first())
        assertEquals(1, stats.genres.single { it.label == "Hard Rock" }.count)
        assertEquals(1, stats.genres.single { it.label == "Unknown genre" }.count)
        assertEquals(4, stats.genres.sumOf { it.count })
    }

    @Test fun ratingsAndFavoritesIgnoreIdsOutsideTheLibrary() {
        val stats = libraryStatistics(listOf(first, second, third),
            favorites = setOf(first.uri, "missing"), ratings = mapOf(first.uri to 5, second.uri to 3, "missing" to 1))
        assertEquals(1, stats.favorites)
        assertEquals(2, stats.ratedTracks)
        assertEquals(4.0, stats.averageRating!!, .0001)
        assertEquals(1, stats.ratings.single { it.label == "5 stars" }.count)
        assertEquals(1, stats.ratings.single { it.label == "3 stars" }.count)
        assertEquals(1, stats.ratings.single { it.label == "Unrated" }.count)
        assertEquals(3, stats.ratings.sumOf { it.count })
    }

    @Test fun emptyLibraryHasNoInventedListeningStatistics() {
        val stats = libraryStatistics(emptyList())
        assertEquals(0, stats.tracks)
        assertEquals(0L, stats.duration)
        assertEquals(0L, stats.bytes)
        assertNull(stats.averageRating)
        assertTrue(stats.formats.isEmpty())
        assertTrue(stats.genres.isEmpty())
        assertTrue(stats.ratings.all { it.count == 0 })
    }

    @Test fun missingFormatIsExplicitlyReported() {
        assertEquals(listOf(StatCount("Unknown", 1)), libraryStatistics(listOf(Song("unknown", "Unknown"))).formats)
    }

    @Test fun fileSizesUseConsistentBinaryUnits() {
        assertEquals("0 B", audioSizeLabel(-1))
        assertEquals("1023 B", audioSizeLabel(1023))
        assertEquals("1.0 KiB", audioSizeLabel(1024))
        assertEquals("1.0 MiB", audioSizeLabel(1L shl 20))
        assertEquals("1.00 GiB", audioSizeLabel(1L shl 30))
    }
}
