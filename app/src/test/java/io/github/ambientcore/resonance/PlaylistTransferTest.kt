package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test

class PlaylistTransferTest {
    private val ambient = Song("content://music/1", "Ambient", fileName = "01 - Intro.flac",
        path = "/storage/F85C-5692/Collection/Ambient/2020/Artist A/Album A/01 - Intro.flac")
    private val rock = Song("content://music/2", "Rock", fileName = "01 - Intro.flac",
        path = "/storage/F85C-5692/Collection/Rock/2020/Artist B/Album B/01 - Intro.flac")

    @Test fun windowsAndAndroidRootsMatchByLongestUniqueFolderSuffix() {
        val index = PlaylistPathIndex(listOf(ambient, rock))
        assertEquals(ambient.uri, index.match("D:\\Music\\Collection\\Ambient\\2020\\Artist A\\Album A\\01 - Intro.flac").uri)
        assertEquals(rock.uri, index.match("E:\\Archive\\Collection\\Rock\\2020\\Artist B\\Album B\\01 - Intro.flac").uri)
    }

    @Test fun ambiguousFilenamesAreNotGuessed() {
        val index = PlaylistPathIndex(listOf(ambient, rock))
        assertTrue(index.match("01 - Intro.flac").ambiguous)
        assertNull(index.match("01 - Intro.flac").uri)
        assertEquals(ambient.uri, index.match(ambient.path).uri)
        assertEquals(rock.uri, index.match(rock.uri).uri)
    }

    @Test fun fileUrisDecodeSpacesWithoutChangingPlusSigns() {
        val song = ambient.copy(path = "/storage/F85C-5692/Collection/A + B/one.flac", fileName = "one.flac")
        assertEquals(song.uri, PlaylistPathIndex(listOf(song)).match("file:///D:/Music/Collection/A%20+%20B/one.flac").uri)
    }

    @Test fun playlistsKeepNamesOrderDuplicatesAndEmptyLists() {
        val inputs = listOf(PlaylistInput("one", "First", listOf(rock.path, ambient.path, rock.path, "missing.flac")),
            PlaylistInput("two", "Empty", emptyList()))
        val prepared = preparePlaylists(inputs, listOf(ambient, rock))
        assertEquals("First", prepared[0].name)
        assertEquals(listOf(rock.uri, ambient.uri, rock.uri), prepared[0].songs)
        assertEquals(1, prepared[0].missing)
        assertEquals(0, prepared[0].ambiguous)
        assertEquals(0, prepared[1].total)
        assertTrue(prepared[1].songs.isEmpty())
    }

    @Test fun unknownRemoteAndContentUrisDoNotResolveByFilename() {
        val index = PlaylistPathIndex(listOf(ambient))
        assertNull(index.match("https://example.invalid/01 - Intro.flac").uri)
        assertNull(index.match("content://unknown/01 - Intro.flac").uri)
        assertEquals(ambient.uri, index.match("01 - Intro.flac").uri)
    }
}
