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

    @Test fun connectionsUseActualSharedAlbumsAndNeverTheGenericLibrary() {
        val a = first.copy(artist = "A", albumArtist = "Compilation", album = "Shared record")
        val b = a.copy(uri = "b", artist = "B")
        val c = a.copy(uri = "c", artist = "C", album = "Different record")
        val page = buildMapPage(listOf(a, b, c), MapRequest(artist = "a", mode = MapMode.CONNECTIONS))
        assertEquals("A", page.center?.name)
        assertTrue(page.nodes.any { it.id == "b" })
        assertFalse(page.nodes.any { it.id == "c" || it.id == "a" })
        assertEquals(1, page.links.single { it.artist == "b" }.albums)
        assertEquals(listOf(a, b), mapConnectionSongs(listOf(a, b, c), "a", "b", ListeningHistory(), false))
        val isolated = c.copy(artist = "Isolated", albumArtist = "Isolated")
        assertTrue(buildMapPage(listOf(a, b, isolated),
            MapRequest(artist = "isolated", mode = MapMode.CONNECTIONS)).nodes.isEmpty())
    }

    @Test fun genreConnectionsAreExplicitlyOptionalAndExplainTagsNotSimilarity() {
        val a = first.copy(artist = "A", albumArtist = "A", genre = "Ambient; Dub")
        val b = second.copy(artist = "B", albumArtist = "B", genre = "ambient; Rock")
        val c = b.copy(uri = "c", title = "Other genre", genre = "Rock")
        val request = MapRequest(artist = "a", mode = MapMode.CONNECTIONS)
        assertTrue(buildMapPage(listOf(a, b, c), request).nodes.isEmpty())
        val page = buildMapPage(listOf(a, b, c), request.copy(genreLinks = true))
        assertEquals(listOf("b"), page.nodes.map { it.id })
        assertEquals(listOf("ambient"), page.links.single().genres)
        assertTrue(page.links.single().explanation.contains("not a claim of musical similarity"))
        assertEquals(listOf(a, b), mapConnectionSongs(listOf(a, b, c), "a", "b", ListeningHistory(), true))
    }

    @Test fun listeningLinksRequireTwoRealAdjacentPlaysInTheCurrentCatalogAndSession() {
        val a = first.copy(artist = "A", albumArtist = "A")
        val b = second.copy(artist = "B", albumArtist = "B")
        fun event(song: Song, time: Long) = ListenEvent(song.uri, song.title, song.artist, song.album, time)
        val request = MapRequest(artist = "a", mode = MapMode.CONNECTIONS)
        val once = ListeningHistory(recent = listOf(event(a, 1000), event(b, 2000)))
        assertTrue(buildMapPage(listOf(a, b), request, once).nodes.isEmpty())
        val twice = once.copy(recent = once.recent + event(a, 3000))
        val page = buildMapPage(listOf(a, b), request, twice)
        assertEquals(listOf("b"), page.nodes.map { it.id })
        assertEquals(2, page.links.single().adjacentPlays)
        assertEquals(listOf(a, b), mapConnectionSongs(listOf(a, b), "a", "b", twice, false))
        assertTrue(buildMapPage(listOf(a, b), request, once.copy(recent = listOf(
            event(a, 1000), event(b, MAP_SESSION_GAP_MS + 2000), event(a, 2 * MAP_SESSION_GAP_MS + 3000)
        ))).nodes.isEmpty())
        val missing = b.copy(uri = "deleted")
        assertTrue(buildMapPage(listOf(a, b), request, once.copy(recent = listOf(
            event(a, 1000), event(missing, 2000), event(b, 3000), event(missing, 4000), event(a, 5000)
        ))).nodes.isEmpty())
    }

    @Test fun discoveryBadgesReflectRecordedHistoryAndCacheInvalidatesWithEvidence() = runBlocking {
        val a = first.copy(artist = "A", albumArtist = "A")
        val b = second.copy(artist = "B", albumArtist = "B")
        val songs = listOf(a, b)
        val cache = ListeningMapCache()
        val request = MapRequest(artist = "a", mode = MapMode.CONNECTIONS)
        assertTrue(cache.page(songs, request).nodes.isEmpty())
        val history = ListeningHistory(counts = mapOf(b.uri to 2L), lastPlayed = mapOf(b.uri to 1000L),
            recent = listOf(ListenEvent(a.uri, "", "", "", 1000), ListenEvent(b.uri, "", "", "", 2000),
                ListenEvent(a.uri, "", "", "", 3000)))
        val linked = cache.page(songs, request, history)
        assertEquals(2, cache.builds)
        assertTrue(linked.nodes.single().played)
        assertEquals("Unheard 90+ days", mapDiscoveryLabel(linked.nodes.single(), 91L * 24 * 60 * 60_000))
        assertEquals("Unexplored here", mapDiscoveryLabel(linked.center!!))
        assertSame(linked, cache.page(songs, request, history))
    }

    @Test fun focusedConnectionsStayBoundedForLargeLibraries() {
        val songs = (0 until 50_000).map { first.copy(uri = "$it", artist = "Artist ${it % 5000}",
            albumArtist = "Anchor", album = "Album $it") }
        val page = buildMapPage(songs, MapRequest(artist = "anchor", pageSize = 7, mode = MapMode.CONNECTIONS))
        assertEquals(5000, page.matches)
        assertEquals(7, page.nodes.size)
        assertEquals(7, page.links.size)
        assertEquals("Anchor", page.center?.name)
        assertTrue(page.links.all { it.albums == 10 })
    }
}
