package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun versionsAreComparedNumericallyRatherThanAlphabetically() {
        assertTrue(isNewerVersion("v0.9.10", "0.9.9"))
        assertTrue(isNewerVersion("v0.10.0", "0.9.99"))
        assertTrue(isNewerVersion("1.0.0", "0.99.99"))
        assertTrue(isNewerVersion("v0.9.12", "0.9.11-qa"))
        assertFalse(isNewerVersion("v0.9.12", "0.9.12"))
        assertFalse(isNewerVersion("v0.9.9", "0.9.12"))
    }

    @Test fun invalidReleaseVersionsAreReported() {
        assertThrows(IllegalArgumentException::class.java) { isNewerVersion("latest", "0.9.12") }
        assertThrows(IllegalArgumentException::class.java) { isNewerVersion("v0.9", "0.9.12") }
    }
}
