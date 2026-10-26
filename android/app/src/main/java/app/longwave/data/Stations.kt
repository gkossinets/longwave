package app.longwave.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class Station(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val homepage: String = "",
    val description: String = "",
    val genre: String = "",
    val country: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val favorite: Boolean = false,
    val source: String = "",
) {
    val isHls: Boolean get() = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /** One line of technical details for list rows: "Finland · MP3 256k". */
    val details: String
        get() = listOfNotNull(
            country.ifBlank { null },
            listOfNotNull(codec.ifBlank { null }, if (bitrate > 0) "${bitrate}k" else null)
                .joinToString(" ").ifBlank { null },
        ).joinToString(" · ")
}

@Serializable
private data class CuratedFile(val source: String = "", val stations: List<Station>)

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

/** "My stations" live in a JSON file in app storage; the curated list ships as an asset. */
class StationRepository(private val context: Context) {
    private val file = File(context.filesDir, "my_stations.json")
    private val listSer = ListSerializer(Station.serializer())

    fun loadMine(): List<Station> =
        if (file.exists()) runCatching { AppJson.decodeFromString(listSer, file.readText()) }.getOrElse { defaults() }
        else defaults()

    fun saveMine(list: List<Station>) = file.writeText(AppJson.encodeToString(listSer, list))

    fun loadCurated(): List<Station> =
        context.assets.open("curated_stations.json").bufferedReader().use {
            AppJson.decodeFromString(CuratedFile.serializer(), it.readText()).stations
        }

    private fun defaults() = listOf(
        Station(
            id = "radio-helsinki", name = "Radio Helsinki", url = "https://stream.radiohelsinki.fi/",
            homepage = "https://www.radiohelsinki.fi/", description = "Helsinki, 98.5 MHz",
            country = "Finland", codec = "MP3", bitrate = 256, source = "manual",
        )
    )
}

// ---------------- Radio Browser (radio-browser.info, open community directory) ----------------

@Serializable
private data class RbStation(
    val stationuuid: String = "",
    val name: String = "",
    val url: String = "",
    val url_resolved: String = "",
    val homepage: String = "",
    val tags: String = "",
    val country: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val votes: Int = 0,
)

@Serializable
data class RbCountry(val name: String = "", val iso_3166_1: String = "", val stationcount: Int = 0)

object RadioBrowser {
    private val servers = listOf(
        "https://all.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://de1.api.radio-browser.info",
    )

    /** Blocking; call from Dispatchers.IO. Empty query + empty filters = top voted stations. */
    fun search(query: String, countryCode: String = "", tag: String = "", limit: Int = 80): List<Station> {
        val params = buildList {
            if (query.isNotBlank()) add("name=" + enc(query.trim()))
            if (countryCode.isNotBlank()) add("countrycode=" + enc(countryCode.trim().uppercase()))
            if (tag.isNotBlank()) add("tag=" + enc(tag.trim().lowercase()))
            add("order=votes"); add("reverse=true"); add("hidebroken=true"); add("limit=$limit")
        }.joinToString("&")
        val body = getAny("/json/stations/search?$params")
        val list = AppJson.decodeFromString(ListSerializer(RbStation.serializer()), body)
        return list.filter { it.url_resolved.isNotBlank() || it.url.isNotBlank() }.map {
            Station(
                id = "rb:" + it.stationuuid,
                name = it.name.trim(),
                url = it.url_resolved.ifBlank { it.url },
                homepage = it.homepage,
                description = it.tags.split(',').filter { t -> t.isNotBlank() }.take(6).joinToString(", "),
                genre = it.tags.split(',').firstOrNull { t -> t.isNotBlank() }.orEmpty(),
                country = it.country,
                codec = it.codec,
                bitrate = it.bitrate,
                source = "radio-browser",
            )
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getAny(path: String): String {
        var last: Exception? = null
        for (s in servers) {
            try {
                return Http.getText(s + path)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Radio Browser unavailable")
    }
}

// ---------------- Small HTTP helpers and stream checking ----------------

object Http {
    const val UA = "Longwave/0.1 (Android; personal use)"

    fun getText(url: String, timeoutMs: Int = 10_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode} from $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

object StreamCheck {
    data class Result(val ok: Boolean, val message: String, val stationName: String = "")

    /**
     * Fetches the start of a stream URL and reports whether it looks like audio.
     * Follows redirects manually (HttpURLConnection won't cross http/https) and
     * accepts .pls/.m3u/.m3u8 playlists, which Sonos resolves itself.
     */
    fun check(url: String): Result {
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            return Result(false, "URL must start with http:// or https://")
        var current = url
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", Http.UA)
                setRequestProperty("Icy-MetaData", "1")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return Result(false, "Redirect without a location")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return Result(false, "The server answered HTTP $code")
                val type = (c.contentType ?: "").lowercase()
                val icyName = c.getHeaderField("icy-name").orEmpty().trim()
                val path = current.substringBefore('?').lowercase()
                return when {
                    type.startsWith("audio/") && !type.contains("mpegurl") && !type.contains("scpls") ->
                        Result(true, "Audio stream ($type)", icyName)
                    type.contains("ogg") || type.contains("aac") -> Result(true, "Audio stream ($type)", icyName)
                    type.contains("mpegurl") || type.contains("scpls") ||
                        path.endsWith(".m3u8") || path.endsWith(".pls") || path.endsWith(".m3u") ->
                        Result(true, "Playlist; Sonos will open it", icyName)
                    type.startsWith("text/html") ->
                        Result(false, "That's a web page, not a stream. Look for a 'listen' or 'stream' link on it.")
                    else -> Result(false, "Unrecognised content type: ${type.ifBlank { "none" }}")
                }
            } catch (e: java.io.IOException) {
                // Old Shoutcast v1 servers reply "ICY 200 OK", which HttpURLConnection rejects.
                if ((e.message ?: "").contains("ICY", ignoreCase = true)) return Result(true, "Shoutcast stream")
                return Result(false, e.message ?: "Network error")
            } finally {
                c.disconnect()
            }
        }
        return Result(false, "Too many redirects")
    }
}
