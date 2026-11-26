package app.longwave.data

import kotlin.random.Random

/**
 * Picks random stations for the Browse "random station" button. Works through the pool in a
 * shuffled order, so nothing comes up twice until everything has, and prefers stations not
 * yet in My stations. The pool can change between calls (the user changed the filter).
 */
class RandomPicker(private val random: Random = Random.Default) {
    private val seen = mutableSetOf<String>()
    private var last: String? = null

    fun next(pool: List<Station>, isSaved: (Station) -> Boolean): Station? {
        val candidates = pool.filterNot(isSaved).ifEmpty { pool }.distinctBy { it.url }
        if (candidates.isEmpty()) return null
        var fresh = candidates.filter { it.url !in seen }
        if (fresh.isEmpty()) {
            // Everything has come up: start a new round, but not with the station just shown.
            seen.removeAll(candidates.map { it.url }.toSet())
            fresh = candidates.filter { it.url != last }.ifEmpty { candidates }
        }
        val pick = fresh[random.nextInt(fresh.size)]
        seen += pick.url
        last = pick.url
        return pick
    }
}
