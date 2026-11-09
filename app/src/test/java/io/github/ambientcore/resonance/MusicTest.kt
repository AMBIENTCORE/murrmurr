package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test

class MusicTest {
    private val first = Song("content://music/1", "First Light", "The Artist", "One", genre = "Ambient", track = 2,
        fileName = "01.flac", path = "/storage/emulated/0/Music/01.flac", duration = 65_000)
    private val second = Song("content://music/2", "Second", "Other Artist", "Two", track = 1, fileName = "02.mp3")

    @Test fun searchCategoriesRetainCompleteAlbumsAndMatchAcrossTrackMetadata() {
        val hit = first.copy(title = "Needle", year = "1998", folder = "Music/One",
            path = "/storage/emulated/0/Music/One/01.flac")
        val sibling = hit.copy(uri = "content://music/3", title = "Other track", track = 1)
        val groups = searchGroups(listOf(hit, sibling, second), "needle", LibraryCategory.ALBUMS, SearchSort.ALPHABETICAL)
        assertEquals(1, groups.size)
        assertEquals(setOf(hit.uri, sibling.uri), groups.single().songs.map { it.uri }.toSet())
        assertEquals(1998, groups.single().year())
        assertEquals(1, searchGroups(listOf(hit, sibling, second), "ambient", LibraryCategory.GENRES, SearchSort.ALPHABETICAL).size)
        assertEquals("The Artist", searchGroups(listOf(hit, sibling), "needle", LibraryCategory.ARTISTS,
            SearchSort.ALPHABETICAL).single().name)
        assertEquals("One", searchGroups(listOf(hit, sibling), "needle", LibraryCategory.FOLDERS,
            SearchSort.ALPHABETICAL).single().name)
    }

    @Test fun searchYearSortsKeepMissingYearsLastAndUseAlphabeticalTies() {
        val alpha = first.copy(title = "Alpha", album = "Alpha", year = "2001")
        val beta = first.copy(uri = "b", title = "Beta", album = "Beta", year = "1998-01-01")
        val unknown = first.copy(uri = "c", title = "Unknown", album = "Unknown", year = "0")
        val tie = alpha.copy(uri = "d", title = "Another", album = "Another")
        val songs = listOf(unknown, beta, tie, alpha)
        assertEquals(listOf(alpha, tie, beta, unknown), sortedSongs(songs, SongSort.YEAR_NEWEST))
        assertEquals(listOf(beta, alpha, tie, unknown), sortedSongs(songs, SongSort.YEAR_OLDEST))
        assertEquals(listOf("Alpha", "Another", "Beta", "Unknown"),
            searchGroups(songs, "", LibraryCategory.ALBUMS, SearchSort.YEAR_NEWEST).map { it.name })
        assertEquals(listOf("Beta", "Alpha", "Another", "Unknown"),
            searchGroups(songs, "", LibraryCategory.ALBUMS, SearchSort.YEAR_OLDEST).map { it.name })
        assertTrue(filterSongs(songs, "1998").contains(beta))
    }

    @Test fun formatLabelOnlyShowsFileTypeAndAvailableBitrate() {
        assertEquals("FLAC / 960 kbps", trackFormatLabel(first.copy(bitrate = 960_000)))
        assertEquals("FLAC", trackFormatLabel(first))
        assertEquals("MP3 / 320 kbps", trackFormatLabel(second, "MPEG", 320_000))
        assertEquals("AAC / 256 kbps", trackFormatLabel(first.copy(fileName = ""), "AAC", 256_000))
        assertEquals("", trackFormatLabel(first.copy(fileName = ""), bitrate = -1))
    }

    @Test fun yearTagsAcceptDatesAndFallBackFromMissingIndexYears() {
        assertEquals("2026", taggedYear(null, "0", "2026"))
        assertEquals("2024", taggedYear("", "0000", "2024-03-27"))
        assertEquals("1999", taggedYear(" 1999/12/31 "))
        assertEquals("2001", taggedYear("unknown", "2001-01-01T00:00:00Z"))
        assertEquals("1980", taggedYear("1980", "2026"))
        assertEquals("", taggedYear(null, "0", "0000", "Unknown", "999", "12345"))
    }

    @Test fun artistDestinationsIncludePerformedTracksAndAlbumCredits() {
        val performed = first.copy(artist = "Performer", albumArtist = "Various artists")
        val guest = second.copy(artist = "performer", albumArtist = "Main band")
        val credited = first.copy(uri = "content://music/3", artist = "Guest", albumArtist = "Performer")
        val unrelated = first.copy(uri = "content://music/4", artist = "Performer 2", albumArtist = "Other")
        val target = songDestinations(performed).single { it.label == "Go to artist" }
        assertEquals(setOf(performed.uri, guest.uri, credited.uri),
            songDestinationGroup(performed, target, listOf(performed, guest, credited, unrelated))?.songs?.map { it.uri }?.toSet())
        assertEquals("Various artists", songDestinations(performed).single { it.label == "Go to album artist" }.value)
    }

