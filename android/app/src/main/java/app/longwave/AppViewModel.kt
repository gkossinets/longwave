package app.longwave

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.longwave.data.RandomPicker
import app.longwave.data.RadioBrowser
import app.longwave.data.Station
import app.longwave.data.StationRepository
import app.longwave.data.StreamCheck
import app.longwave.sonos.NowPlaying
import app.longwave.sonos.RoomStatus
import app.longwave.sonos.SonosAlarm
import app.longwave.sonos.SonosControl
import app.longwave.sonos.SonosDiscovery
import app.longwave.sonos.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An alarm as shown in the app: same time/days/source/volume across one or more rooms. */
data class AlarmGroup(val alarms: List<SonosAlarm>) {
    val first get() = alarms.first()
    val roomUuids get() = alarms.map { it.roomUuid }.toSet()
    val key get() = first.id
}

/** What the alarm editor hands back. */
data class AlarmDraft(
    val startTime: String,
    val recurrence: String,
    val roomUuids: Set<String>,
    val programUri: String,
    val programMetaData: String,
    val playMode: String,
    val volume: Int,
    val duration: String,
    val enabled: Boolean,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = StationRepository(app)
    private val discovery = SonosDiscovery(app)
    private val prefs = app.getSharedPreferences("ui", 0)

    var myStations by mutableStateOf(repo.loadMine()); private set
    var curated by mutableStateOf(emptyList<Station>()); private set
    var speakers by mutableStateOf(emptyList<Speaker>()); private set
    var status by mutableStateOf(emptyMap<String, RoomStatus>()); private set
    var alarms by mutableStateOf(emptyList<SonosAlarm>()); private set
    var searchResults by mutableStateOf(emptyList<Station>()); private set
    var searching by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    var lastRooms: Set<String>
        get() = prefs.getStringSet("last_rooms", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("last_rooms", v).apply()
    var lastVolume: Int
        get() = prefs.getInt("last_volume", 15)
        set(v) = prefs.edit().putInt("last_volume", v).apply()

    init {
        NowPlaying.init(app)
        viewModelScope.launch { curated = withContext(Dispatchers.IO) { repo.loadCurated() } }
        refreshAll()
    }

    private fun launchIo(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            message = "$label: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            busy = false
        }
    }

    // ---------------- speakers

    fun refreshAll() = launchIo("Finding speakers") {
        speakers = discovery.discover()
        NowPlaying.speakers = speakers
        loadStatus()
        NowPlaying.refreshQuietly()
        alarms = SonosControl.listAlarms(speakers.first().ip)
    }

    private fun loadStatus() {
        val all = speakers
        status = all.associate { s -> s.uuid to runCatching { SonosControl.status(s, all) }.getOrElse { RoomStatus("?", "", 0) } }
    }

    /** After a command, re-read groups and poll status until no room is still "TRANSITIONING" (max ~12 s). */
    private suspend fun reloadTopologyAndStatus() {
        speakers.firstOrNull()?.let { speakers = discovery.topology(it.ip) }
        repeat(8) {
            delay(1500)
            loadStatus()
            if (status.values.none { it.state == "TRANSITIONING" }) {
                NowPlaying.refreshQuietly()
                return
            }
        }
        NowPlaying.refreshQuietly()
    }

    /** Light refresh used when the Speakers tab opens. */
    fun refreshStatus() = viewModelScope.launch {
        if (speakers.isEmpty()) return@launch
        try {
            withContext(Dispatchers.IO) {
                speakers = discovery.topology(speakers.first().ip)
                loadStatus()
            }
        } catch (_: Exception) {
        }
    }

    fun play(station: Station, roomUuids: Set<String>, volume: Int?) = launchIo("Play") {
        if (speakers.isEmpty()) speakers = discovery.discover()
        val targets = speakers.filter { it.uuid in roomUuids }
        if (targets.isEmpty()) throw IllegalStateException("Pick at least one room")
        lastRooms = roomUuids
        volume?.let { lastVolume = it }
        SonosControl.play(targets, speakers, station.url, station.name, volume)
        message = "Playing ${station.name} in ${targets.joinToString(" + ") { it.name }}"
        runCatching { NowPlaying.setActive(targets.first().uuid) }
        reloadTopologyAndStatus()
    }

    // ---------------- control bar (active group)

    fun togglePlay() = launchIo("Control") {
        if (NowPlaying.state.value.isPlaying) NowPlaying.stop() else NowPlaying.play()
        loadStatus()
    }

    fun setActiveRoom(uuid: String) = launchIo("Room") { NowPlaying.setActive(uuid) }

    /** Quick switcher: make the active group exactly these rooms. */
    fun setRooms(uuids: Set<String>) = launchIo("Speakers") {
        NowPlaying.setRooms(uuids)
        lastRooms = uuids
        speakers = NowPlaying.speakers
        loadStatus()
    }

    fun setGroupVolume(v: Int) = launchIo("Volume") { NowPlaying.setVolume(v); loadStatus() }

    fun stop(s: Speaker) = launchIo("Stop") { SonosControl.stop(s, speakers); reloadTopologyAndStatus() }

    fun ungroup(s: Speaker) = launchIo("Ungroup") { SonosControl.leaveGroup(s); reloadTopologyAndStatus() }

    fun setVolume(s: Speaker, v: Int) = launchIo("Volume") {
        SonosControl.setVolume(s, v)
        status[s.uuid]?.let { status = status + (s.uuid to it.copy(volume = v)) }
    }

    // ---------------- my stations

    fun isSaved(st: Station) = myStations.any { it.url == st.url }

    // Kept here so the no-repeats order survives switching tabs.
    private val randomPicker = RandomPicker()

    /** A random station from [pool] (the curated stations currently shown), or null if empty. */
    fun randomStation(pool: List<Station>): Station? = randomPicker.next(pool, ::isSaved)

    fun addStation(st: Station) {
        if (isSaved(st)) { message = "${st.name} is already in My stations"; return }
        myStations = myStations + st.copy(id = java.util.UUID.randomUUID().toString())
        repo.saveMine(myStations)
        message = "Added ${st.name}"
    }

    fun removeStation(st: Station) {
        myStations = myStations.filterNot { it.id == st.id }
        repo.saveMine(myStations)
    }

    fun updateStation(st: Station) {
        myStations = myStations.map { if (it.id == st.id) st else it }
        repo.saveMine(myStations)
    }

    fun moveStation(st: Station, delta: Int) {
        val i = myStations.indexOfFirst { it.id == st.id }
        val j = i + delta
        if (i < 0 || j !in myStations.indices) return
        myStations = myStations.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
        repo.saveMine(myStations)
    }

    // ---------------- browse

    fun search(query: String, country: String, tag: String) = viewModelScope.launch {
        searching = true
        try {
            searchResults = withContext(Dispatchers.IO) { RadioBrowser.search(query, country, tag) }
            if (searchResults.isEmpty()) message = "No stations found"
        } catch (e: Exception) {
            message = "Search failed: ${e.message}"
        } finally {
            searching = false
        }
    }

    suspend fun checkStream(url: String): StreamCheck.Result =
        withContext(Dispatchers.IO) { StreamCheck.check(url.trim()) }

    // ---------------- alarms

    fun alarmGroups(): List<AlarmGroup> = alarms
        .groupBy { listOf(it.startTime, it.recurrence, it.programUri, it.enabled, it.volume, it.duration) }
        .values.map { AlarmGroup(it) }
        .sortedWith(compareBy({ it.first.startTime }, { it.first.recurrence }))

    fun roomName(uuid: String) = speakers.firstOrNull { it.uuid == uuid }?.name ?: "Unknown room"

    fun alarmSourceName(a: SonosAlarm): String {
        if (a.programUri.isBlank() || a.programUri.startsWith("x-rincon-buzzer")) return "Sonos chime"
        SonosControl.streamUrlOf(a.programUri)?.let { url ->
            val bare = url.substringAfter("://")
            myStations.firstOrNull { it.url == url || it.url.substringAfter("://") == bare }?.let { return it.name }
        }
        val t = SonosControl.titleOf(a.programMetaData)
        return when {
            t.isNotBlank() && SonosControl.streamUrlOf(a.programUri) != null -> t
            t.isNotBlank() -> "$t (via Sonos app)"
            else -> "Other source (set in Sonos app)"
        }
    }

    fun refreshAlarms() = launchIo("Alarms") {
        if (speakers.isEmpty()) speakers = discovery.discover()
        alarms = SonosControl.listAlarms(speakers.first().ip)
    }

    /** Creates, updates or removes one Sonos alarm per room so the group matches the draft. */
    fun saveAlarm(existing: AlarmGroup?, d: AlarmDraft) = launchIo("Saving alarm") {
        if (d.roomUuids.isEmpty()) throw IllegalStateException("Pick at least one room")
        val ip = speakers.first().ip
        val byRoom = existing?.alarms.orEmpty().associateBy { it.roomUuid }
        for (room in d.roomUuids) {
            val base = byRoom[room]
            val a = SonosAlarm(
                id = base?.id ?: "", startTime = d.startTime, duration = d.duration, recurrence = d.recurrence,
                enabled = d.enabled, roomUuid = room, programUri = d.programUri, programMetaData = d.programMetaData,
                playMode = d.playMode, volume = d.volume, includeLinkedZones = base?.includeLinkedZones ?: false,
            )
            if (base != null) SonosControl.updateAlarm(ip, a) else SonosControl.createAlarm(ip, a)
        }
        existing?.alarms.orEmpty().filter { it.roomUuid !in d.roomUuids }.forEach { SonosControl.destroyAlarm(ip, it.id) }
        alarms = SonosControl.listAlarms(ip)
        message = "Alarm saved"
    }

    fun setAlarmEnabled(g: AlarmGroup, enabled: Boolean) = launchIo("Alarm") {
        val ip = speakers.first().ip
        g.alarms.forEach { SonosControl.updateAlarm(ip, it.copy(enabled = enabled)) }
        alarms = SonosControl.listAlarms(ip)
    }

    fun deleteAlarm(g: AlarmGroup) = launchIo("Delete alarm") {
        val ip = speakers.first().ip
        g.alarms.forEach { SonosControl.destroyAlarm(ip, it.id) }
        alarms = SonosControl.listAlarms(ip)
        message = "Alarm deleted"
    }
}
