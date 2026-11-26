package app.longwave.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BackfillFaviconsTest {
    private val defaults = listOf(
        Station(id = "mad-radio", name = "Mad Radio", url = "https://m/", favicon = "https://m/logo.png"),
        Station(id = "wfmu", name = "WFMU", url = "https://w/", favicon = "https://w/logo.gif"),
    )

    @Test fun fillsBlankFaviconOfASavedDefault() {
        val saved = listOf(Station(id = "mad-radio", name = "Mad Radio", url = "https://m/"))
        assertEquals("https://m/logo.png", backfillFavicons(saved, defaults).single().favicon)
    }

    @Test fun keepsAFaviconAlreadySet() {
        val saved = listOf(Station(id = "wfmu", name = "WFMU", url = "https://w/", favicon = "https://mine/x.png"))
        assertEquals("https://mine/x.png", backfillFavicons(saved, defaults).single().favicon)
    }

    @Test fun keepsUserEditsAndOrder() {
        val saved = listOf(
            Station(id = "user-1", name = "Mine", url = "https://u/"),
            Station(id = "wfmu", name = "My WFMU", url = "https://other/"),
        )
        val out = backfillFavicons(saved, defaults)
        assertEquals(listOf("user-1", "wfmu"), out.map { it.id })
        assertEquals("", out[0].favicon)
        assertEquals("My WFMU", out[1].name)
        assertEquals("https://other/", out[1].url)
        // Pointed at a different stream, so it is no longer WFMU: don't give it WFMU's logo.
        assertEquals("", out[1].favicon)
    }

    @Test fun fillsARenamedDefaultThatStillPlaysTheSameStream() {
        val saved = listOf(Station(id = "wfmu", name = "My WFMU", url = "https://w/"))
        assertEquals("https://w/logo.gif", backfillFavicons(saved, defaults).single().favicon)
    }

    @Test fun doesNotBringBackRemovedDefaults() {
        val saved = listOf(Station(id = "user-1", name = "Mine", url = "https://u/"))
        assertEquals(saved, backfillFavicons(saved, defaults))
    }
}
