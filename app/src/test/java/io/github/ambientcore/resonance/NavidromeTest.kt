package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

class NavidromeTest {
    @Test fun accountValidationRequiresExplicitHttpAndCleanUrl() {
        for (url in listOf("file:///music", "https://user:secret@nas", "https://nas/?password=secret", "https://nas/#fragment", "not a URL")) {
            assertThrows(IllegalArgumentException::class.java) { serverAccount(url, "user", "secret", false) }
        }
        assertThrows(IllegalArgumentException::class.java) { serverAccount("http://100.64.1.2:4533", "user", "secret", false) }
        val account = serverAccount("http://100.64.1.2:4533/", " user ", "secret", true)
        assertEquals("http://100.64.1.2:4533", account.url)
        assertEquals("user", account.username)
        assertFalse(account.toString().contains("secret"))
        assertFalse(account.toString().contains("100.64"))
    }

    @Test fun authenticationUsesSaltedTokenAndPreservesBasePath() {
        val account = serverAccount("https://nas.example/navidrome", "a + b", "secret", false)
        val uri = URI(NavidromeClient(account).url("stream", mapOf("id" to "track / + Ø", "format" to "raw")))
        val params = uri.rawQuery.split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("/navidrome/rest/stream.view", uri.path)
        assertEquals("a + b", params["u"])
        assertEquals("track / + Ø", params["id"])
        assertEquals("raw", params["format"])
        assertEquals("murrmurr", params["c"])
        assertFalse(uri.toString().contains("secret"))
        assertFalse("p" in params)
        val token = MessageDigest.getInstance("MD5").digest(("secret" + params["s"]).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(token, params["t"])
        assertThrows(IllegalArgumentException::class.java) { NavidromeClient(account).url("ping", mapOf("p" to "x", "u" to "other")) }
    }

    @Test fun stableTrackIdentityDoesNotContainCredentialsOrMixAccounts() {
        val account = serverAccount("https://nas.example", "user", "first", false)
        val same = serverAccount("https://nas.example", "user", "changed", false)
        val other = serverAccount("https://nas.example", "other", "first", false)
        val id = "track / + Ø"
        val uri = serverTrackUri(account.key, id)
        assertEquals(uri, serverTrackUri(same.key, id))
        assertNotEquals(uri, serverTrackUri(other.key, id))
        assertEquals("/$id", URI(uri).path)
        assertFalse(uri.contains("first"))
        assertFalse(uri.contains("user"))
    }

    @Test fun historiesRemainSourceAndAccountSpecific() {
        val local = "content://music/1"
        val server = serverTrackUri(sha256("one"), "1")
        val other = serverTrackUri(sha256("two"), "1")
        val history = ListeningHistory().record(ListenEvent(local, "Local", "", "", 1))
            .record(ListenEvent(server, "Server", "", "", 2)).record(ListenEvent(other, "Other", "", "", 3))
        assertEquals(setOf(local), history.forSource(LibrarySource.LOCAL, "").counts.keys)
        assertEquals(setOf(server), history.forSource(LibrarySource.SERVER, sha256("one")).counts.keys)
        assertEquals("Server", history.forSource(LibrarySource.SERVER, sha256("one")).recent.single().title)
        assertEquals(setOf(local, other), history.withoutSource(LibrarySource.SERVER, sha256("one")).counts.keys)
        assertEquals(setOf(server, other), history.withoutSource(LibrarySource.LOCAL, "").counts.keys)
    }
}
