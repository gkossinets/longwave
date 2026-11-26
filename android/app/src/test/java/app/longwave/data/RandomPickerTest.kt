package app.longwave.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomPickerTest {
    private fun st(n: Int) = Station(id = "s$n", name = "S$n", url = "https://s$n/")
    private val pool = (1..5).map(::st)
    private val nothingSaved: (Station) -> Boolean = { false }

    @Test fun emptyPoolGivesNothing() {
        assertNull(RandomPicker(Random(1)).next(emptyList(), nothingSaved))
    }

    @Test fun noRepeatsUntilEveryStationHasComeUp() {
        val p = RandomPicker(Random(7))
        val firstRound = (1..5).map { p.next(pool, nothingSaved)!!.url }
        assertEquals(pool.map { it.url }.toSet(), firstRound.toSet())
    }

    @Test fun startsOverWithoutRepeatingTheLastPick() {
        for (seed in 1..50) {
            val p = RandomPicker(Random(seed))
            val last = (1..5).map { p.next(pool, nothingSaved)!! }.last()
            assertNotEquals("seed $seed", last.url, p.next(pool, nothingSaved)!!.url)
        }
    }

    @Test fun skipsSavedStations() {
        val saved = setOf("https://s1/", "https://s2/")
        val p = RandomPicker(Random(3))
        repeat(20) { assertTrue(p.next(pool) { it.url in saved }!!.url !in saved) }
    }

    @Test fun usesSavedStationsWhenThatIsAllThereIs() {
        assertEquals("https://s1/", RandomPicker(Random(3)).next(listOf(st(1))) { true }!!.url)
    }

    @Test fun followsAChangedPool() {
        // e.g. the genre filter changed: picks must come from the new pool only.
        val p = RandomPicker(Random(5))
        p.next(pool, nothingSaved)
        val jazz = listOf(st(8), st(9))
        repeat(6) { assertTrue(p.next(jazz, nothingSaved)!!.url in jazz.map { it.url }) }
    }
}
