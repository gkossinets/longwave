package app.longwave.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkFreshnessTest {
    private val hour = 3600L * 1000
    private val day = 24 * hour

    @Test fun logoKeptForThirtyDays() {
        assertTrue(artworkIsFresh(ageMs = 29 * day, hasLogo = true))
        assertFalse(artworkIsFresh(ageMs = 31 * day, hasLogo = true))
    }

    // A name tile may only mean the lookup failed (offline, site down), so retry soon.
    @Test fun fallbackTileRetriedAfterADay() {
        assertTrue(artworkIsFresh(ageMs = 12 * hour, hasLogo = false))
        assertFalse(artworkIsFresh(ageMs = 25 * hour, hasLogo = false))
    }

    @Test fun clockMovedBackIsStale() {
        assertFalse(artworkIsFresh(ageMs = -1 * hour, hasLogo = true))
    }
}