    @Test fun albumDestinationsDoNotMixSameNamedAlbumsOrServerAlbumIds() {
        val otherArtist = first.copy(uri = "content://music/3", albumArtist = "Another artist")
        val target = songDestinations(first).single { it.category == LibraryCategory.ALBUMS }
        assertEquals(listOf(first), songDestinationGroup(first, target, listOf(otherArtist, first))?.songs)
        val server = first.copy(uri = "server:1", serverKey = "account", serverTrackId = "1", serverAlbumId = "album-a")
        val otherAlbum = server.copy(uri = "server:2", serverTrackId = "2", serverAlbumId = "album-b")
        val serverTarget = songDestinations(server).single { it.category == LibraryCategory.ALBUMS }
        assertEquals(listOf(server), songDestinationGroup(server, serverTarget, listOf(server, otherAlbum))?.songs)
    }

    @Test fun genreDestinationsSplitTagsAndResolveCaseInsensitively() {
        val multi = first.copy(genre = "Rock; Electronic; rock")
        val targets = songDestinations(multi).filter { it.category == LibraryCategory.GENRES }
        assertEquals(listOf("Rock", "Electronic"), targets.map { it.value })
        val rock = second.copy(genre = "rock")
        assertEquals(setOf(multi.uri, rock.uri),
            songDestinationGroup(multi, targets.first(), listOf(multi, rock))?.songs?.map { it.uri }?.toSet())
        assertNull(songDestinationGroup(multi, targets.last(), listOf(rock)))
    }

    @Test fun folderDestinationsKeepStorageRootsSeparate() {
        val sibling = second.copy(path = "/storage/emulated/0/Music/02.mp3")
        val sd = first.copy(uri = "content://music/3", path = "/storage/F85C-5692/Music/01.flac")
        val target = songDestinations(first).single { it.category == LibraryCategory.FOLDERS }
        assertEquals(setOf(first.uri, sibling.uri),
            songDestinationGroup(first, target, listOf(first, sibling, sd))?.songs?.map { it.uri }?.toSet())
        assertTrue(songDestinations(second).none { it.category == LibraryCategory.FOLDERS })
    }

    @Test fun durationFormattingHandlesHoursAndNegativeValues() {
        assertEquals("0:00", durationLabel(-1))
        assertEquals("1:05", durationLabel(65_000))
        assertEquals("1:01:01", durationLabel(3_661_000))
    }

    @Test fun searchCombinesTermsAcrossMetadataAndIgnoresCase() {
        assertTrue(first.matches("FIRST ambient"))
        assertTrue(first.matches("artist 01.flac"))
        assertTrue(first.matches(""))
        assertFalse(first.matches("First missing"))
    }

    @Test fun importSupportsMusicBeeWindowsPathsCommentsAndBom() {
        val result = matchM3u("\uFEFF#EXTM3U\n#EXTINF:65,First\nD:\\Music\\01.flac\n02.mp3\nmissing.mp3", listOf(first, second))
        assertEquals(listOf(first.uri, second.uri), result.songs)
        assertEquals(1, result.unmatched)
    }

    @Test fun importDoesNotGuessWhenDuplicateFilenamesExist() {
        val duplicate = first.copy(uri = "content://music/3", path = "/storage/AAAA-BBBB/01.flac")
        val result = matchM3u("01.flac\n${first.path}", listOf(first, duplicate))
        assertEquals(listOf(first.uri), result.songs)
        assertEquals(1, result.unmatched)
    }

    @Test fun exportRoundTripsAndKeepsPlaylistDuplicates() {
        val result = matchM3u(exportM3u(listOf(first, second, first)), listOf(first, second))
        assertEquals(listOf(first.uri, second.uri, first.uri), result.songs)
        assertEquals(0, result.unmatched)
    }

    @Test fun albumSortRespectsDiscAndTrackNumbers() {
        val discTwo = first.copy(title = "Disc two", disc = 2, track = 1)
        val discOne = first.copy(title = "Disc one", disc = 1, track = 9)
        assertEquals(listOf(discOne, discTwo), sortedSongs(listOf(discTwo, discOne), SongSort.ALBUM))
    }

