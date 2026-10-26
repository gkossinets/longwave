package app.longwave

import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RoutingSessionInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import app.longwave.sonos.NowPlaying
import app.longwave.sonos.SonosControl
import app.longwave.sonos.Soap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Registers each Sonos room as an Android media output ("route"), and the active
 * Sonos group as a routing session. Android's output switcher (the chip on the
 * media card in the shade / lock screen) then shows the rooms, can move or group
 * playback, and sets room or group volume. The media session links to the routing
 * session through DeviceInfo.routingControllerId (see SonosPlayer).
 */
@RequiresApi(Build.VERSION_CODES.R)
class SonosRouteProvider : MediaRoute2ProviderService() {
    companion object {
        const val FEATURE = "app.longwave.SONOS_ROOM"
        /** Provider-side id of the routing session for the active group, or null. */
        val sessionId = MutableStateFlow<String?>(null)
        /** App-side MediaRouter2 controller id for that session; what the media session links to. */
        val controllerId = MutableStateFlow<String?>(null)
        private var seq = 0
        private const val TAG = "SonosRoutes"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var roomVolumes = mapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        NowPlaying.init(this)
        scope.launch {
            NowPlaying.refreshQuietly()
            NowPlaying.state.collect { publish(it) }
        }
    }

    override fun onDestroy() {
        sessionId.value?.let { runCatching { notifySessionReleased(it) } }
        sessionId.value = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- publishing routes and the session

    private suspend fun publish(s: NowPlaying.State) {
        val speakers = NowPlaying.speakers
        if (speakers.isEmpty()) return
        roomVolumes = speakers.associate { sp ->
            sp.uuid to (runCatching {
                Soap.call(sp.ip, Soap.Svc.RC, "GetVolume", listOf("InstanceID" to 0, "Channel" to "Master"))["CurrentVolume"]?.toInt()
            }.getOrNull() ?: roomVolumes[sp.uuid] ?: 0)
        }
        val memberIds = s.members.map { it.uuid }.toSet()
        val routes = speakers.map { sp ->
            MediaRoute2Info.Builder(sp.uuid, sp.name)
                .addFeature(FEATURE)
                .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
                .addFeature(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
                .setDescription("Sonos")
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(100)
                .setVolume(roomVolumes[sp.uuid] ?: 0)
                .setConnectionState(
                    if (sp.uuid in memberIds) MediaRoute2Info.CONNECTION_STATE_CONNECTED
                    else MediaRoute2Info.CONNECTION_STATE_DISCONNECTED
                )
                .apply {
                    if (Build.VERSION.SDK_INT >= 34) {
                        addFeature(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK)
                        setType(MediaRoute2Info.TYPE_REMOTE_SPEAKER)
                    }
                }
                .build()
        }
        withContext(Dispatchers.Main) {
            notifyRoutes(routes)
            // Sessions are only created on request (PlaybackService asks to transfer to the
            // active room); here we just keep an existing one in step with the Sonos group.
            val id = sessionId.value ?: return@withContext
            if (s.leader == null || memberIds.isEmpty()) return@withContext
            notifySessionUpdated(sessionInfo(id, s, speakers.map { it.uuid }))
        }
    }

    private fun sessionInfo(id: String, s: NowPlaying.State, all: List<String>): RoutingSessionInfo {
        val members = s.members.map { it.uuid }
        return RoutingSessionInfo.Builder(id, packageName)
            .setName(s.roomsLabel)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(100)
            .setVolume(s.volume)
            .apply {
                members.forEach { addSelectedRoute(it) }
                if (members.size > 1) members.forEach { addDeselectableRoute(it) }
                all.filter { it !in members }.forEach { addSelectableRoute(it); addTransferableRoute(it) }
            }
            .build()
    }

    private fun run(requestId: Long, what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "$what failed", e)
                withContext(Dispatchers.Main) { notifyRequestFailed(requestId, REASON_NETWORK_ERROR) }
            }
        }
    }

    private fun members() = NowPlaying.state.value.members.map { it.uuid }.toSet()

    // ---------------- requests from Android's output switcher

    override fun onCreateSession(requestId: Long, packageName: String, routeId: String, sessionHints: Bundle?) =
        run(requestId, "create session") {
            // Already playing there (the app linking to its current group): keep the group as is.
            if (routeId !in members()) NowPlaying.setRooms(setOf(routeId))
            val s = NowPlaying.state.value
            withContext(Dispatchers.Main) {
                sessionId.value?.let { notifySessionReleased(it) }
                val id = "longwave-${++seq}"
                notifySessionCreated(requestId, sessionInfo(id, s, NowPlaying.speakers.map { it.uuid }))
                sessionId.value = id
            }
        }

    override fun onReleaseSession(requestId: Long, sessionId: String) =
        run(requestId, "release session") {
            NowPlaying.stop()
            withContext(Dispatchers.Main) {
                notifySessionReleased(sessionId)
                if (SonosRouteProvider.sessionId.value == sessionId) SonosRouteProvider.sessionId.value = null
            }
        }

    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) =
        run(requestId, "add room") { NowPlaying.setRooms(members() + routeId) }

    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) =
        run(requestId, "remove room") {
            val next = members() - routeId
            if (next.isNotEmpty()) NowPlaying.setRooms(next)
        }

    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) =
        run(requestId, "move") { NowPlaying.setRooms(setOf(routeId)) }

    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) =
        run(requestId, "room volume") {
            val sp = NowPlaying.speakers.firstOrNull { it.uuid == routeId } ?: return@run
            SonosControl.setVolume(sp, volume)
            NowPlaying.refreshQuietly()
        }

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) =
        run(requestId, "group volume") { NowPlaying.setVolume(volume) }
}
