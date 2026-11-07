package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test

class MusicTest {
    private val first = Song("content://music/1", "First Light", "The Artist", "One", genre = "Ambient", track = 2,
        fileName = "01.flac", path = "/storage/emulated/0/Music/01.flac", duration = 65_000)
    private val second = Song("content://music/2", "Second", "Other Artist", "Two", track = 1, fileName = "02.mp3")

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