    @Test fun smartPlaylistsUpdateFromRatingsWithoutChangingMembershipLists() {
        val playlist = MusicPlaylist("smart", "Highly rated", emptyList(), RatingRule(stars = 4))
        assertEquals(listOf(first), playlistSongs(playlist, listOf(first, second), mapOf(first.uri to 5)))
        assertEquals(listOf(second), playlistSongs(playlist, listOf(first, second), mapOf(first.uri to 2, second.uri to 4)))
        assertTrue(playlist.songs.isEmpty())
    }

    @Test fun ratingRulesHandleExactAtMostAndUnrated() {
        assertTrue(RatingRule(RatingComparison.EXACTLY, 0).matches(0))
        assertFalse(RatingRule(RatingComparison.EXACTLY, 0).matches(1))
        assertTrue(RatingRule(RatingComparison.AT_MOST, 2).matches(0))
        assertFalse(RatingRule(RatingComparison.AT_MOST, 2).matches(3))
        assertTrue(RatingRule(RatingComparison.AT_LEAST, 4).matches(5))
    }

    @Test fun normalPlaylistsKeepTheirExplicitOrderAndDuplicates() {
        val playlist = MusicPlaylist("normal", "Ordered", listOf(second.uri, first.uri, second.uri, "missing"))
        assertEquals(listOf(second, first, second), playlistSongs(playlist, listOf(first, second), mapOf(first.uri to 5)))
    }

    @Test fun smartPlaylistSortsByRatingThenTitle() {
        val playlist = MusicPlaylist("smart", "All", emptyList(), RatingRule(stars = 0))
        assertEquals(listOf(second, first), playlistSongs(playlist, listOf(first, second), mapOf(second.uri to 5)))
        assertEquals(listOf(first, second), playlistSongs(playlist, listOf(second, first), emptyMap()))
    }

    @Test fun incrementalCheckpointsInvalidateAfterIndexRebuildOrGenerationReset() {
        assertTrue(validCheckpoint(IndexCheckpoint("one", 10), "one", 10))
        assertTrue(validCheckpoint(IndexCheckpoint("one", 10), "one", 11))
        assertFalse(validCheckpoint(IndexCheckpoint("one", 10), "two", 11))
        assertFalse(validCheckpoint(IndexCheckpoint("one", 10), "one", 9))
        assertFalse(validCheckpoint(null, "one", 10))
    }

    @Test fun legacySongsCanBeAssignedToTheirStorageVolume() {
        assertEquals("external_primary", volumeForSong(first))
        assertEquals("f85c-5692", volumeForSong(second.copy(path = "/storage/F85C-5692/Music/test.mp3")))
        assertEquals("external_primary", volumeForSong(second.copy(mediaVolume = "external_primary")))
        assertEquals("", volumeForSong(second))
    }

    @Test fun semicolonsSplitGenresAndRemoveEmptyOrDuplicateValues() {
        val song = first.copy(genre = " Rock ; hard rock;; rock ; ")
        assertEquals(listOf("Rock", "hard rock"), song.genres)
        assertTrue(first.copy(genre = " ; ").genres.isEmpty())
        assertEquals(listOf("Jazz/Funk"), first.copy(genre = "Jazz/Funk").genres)
    }

    @Test fun eachGenreContainsTheTrackWithoutDuplicates() {
        val multi = first.copy(genre = "Rock; Hard Rock; rock")
        val rock = second.copy(genre = "rock")
        val groups = libraryGroups(listOf(multi, rock), LibraryCategory.GENRES)
        assertEquals(setOf("Hard Rock", "Rock"), groups.map { it.name }.toSet())
        assertEquals(listOf(multi), groups.single { it.id == "hard rock" }.songs)
        assertEquals(listOf(multi, rock), groups.single { it.id == "rock" }.songs)
    }

    @Test fun categorySearchOnlyUsesCategoryFields() {
        val songs = listOf(first.copy(album = "Sunrise", genre = "Rock"), second.copy(album = "Night", genre = "Jazz"))
        val albums = libraryGroups(songs, LibraryCategory.ALBUMS)
        assertTrue(filterGroups(albums, "Rock").isEmpty())
        assertEquals("Sunrise", filterGroups(albums, "sun ARTIST").single().name)
        assertEquals(listOf(songs[0]), filterSongs(songs, "first ROCK"))
        assertSame(songs, filterSongs(songs, "  "))
        assertTrue(filterGroups(libraryGroups(songs, LibraryCategory.ARTISTS), "Sunrise").isEmpty())
    }

