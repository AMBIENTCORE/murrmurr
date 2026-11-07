package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test

class BrowseModeTest {
    @Test fun allFiveModesHaveDistinctPresentations() {
        assertEquals(listOf("List", "List with icon", "Icons small", "Icons large", "Compact list"),
            BrowseMode.entries.map { it.label })
        assertEquals(3, BrowseMode.ICONS_SMALL.columns)
        assertEquals(2, BrowseMode.ICONS_LARGE.columns)
        assertTrue(BrowseMode.entries.filterNot { it.grid }.all { it.columns == 1 })
        assertFalse(BrowseMode.LIST.artwork)
        assertFalse(BrowseMode.COMPACT.artwork)
        assertTrue(BrowseMode.LIST_ICON.artwork)
        assertTrue(BrowseMode.ICONS_SMALL.artwork)
        assertTrue(BrowseMode.ICONS_LARGE.artwork)
    }

    @Test fun albumIconModesUseDenserColumnsWithoutChangingOtherGrids() {
        assertEquals(5, albumGridColumns(BrowseMode.ICONS_SMALL))
        assertEquals(3, albumGridColumns(BrowseMode.ICONS_LARGE))
        assertEquals(3, BrowseMode.ICONS_SMALL.columns)
        assertEquals(2, BrowseMode.ICONS_LARGE.columns)
        assertEquals(1, albumGridColumns(BrowseMode.COMPACT))
    }
}
