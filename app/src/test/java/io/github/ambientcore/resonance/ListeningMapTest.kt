package io.github.ambientcore.resonance

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ListeningMapTest {
    private val first = Song("one", "First", "Performer", "Album", albumArtist = "Main artist", track = 2)
    private val second = first.copy(uri = "two", title = "Second", artist = "performer", track = 1)

    @Test fun artistMapDoesNotNeedGenreTagsAndDeduplicatesCredits() {
        val sameCredit = first.copy(uri = "same", artist = "  MAIN   artist ", albumArtist = "Main artist")
        val page = buildMapPage(listOf(first, second, sameCredit), MapRequest())
        assertEquals(2, page.matches)
        assertEquals(3, page.nodes.single { it.id == "main artist" }.tracks)
        assertEquals(2, page.nodes.single { it.id == "performer" }.tracks)
        assertEquals(emptyList<String>(), listOf(first, second).flatMap { it.genres })
    }

    @Test fun missingArtistFallsBackToAlbumCreditThenUnknownWithoutDroppingTracks() {
        val credited = first.copy(artist = "Unknown artist")
        val unknown = first.copy(uri = "unknown", artist = "", albumArtist = "<unknown>")
        val page = buildMapPage(listOf(credited, unknown), MapRequest())
        assertEquals(setOf("Main artist", "Unknown artist"), page.nodes.map { it.name }.toSet())
        assertEquals(listOf(unknown), mapSongs(listOf(credited, unknown), "unknown artist"))
    }

    @Test fun albumsAndPlaybackKeepCompilationsCompleteAndTrackOrderCorrect() {
        val guest = first.copy(uri = "guest", artist = "Guest", track = 3)
        val songs = listOf(first, guest, second)
        val page = buildMapPage(songs, MapRequest(artist = "performer"))
        assertEquals(1, page.matches)
        assertEquals(2, page.nodes.single().tracks)
        assertEquals(listOf(second, first), mapSongs(songs, "performer"))
        assertEquals(listOf(second, first, guest), mapSongs(songs, "performer", first.albumKey))
        val other = first.copy(uri = "server", serverKey = "other", serverTrackId = "id", serverAlbumId = "album")
        assertEquals(2, buildMapPage(songs + other, MapRequest(artist = "performer")).matches)
    }

    @Test fun queryAndPaginationAreStableBoundedAndReachEveryArtist() {
        val songs = (0 until 43).map { first.copy(uri = "$it", artist = "Artist %02d".format(it),
            albumArtist = "Artist %02d".format(it)) }
        val pages = (0..5).map { buildMapPage(songs, MapRequest(page = it)) }
        assertTrue(pages.all { it.nodes.size <= 8 && it.matches == 43 && it.pageCount == 6 })
        assertEquals(43, pages.flatMap { it.nodes }.map { it.id }.distinct().size)
        assertEquals(pages.last(), buildMapPage(songs.reversed(), MapRequest(page = 999)))
        assertEquals(listOf("Artist 07"), buildMapPage(songs, MapRequest(query = "artist 07")).nodes.map { it.name })
        assertEquals(0, buildMapPage(songs, MapRequest(query = "missing")).matches)
        assertEquals(0, buildMapPage(emptyList(), MapRequest()).page)
    }

    @Test fun cacheKeepsOnlyTheLatestPageAndInvalidatesOnLibraryChange() = runBlocking {
        val cache = ListeningMapCache()
        val songs = listOf(first, second)
        val page = cache.page(songs, MapRequest())
        assertSame(page, cache.page(songs, MapRequest()))
        assertEquals(1, cache.builds)
        cache.page(songs, MapRequest(artist = "performer"))
        assertEquals(2, cache.builds)
        cache.page(songs.toList(), MapRequest(artist = "performer"))
        assertEquals(3, cache.builds)
    }

    @Test fun largeLibraryUsesBoundedPagesAndSupportsCancellation() {
        val songs = (0 until 50_000).map { first.copy(uri = "$it", artist = "Artist ${it % 5000}",
            albumArtist = "Artist ${it % 5000}", album = "Album $it") }
        val start = System.nanoTime()
        val page = buildMapPage(songs, MapRequest(pageSize = MAP_MAX_NODES))
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertEquals(5000, page.matches)
        assertEquals(MAP_MAX_NODES, page.nodes.size)
        assertTrue("50k metadata rows took $elapsed ms", elapsed < 2000)
        var checked = 0
        try {
            buildMapPage(songs, MapRequest()) {
                if (++checked == 3) throw kotlinx.coroutines.CancellationException("Closed map")
            }
            fail("Background aggregation must stop on cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(3, checked)
        }
    }
}
