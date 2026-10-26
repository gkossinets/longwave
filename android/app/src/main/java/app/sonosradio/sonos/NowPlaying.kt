package app.sonosradio.sonos

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The "active" Sonos group: what the bottom control bar and the system media
 * notification show and control. Shared by the UI and PlaybackService.
 *
 * The active group is the one containing the room the user last played to or picked;
 * if none was picked, the first group that is playing.
 */
object NowPlaying {
    data class State(
        val leader: Speaker? = null,
        val members: List<Speaker> = emptyList(),
        val transport: String = "",
        val title: String = "",
        val volume: Int = 0,
    ) {
        val isPlaying: Boolean get() = transport == "PLAYING" || transport == "TRANSITIONING"
        val roomsLabel: String get() = members.joinToString(" + ") { it.name }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var speakers: List<Speaker> = emptyList()
    @Volatile private var activeUuid: String? = null
    private var appContext: Context? = null
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val i0 = listOf<Pair<String, Any>>("InstanceID" to 0)
    private val titles = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        activeUuid = prefs()?.getString("active_room", null)
    }

    private fun prefs() = appContext?.getSharedPreferences("sonos", Context.MODE_PRIVATE)

    /** Re-reads topology and the active group's state. Blocking network; call off the main thread. */
    suspend fun refresh() = mutex.withLock {
        val ctx = appContext ?: return@withLock
        var all = speakers
        if (all.isEmpty()) all = SonosDiscovery(ctx).discover()
        all = all.firstNotNullOfOrNull { s -> runCatching { SonosDiscovery(ctx).topology(s.ip) }.getOrNull() }
            ?: SonosDiscovery(ctx).discover()
        speakers = all
        if (all.isEmpty()) { _state.value = State(); return@withLock }

        val coordinators = all.filter { it.isCoordinator }
        val chosen = all.firstOrNull { it.uuid == activeUuid }
        val leader = if (chosen != null) {
            all.firstOrNull { it.uuid == chosen.coordinatorUuid } ?: chosen
        } else {
            coordinators.firstOrNull { transportOf(it) == "PLAYING" } ?: coordinators.firstOrNull() ?: all.first()
        }
        val members = all.filter { it.coordinatorUuid == leader.uuid }.ifEmpty { listOf(leader) }
        val transport = transportOf(leader)
        val media = runCatching { Soap.call(leader.ip, Soap.Svc.AV, "GetMediaInfo", i0) }.getOrDefault(emptyMap())
        val uri = media["CurrentURI"].orEmpty()
        var title = SonosControl.titleOf(media["CurrentURIMetaData"].orEmpty())
        // Sonos drops the title when coordination moves to another room; reuse the one we saw.
        if (title.isNotBlank()) titles[uri] = title
        else title = titles[uri] ?: SonosControl.streamUrlOf(uri) ?: ""
        val volume = runCatching {
            Soap.call(leader.ip, Soap.Svc.GRC, "GetGroupVolume", i0)["CurrentVolume"]?.toIntOrNull()
        }.getOrNull() ?: 0
        _state.value = State(leader, members, transport, title, volume)
    }

    private fun transportOf(s: Speaker): String =
        runCatching { Soap.call(s.ip, Soap.Svc.AV, "GetTransportInfo", i0)["CurrentTransportState"].orEmpty() }
            .getOrDefault("")

    suspend fun refreshQuietly() {
        try { refresh() } catch (_: Exception) { }
    }

    /** Makes the group containing this room the active one. */
    suspend fun setActive(uuid: String) {
        activeUuid = uuid
        prefs()?.edit()?.putString("active_room", uuid)?.apply()
        refresh()
    }

    suspend fun play() {
        val l = _state.value.leader ?: throw SonosException("No room selected")
        try {
            Soap.call(l.ip, Soap.Svc.AV, "Play", i0 + listOf("Speed" to 1))
        } catch (e: SonosException) {
            throw SonosException("Nothing to resume in ${l.name}; pick a station first")
        }
        _state.value = _state.value.copy(transport = "TRANSITIONING")
        delay(1200)
        refresh()
    }

    suspend fun stop() {
        val l = _state.value.leader ?: return
        try {
            Soap.call(l.ip, Soap.Svc.AV, "Stop", i0)
        } catch (e: SonosException) {
            Soap.call(l.ip, Soap.Svc.AV, "Pause", i0)
        }
        _state.value = _state.value.copy(transport = "STOPPED")
        refresh()
    }

