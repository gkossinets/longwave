package app.sonosradio.sonos

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Local-network control of Sonos speakers over UPnP/SOAP (port 1400).
 * Same mechanism as the sonos-radio CLI: no Sonos cloud, no TuneIn.
 */

data class Speaker(
    val uuid: String,
    val name: String,
    val ip: String,
    val coordinatorUuid: String,
) {
    val isCoordinator: Boolean get() = uuid == coordinatorUuid
}

data class RoomStatus(val state: String, val title: String, val volume: Int)

data class SonosAlarm(
    val id: String,
    val startTime: String,      // "HH:MM:SS"
    val duration: String,       // "HH:MM:SS"
    val recurrence: String,     // ONCE, DAILY, WEEKDAYS, WEEKENDS, ON_0135 (0 = Sunday)
    val enabled: Boolean,
    val roomUuid: String,
    val programUri: String,
    val programMetaData: String,
    val playMode: String,
    val volume: Int,
    val includeLinkedZones: Boolean,
)

class SonosException(message: String) : IOException(message)

// ---------------------------------------------------------------- SOAP

object Soap {
    enum class Svc(val path: String, val urn: String) {
        AV("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
        RC("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
        GRC("/MediaRenderer/GroupRenderingControl/Control", "urn:schemas-upnp-org:service:GroupRenderingControl:1"),
        ALARM("/AlarmClock/Control", "urn:schemas-upnp-org:service:AlarmClock:1"),
        ZGT("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1"),
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** Blocking SOAP call. Returns the response's child elements as name -> text (entities decoded). */
    fun call(ip: String, svc: Svc, action: String, args: List<Pair<String, Any>> = emptyList(), timeoutMs: Int = 6000): Map<String, String> {
        val argXml = args.joinToString("") { (k, v) -> "<$k>${escape(v.toString())}</$k>" }
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="${svc.urn}">$argXml</u:$action></s:Body></s:Envelope>"""
        val c = URL("http://$ip:1400${svc.path}").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"${svc.urn}#$action\"")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val err = Regex("<errorCode>(\\d+)</errorCode>").find(text)?.groupValues?.get(1)
                throw SonosException(describeError(action, err ?: "HTTP $code"))
            }
            return parseResponse(text, action + "Response")
        } finally {
            c.disconnect()
        }
    }

    private fun describeError(action: String, code: String) = when (code) {
        "701" -> "$action: not possible right now (speaker busy or nothing to play)"
        "714" -> "$action: Sonos rejected the stream format"
        "800" -> "$action: Sonos rejected the request (800)"
        "801" -> "There's already an alarm in that room at that time"
        else -> "$action failed (Sonos error $code)"
    }

    private fun parseResponse(xml: String, responseTag: String): Map<String, String> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val out = mutableMapOf<String, String>()
        var inResponse = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                val name = p.name.substringAfter(':')
                if (name == responseTag) inResponse = true
                else if (inResponse) out[name] = p.nextText()
            }
        }
        return out
    }
}

// ---------------------------------------------------------------- discovery

class SonosDiscovery(private val context: Context) {
    private val prefs = context.getSharedPreferences("sonos", Context.MODE_PRIVATE)

    suspend fun discover(): List<Speaker> = withContext(Dispatchers.IO) {
        val cached = prefs.getStringSet("known_ips", emptySet()).orEmpty()
        val seed = cached.firstOrNull { isSonos(it, 600) } ?: ssdp() ?: subnetScan()
            ?: throw SonosException("No Sonos speakers found. Is the phone on the same Wi-Fi as the speakers?")
        val speakers = topology(seed)
        prefs.edit().putStringSet("known_ips", speakers.map { it.ip }.toSet()).apply()
        speakers
    }

    fun topology(ip: String): List<Speaker> {
        val state = Soap.call(ip, Soap.Svc.ZGT, "GetZoneGroupState")["ZoneGroupState"]
            ?: throw SonosException("Speaker at $ip returned no topology")
        val p = Xml.newPullParser()
        p.setInput(StringReader(state))
        val out = mutableListOf<Speaker>()
        var coordinator = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "ZoneGroup" -> coordinator = p.getAttributeValue(null, "Coordinator").orEmpty()
                "ZoneGroupMember" -> {
                    if (p.getAttributeValue(null, "Invisible") == "1") continue // second speaker of a stereo pair
                    val loc = p.getAttributeValue(null, "Location").orEmpty()
                    val host = runCatching { URL(loc).host }.getOrDefault("")
                    if (host.isBlank()) continue
                    out += Speaker(
                        uuid = p.getAttributeValue(null, "UUID").orEmpty(),
                        name = p.getAttributeValue(null, "ZoneName").orEmpty(),
                        ip = host,
                        coordinatorUuid = coordinator,
                    )
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun ssdp(timeoutMs: Long = 2500): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("sonosradio").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length)
                        if (text.contains("Sonos", true) || text.contains("ZonePlayer", true))
                            return pkt.address.hostAddress
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            lock.release()
        }
        return null
    }

    /** Fallback when multicast is blocked: probe port 1400 across the phone's /24. */
    private suspend fun subnetScan(): String? = coroutineScope {
        val base = localIpv4()?.substringBeforeLast('.') ?: return@coroutineScope null
        val found = CompletableDeferred<String?>()
        val jobs = (1..254).map { i ->
            launch(Dispatchers.IO) { val ip = "$base.$i"; if (!found.isCompleted && isSonos(ip, 400)) found.complete(ip) }
        }
        launch { jobs.joinAll(); found.complete(null) }
        val result = found.await()
        jobs.forEach { it.cancel() }
        result
    }

    private fun isSonos(ip: String, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, 1400), timeoutMs) }
        val c = URL("http://$ip:1400/xml/device_description.xml").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = 1500
        try { c.inputStream.bufferedReader().use { it.readText() }.contains("Sonos", true) } finally { c.disconnect() }
    } catch (_: Exception) {
        false
    }

    private fun localIpv4(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}

// ---------------------------------------------------------------- control

object SonosControl {
    const val CHIME_URI = "x-rincon-buzzer:0"
    private const val RADIO_PREFIX = "x-rincon-mp3radio://"

    /** Sonos plays an arbitrary stream (MP3/AAC, .pls/.m3u, HLS) when given this prefix. */
    fun streamUri(url: String) = RADIO_PREFIX + url.trim()

    fun streamUrlOf(programUri: String): String? =
        if (programUri.startsWith(RADIO_PREFIX)) programUri.removePrefix(RADIO_PREFIX) else null

    fun didl(title: String): String =
        "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"R:0/0/0\" parentID=\"R:0/0\" restricted=\"true\">" +
            "<dc:title>${Soap.escape(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
            "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">SA_RINCON65031_</desc>" +
            "</item></DIDL-Lite>"

    fun titleOf(metadata: String): String =
        Regex("<dc:title>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(metadata)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.trim().orEmpty()

    private val i0 = listOf<Pair<String, Any>>("InstanceID" to 0)

    /**
     * Plays a stream on exactly the given rooms: the first becomes the group leader,
     * the rest join it. Rooms grouped with the leader but not selected are split off.
     */
    fun play(targets: List<Speaker>, all: List<Speaker>, streamUrl: String, title: String, volume: Int?) {
        require(targets.isNotEmpty())
        val leader = targets.first()
        val targetIds = targets.map { it.uuid }.toSet()
        if (!leader.isCoordinator) {
            Soap.call(leader.ip, Soap.Svc.AV, "BecomeCoordinatorOfStandaloneGroup", i0)
        } else {
            all.filter { it.coordinatorUuid == leader.uuid && it.uuid != leader.uuid && it.uuid !in targetIds }
                .forEach { Soap.call(it.ip, Soap.Svc.AV, "BecomeCoordinatorOfStandaloneGroup", i0) }
        }
        Soap.call(leader.ip, Soap.Svc.AV, "SetAVTransportURI",
            i0 + listOf("CurrentURI" to streamUri(streamUrl), "CurrentURIMetaData" to didl(title)))
        targets.drop(1).forEach {
            Soap.call(it.ip, Soap.Svc.AV, "SetAVTransportURI",
                i0 + listOf("CurrentURI" to "x-rincon:${leader.uuid}", "CurrentURIMetaData" to ""))
        }
        if (volume != null) targets.forEach { setVolume(it, volume) }
        Soap.call(leader.ip, Soap.Svc.AV, "Play", i0 + listOf("Speed" to 1))
    }

    fun stop(s: Speaker, all: List<Speaker>) {
        val coord = all.firstOrNull { it.uuid == s.coordinatorUuid } ?: s
        try {
            Soap.call(coord.ip, Soap.Svc.AV, "Stop", i0)
        } catch (e: SonosException) {
            Soap.call(coord.ip, Soap.Svc.AV, "Pause", i0)
        }
    }

    /** Takes a room out of whatever group it is in. */
    fun leaveGroup(s: Speaker) = Soap.call(s.ip, Soap.Svc.AV, "BecomeCoordinatorOfStandaloneGroup", i0)

    fun setVolume(s: Speaker, v: Int) {
        Soap.call(s.ip, Soap.Svc.RC, "SetVolume",
            i0 + listOf("Channel" to "Master", "DesiredVolume" to v.coerceIn(0, 100)))
    }

    fun status(s: Speaker, all: List<Speaker>): RoomStatus {
        val coord = all.firstOrNull { it.uuid == s.coordinatorUuid } ?: s
        val state = Soap.call(coord.ip, Soap.Svc.AV, "GetTransportInfo", i0)["CurrentTransportState"].orEmpty()
        val media = Soap.call(coord.ip, Soap.Svc.AV, "GetMediaInfo", i0)
        var title = titleOf(media["CurrentURIMetaData"].orEmpty())
        if (title.isBlank()) title = streamUrlOf(media["CurrentURI"].orEmpty()) ?: ""
        val vol = Soap.call(s.ip, Soap.Svc.RC, "GetVolume", i0 + listOf("Channel" to "Master"))["CurrentVolume"]
            ?.toIntOrNull() ?: 0
        return RoomStatus(state, title, vol)
    }

    // ---- alarms (stored on the Sonos system; any speaker can be asked) ----

    fun listAlarms(ip: String): List<SonosAlarm> {
        val xml = Soap.call(ip, Soap.Svc.ALARM, "ListAlarms")["CurrentAlarmList"].orEmpty()
        if (xml.isBlank()) return emptyList()
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        val out = mutableListOf<SonosAlarm>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Alarm") {
                fun a(n: String) = p.getAttributeValue(null, n).orEmpty()
                out += SonosAlarm(
                    id = a("ID"), startTime = a("StartTime"), duration = a("Duration"),
                    recurrence = a("Recurrence"), enabled = a("Enabled") == "1", roomUuid = a("RoomUUID"),
                    programUri = a("ProgramURI"), programMetaData = a("ProgramMetaData"),
                    playMode = a("PlayMode").ifBlank { "NORMAL" }, volume = a("Volume").toIntOrNull() ?: 20,
                    includeLinkedZones = a("IncludeLinkedZones") == "1",
                )
            }
        }
        return out
    }

    private fun alarmArgs(a: SonosAlarm) = listOf<Pair<String, Any>>(
        "StartLocalTime" to a.startTime,
        "Duration" to a.duration,
        "Recurrence" to a.recurrence,
        "Enabled" to if (a.enabled) "1" else "0",
        "RoomUUID" to a.roomUuid,
        "ProgramURI" to a.programUri,
        "ProgramMetaData" to a.programMetaData,
        "PlayMode" to a.playMode,
        "Volume" to a.volume,
        "IncludeLinkedZones" to if (a.includeLinkedZones) "1" else "0",
    )

    fun createAlarm(ip: String, a: SonosAlarm): String =
        Soap.call(ip, Soap.Svc.ALARM, "CreateAlarm", alarmArgs(a))["AssignedID"].orEmpty()

    fun updateAlarm(ip: String, a: SonosAlarm) {
        Soap.call(ip, Soap.Svc.ALARM, "UpdateAlarm", listOf<Pair<String, Any>>("ID" to a.id) + alarmArgs(a))
    }

    fun destroyAlarm(ip: String, id: String) {
        Soap.call(ip, Soap.Svc.ALARM, "DestroyAlarm", listOf("ID" to id))
    }
}

// ---------------------------------------------------------------- recurrence helpers

object Recurrence {
    /** Sonos day codes: 0 = Sunday … 6 = Saturday. UI order is Monday-first. */
    val uiDays = listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 0 to "S")

    fun toDays(r: String): Set<Int> = when (r) {
        "DAILY" -> (0..6).toSet()
        "WEEKDAYS" -> (1..5).toSet()
        "WEEKENDS" -> setOf(0, 6)
        "ONCE" -> emptySet()
        else -> if (r.startsWith("ON_")) r.removePrefix("ON_").mapNotNull { it.digitToIntOrNull() }.toSet() else emptySet()
    }

    fun fromDays(days: Set<Int>): String = when (days) {
        emptySet<Int>() -> "ONCE"
        (0..6).toSet() -> "DAILY"
        (1..5).toSet() -> "WEEKDAYS"
        setOf(0, 6) -> "WEEKENDS"
        else -> "ON_" + days.sorted().joinToString("")
    }

    fun describe(r: String): String {
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return when (r) {
            "DAILY" -> "Every day"
            "WEEKDAYS" -> "Weekdays"
            "WEEKENDS" -> "Weekends"
            "ONCE" -> "Once"
            else -> toDays(r).sortedBy { (it + 6) % 7 }.joinToString(", ") { names[it] }
        }
    }
}
