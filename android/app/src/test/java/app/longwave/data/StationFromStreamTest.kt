package app.longwave.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationFromStreamTest {
    private val known = listOf(
        Station(id = "cur:1", name = "WFMU 91.1", url = "https://stream0.wfmu.org/freeform-128k",
            description = "Freeform since 1958", homepage = "https://wfmu.org/"),
    )

    @Test fun usesTheFullRecordWhenTheStreamIsKnown() {
        val st = stationFromStream("https://stream0.wfmu.org/freeform-128k", "WFMU", known)!!
        assertEquals("WFMU 91.1", st.name)
        assertEquals("Freeform since 1958", st.description)
    }

    @Test fun matchesAKnownStreamRegardlessOfSchemeAndTrailingSlash() {
        // The CLI drops the scheme before handing the URL to Sonos.
        val st = stationFromStream("stream0.wfmu.org/freeform-128k/", "WFMU", known)!!
        assertEquals("https://stream0.wfmu.org/freeform-128k", st.url)
    }

    @Test fun unknownStreamUsesTheTitleSonosReports() {
        val st = stationFromStream("https://radio.example.org/live.mp3", "Example FM", known)!!
        assertEquals("Example FM", st.name)
        assertEquals("https://radio.example.org/live.mp3", st.url)
        assertTrue(st.id.isNotBlank())
    }

    @Test fun schemelessUnknownStreamGetsHttp() {
        assertEquals("http://radio.example.org/live",
            stationFromStream("radio.example.org/live", "Example FM", known)!!.url)
    }

    @Test fun titleThatIsJustTheUrlFallsBackToTheHost() {
        assertEquals("radio.example.org",
            stationFromStream("https://radio.example.org/live", "https://radio.example.org/live", known)!!.name)
        assertEquals("radio.example.org", stationFromStream("https://radio.example.org/live", "", known)!!.name)
    }

    @Test fun nothingToSaveWithoutAStream() {
        assertNull(stationFromStream("", "Something", known))
    }

    @Test fun sameStreamOverHttpAndHttpsIsTheSameStation() {
        assertTrue(sameStream("http://stream0.wfmu.org/freeform-128k", "https://stream0.wfmu.org/freeform-128k/"))
        assertTrue(!sameStream("https://a.org/x", "https://a.org/y"))
    }
}