    /** Group volume: Sonos keeps the rooms' relative levels. */
    suspend fun setVolume(v: Int) {
        val l = _state.value.leader ?: return
        val vol = v.coerceIn(0, 100)
        _state.value = _state.value.copy(volume = vol)
        Soap.call(l.ip, Soap.Svc.GRC, "SnapshotGroupVolume", i0)
        Soap.call(l.ip, Soap.Svc.GRC, "SetGroupVolume", i0 + listOf("DesiredVolume" to vol))
    }

    /**
     * Regroups so the active group is exactly [target]; playback continues.
     * If the current leader is unchecked, the same stream is started on a checked room, the
     * other checked rooms join it, and the old leader's group is dissolved and stopped.
     * (DelegateGroupCoordinationTo was tried first but leaves the hidden half of a stereo
     * pair playing when the pair was the leader, so it is only used for queue content,
     * which can't be copied to another speaker.)
     */
    suspend fun setRooms(target: Set<String>) {
        if (target.isEmpty()) return
        val ctx = appContext ?: return
        refresh()
        val all = speakers
        val leader = _state.value.leader ?: return
        val memberIds = _state.value.members.map { it.uuid }.toSet()
        fun join(s: Speaker, to: Speaker) = Soap.call(s.ip, Soap.Svc.AV, "SetAVTransportURI",
            i0 + listOf("CurrentURI" to "x-rincon:${to.uuid}", "CurrentURIMetaData" to ""))
        fun leave(s: Speaker) = runCatching { Soap.call(s.ip, Soap.Svc.AV, "BecomeCoordinatorOfStandaloneGroup", i0) }

        var newLeader = leader
        if (leader.uuid !in target) {
            newLeader = all.firstOrNull { it.uuid in target && it.uuid in memberIds } ?: all.first { it.uuid in target }
            val media = Soap.call(leader.ip, Soap.Svc.AV, "GetMediaInfo", i0)
            val uri = media["CurrentURI"].orEmpty()
            var meta = media["CurrentURIMetaData"].orEmpty()
            if (SonosControl.titleOf(meta).isBlank()) titles[uri]?.let { meta = SonosControl.didl(it) }
            val wasPlaying = _state.value.isPlaying
            if (uri.startsWith("x-rincon-queue:")) {
                // A queue lives on the old leader; hand coordination over instead of copying.
                if (newLeader.uuid !in memberIds) {
                    if (!newLeader.isCoordinator) leave(newLeader)
                    join(newLeader, leader)
                    delay(1500)
                }
                Soap.call(leader.ip, Soap.Svc.AV, "DelegateGroupCoordinationTo",
                    i0 + listOf("NewCoordinator" to newLeader.uuid, "RejoinGroup" to 0))
            } else {
                leave(newLeader)
                if (uri.isNotBlank()) {
                    Soap.call(newLeader.ip, Soap.Svc.AV, "SetAVTransportURI",
                        i0 + listOf("CurrentURI" to uri, "CurrentURIMetaData" to meta))
                    if (wasPlaying) Soap.call(newLeader.ip, Soap.Svc.AV, "Play", i0 + listOf("Speed" to 1))
                }
                // Dissolve the old group and stop the old leader (a stereo pair stops as a unit).
                all.filter { it.coordinatorUuid == leader.uuid && it.uuid != leader.uuid && it.uuid != newLeader.uuid }
                    .forEach { leave(it) }
                runCatching { Soap.call(leader.ip, Soap.Svc.AV, "Stop", i0) }
            }
            delay(1500)
        }
        val now = SonosDiscovery(ctx).topology(newLeader.ip)
        now.filter { it.uuid in target && it.uuid != newLeader.uuid && it.coordinatorUuid != newLeader.uuid }
            .forEach { join(it, newLeader) }
        now.filter { it.coordinatorUuid == newLeader.uuid && it.uuid != newLeader.uuid && it.uuid !in target }
            .forEach { leave(it) }
        delay(800)
        speakers = emptyList() // force a fresh topology read
        setActive(newLeader.uuid)
    }

    /** Polls every few seconds while the media service is alive, so the notification stays current. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refreshQuietly()
                delay(5000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
