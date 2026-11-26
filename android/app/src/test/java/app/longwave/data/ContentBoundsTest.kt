package app.longwave.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentBoundsTest {
    private val green = 0xFF2E9E40.toInt()
    private val red = 0xFFE03020.toInt()
    private val clear = 0x00000000

    /** w x h image of [bg] with a [fg] rectangle from (l,t) to (r,b) inclusive. */
    private fun img(w: Int, h: Int, bg: Int, fg: Int, l: Int, t: Int, r: Int, b: Int) =
        IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in l..r && y in t..b) fg else bg }

    @Test fun findsLogoOnSolidBackground() {
        val px = img(100, 50, green, red, 40, 20, 59, 29)
        assertArrayEquals(intArrayOf(40, 20, 59, 29), contentBounds(px, 100, 50, green))
    }

    @Test fun findsLogoOnTransparentBackground() {
        val px = img(100, 100, clear, red, 10, 30, 89, 69)
        assertArrayEquals(intArrayOf(10, 30, 89, 69), contentBounds(px, 100, 100, null))
    }

    @Test fun ignoresSlightNoiseInTheBackground() {
        val px = img(100, 50, green, red, 40, 20, 59, 29)
        px[0] = 0xFF2F9F41.toInt()  // JPEG-style near-miss in a corner
        assertArrayEquals(intArrayOf(40, 20, 59, 29), contentBounds(px, 100, 50, green))
    }

    @Test fun emptyImageHasNoContent() {
        assertNull(contentBounds(IntArray(100) { green }, 10, 10, green))
    }
}