    @Test fun folderTreeKeepsVolumesSeparateAndCountsSubtrees() {
        val internal = first.copy(path = "/storage/emulated/0/Music/Artist/Album/first.flac")
        val card = second.copy(path = "/storage/F85C-5692/Music/Artist/Album/second.mp3")
        val roots = folderTree(listOf(internal, card))
        assertEquals(2, roots.size)
        assertEquals(8, folderIds(roots).size)
        assertEquals(listOf(internal), roots.single { it.name == "Internal storage" }.songs)
        assertEquals(listOf(card), roots.single { it.name.startsWith("SD card") }.songs)
        assertEquals(2, folderRows(roots, emptySet()).size)
        assertEquals(8, folderRows(roots, folderIds(roots)).size)
        assertEquals(3, folderRows(roots, folderIds(roots)).maxOf { it.depth })
    }

    @Test fun folderSearchIncludesAncestorsAndMatchingDescendants() {
        val a = first.copy(path = "/storage/F85C-5692/Music/Alpha/One/first.flac")
        val b = second.copy(path = "/storage/F85C-5692/Music/Beta/Two/second.mp3")
        val roots = folderTree(listOf(a, b))
        val filtered = filterFolderTree(roots, "beta")
        val rows = folderRows(filtered, folderIds(filtered))
        assertEquals(listOf("SD card (F85C-5692)", "Music", "Beta", "Two"), rows.map { it.folder.name })
        assertTrue(filterFolderTree(roots, "missing").isEmpty())
        assertEquals(listOf(a, b), filterFolderTree(roots, "Music").single().songs)
    }

    @Test fun folderTreeSupportsCachedEntriesWithoutAbsolutePaths() {
        val song = first.copy(path = "", folder = "Music/Unindexed", mediaVolume = "f85c-5692")
        val roots = folderTree(listOf(song))
        assertEquals(listOf("SD card (F85C-5692)", "Music", "Unindexed"),
            folderRows(roots, folderIds(roots)).map { it.folder.name })
    }

    @Test fun recentAlbumOrderUsesTheNewestCachedTrackDateInEachAlbum() {
        val oldTrack = first.copy(modified = 10)
        val newlyAddedTrack = first.copy(uri = "new", title = "New track", modified = 100)
        val otherAlbum = second.copy(modified = 50)
        val albums = recentlyAddedAlbums(listOf(oldTrack, otherAlbum, newlyAddedTrack))
        assertEquals(listOf(first.album, second.album), albums.map { it.first().album })
        assertEquals(setOf(oldTrack.uri, newlyAddedTrack.uri), albums.first().map { it.uri }.toSet())
        assertTrue(recentlyAddedAlbums(emptyList()).isEmpty())
    }

    @Test fun recentAlbumsKeepTheTwelveNewestWithStableAlphabeticalTies() {
        val songs = (0 until 15).map { index ->
            first.copy(uri = "$index", album = "Album %02d".format(java.util.Locale.ROOT, index), modified = index.toLong())
        }
        val albums = recentlyAddedAlbums(songs)
        assertEquals(12, albums.size)
        assertEquals("Album 14", albums.first().first().album)
        assertEquals("Album 03", albums.last().first().album)
        val ties = recentlyAddedAlbums(listOf(second.copy(album = "B", modified = 10), first.copy(album = "A", modified = 10)))
        assertEquals(listOf("A", "B"), ties.map { it.first().album })
    }

    @Test fun albumTrackGroupsKeepAlbumsDistinctAndOrderDiscsAndTracks() {
        val trackTwo = first.copy(uri = "two", album = "Shared album", albumArtist = "Artist A", disc = 1, track = 2)
        val trackOne = trackTwo.copy(uri = "one", track = 1)
        val discTwo = trackTwo.copy(uri = "disc-two", disc = 2, track = 1)
        val otherArtist = trackOne.copy(uri = "other", albumArtist = "Artist B")
        val groups = albumTrackGroups(listOf(discTwo, trackTwo, otherArtist, trackOne))
        assertEquals(2, groups.size)
        assertEquals(listOf("one", "two", "disc-two"), groups.single { it.subtitle == "Artist A" }.songs.map { it.uri })
        assertEquals(listOf("other"), groups.single { it.subtitle == "Artist B" }.songs.map { it.uri })
        assertTrue(albumTrackGroups(emptyList()).isEmpty())
    }

    @Test fun outputExplanationsDistinguishSourceResolutionFromDecodedPcm() {
        assertTrue(explainAudioPrecision(0, true, 16, false).contains("16-bit output is normal"))
        assertTrue(explainAudioPrecision(16, false, 16, false).contains("no bit-depth reduction"))
        assertTrue(explainAudioPrecision(24, false, 16, false).contains("Source is 24-bit"))
        assertTrue(explainAudioPrecision(24, false, 32, true).contains("preserve"))
        assertTrue(explainAudioPrecision(0, false, 16, false).contains("unavailable"))
        assertTrue(explainAudioPrecision(32, false, 32, true).contains("not guaranteed"))
    }
}
