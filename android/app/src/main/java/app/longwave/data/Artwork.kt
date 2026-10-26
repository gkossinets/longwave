package app.longwave.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Square artwork for the media notification / lock screen and the control bar.
 * Uses the station's logo when a reasonably large one can be found (Radio Browser
 * favicon, then the homepage's touch icon / icons / og:image); otherwise draws a
 * coloured tile with the station name. Results are cached on disk for 30 days.
 */
object Artwork {
    private const val SIZE = 512
    private val mem = ConcurrentHashMap<String, ByteArray>()

    /** Blocking (may hit the network the first time); call off the main thread. */
    fun forStation(ctx: Context, st: Station): ByteArray? = try {
        val key = Integer.toHexString((st.name + "|" + st.url + "|" + st.favicon + "|" + st.homepage).hashCode())
        mem[key] ?: run {
            val dir = File(ctx.cacheDir, "artwork").apply { mkdirs() }
            val f = File(dir, "$key.png")
            val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600 * 1000
            val bytes = if (fresh) f.readBytes() else build(st).also { f.writeBytes(it) }
            mem[key] = bytes
            bytes
        }
    } catch (e: Exception) {
        null
    }

    private fun build(st: Station): ByteArray {
        val logo = runCatching { findLogo(st) }.getOrNull()
        val bmp = if (logo != null) composeLogo(logo, st.name) else composeName(st.name)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    // ---------------- finding a logo

    private fun findLogo(st: Station): Bitmap? {
        val candidates = mutableListOf<String>()
        if (st.favicon.startsWith("http")) candidates += st.favicon
        if (st.homepage.startsWith("http")) candidates += runCatching { pageImages(st.homepage) }.getOrDefault(emptyList())
        var best: Bitmap? = null
        for (c in candidates.distinct().take(8)) {
            val b = runCatching { download(c) }.getOrNull() ?: continue
            if (min(b.width, b.height) >= 192) return b
            if (best == null || b.width * b.height > best.width * best.height) best = b
        }
        return best?.takeIf { min(it.width, it.height) >= 96 }
    }

    private fun pageImages(home: String): List<String> {
        val html = Http.getText(home, 6000).take(400_000)
        val icons = mutableListOf<String>()
        val touch = mutableListOf<String>()
        val og = mutableListOf<String>()
        Regex("""<link[^>]+>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val rel = Regex("""rel=["']([^"']+)""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: return@forEach
            val href = Regex("""href=["']([^"']+)""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: return@forEach
            when {
                "apple-touch-icon" in rel -> touch += href
                "icon" in rel -> icons += href
            }
        }
        Regex("""<meta[^>]+>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            if (Regex("""(og:image|twitter:image)["']""", RegexOption.IGNORE_CASE).containsMatchIn(tag))
                Regex("""content=["']([^"']+)""", RegexOption.IGNORE_CASE).find(tag)?.let { og += it.groupValues[1] }
        }
        val ordered = touch + listOf("/apple-touch-icon.png") + icons.filterNot { it.endsWith(".svg") } + og
        return ordered.mapNotNull { runCatching { URL(URL(home), it.replace("&amp;", "&")).toString() }.getOrNull() }
    }

    private fun download(url: String): Bitmap? {
        var current = url
        repeat(4) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", Http.UA)
            }
            try {
                if (c.responseCode in 300..399) {
                    current = URL(URL(current), c.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (c.responseCode !in 200..299) return null
                val bytes = c.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) return null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    // ---------------- drawing

    private fun nameColor(name: String): Int {
        val hue = (abs(name.lowercase().hashCode()) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.45f))
    }

    /** Opaque, roughly square logos fill the tile; anything else sits centred on its own edge colour. */
    private fun composeLogo(logo: Bitmap, name: String): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val aspect = logo.width.toFloat() / logo.height
        val edge = edgeColor(logo)
        if (aspect in 0.85f..1.18f && edge != null && edgeIsOpaque(logo)) {
            val s = SIZE / min(logo.width, logo.height).toFloat()
            val w = logo.width * s; val h = logo.height * s
            c.drawBitmap(logo, null, RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2), paint)
        } else {
            c.drawColor(edge ?: nameColor(name))
            val box = SIZE * 0.7f
            val s = min(box / logo.width, box / logo.height)
            val w = logo.width * s; val h = logo.height * s
            c.drawBitmap(logo, null, RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2), paint)
        }
        return out
    }

    private fun edgePixels(b: Bitmap): List<Int> {
        val px = mutableListOf<Int>()
        val stepX = max(1, b.width / 32); val stepY = max(1, b.height / 32)
        for (x in 0 until b.width step stepX) { px += b.getPixel(x, 0); px += b.getPixel(x, b.height - 1) }
        for (y in 0 until b.height step stepY) { px += b.getPixel(0, y); px += b.getPixel(b.width - 1, y) }
        return px
    }

    private fun edgeIsOpaque(b: Bitmap) = edgePixels(b).all { Color.alpha(it) > 250 }

    private fun edgeColor(b: Bitmap): Int? {
        val opaque = edgePixels(b).filter { Color.alpha(it) > 200 }
        if (opaque.size < 8) return null
        val r = opaque.sumOf { Color.red(it) } / opaque.size
        val g = opaque.sumOf { Color.green(it) } / opaque.size
        val bl = opaque.sumOf { Color.blue(it) } / opaque.size
        return Color.rgb(r, g, bl)
    }

    /** Coloured tile: a gradient from the station's colour, radio-wave mark, and the name. */
    private fun composeName(name: String): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val base = nameColor(name)
        val hsv = FloatArray(3).also { Color.colorToHSV(base, it) }
        val dark = Color.HSVToColor(floatArrayOf((hsv[0] + 25) % 360, hsv[1], 0.22f))
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), base, dark, Shader.TileMode.CLAMP)
        })
        // radio-wave mark, top left
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND
            color = Color.argb(150, 255, 255, 255)
        }
        val cx = 110f; val cy = 120f
        c.drawCircle(cx, cy, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) })
        for (r in listOf(40f, 70f)) {
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 135f, 90f, false, stroke)
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -45f, 90f, false, stroke)
        }
        // name, bottom left, wrapped to at most three lines
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val maxW = SIZE - 2 * 48f
        var size = 92f
        var lines: List<String>
        while (true) {
            text.textSize = size
            lines = wrap(name, text, maxW)
            if (lines.size <= 3 && lines.all { text.measureText(it) <= maxW } || size < 36f) break
            size -= 6f
        }
        val lh = size * 1.12f
        var y = SIZE - 56f - lh * (lines.size - 1)
        for (l in lines.take(3)) { c.drawText(l, 48f, y, text); y += lh }
        return out
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        for (w in s.trim().split(Regex("\\s+"))) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(t) <= maxW || cur.isEmpty()) cur = t else { lines += cur; cur = w }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }
}
