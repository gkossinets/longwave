package app.longwave

import android.app.PendingIntent
import android.content.Intent
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.longwave.sonos.NowPlaying
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Publishes the active Sonos group as an Android media session, which gives the app
 * a media notification (pull-down shade, lock screen) with play/stop and volume.
 * Nothing plays on the phone; the "player" forwards commands to Sonos.
 */
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        NowPlaying.init(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, SonosPlayer(scope)).setSessionActivity(open).build()
        NowPlaying.startPolling()
        registerRouteDiscovery()
    }

    private var routeCallback: Any? = null
    private var transferCallback: Any? = null
    private var transferPending = false

    /**
     * Tells Android this app wants Sonos-room outputs (so the output switcher lists them),
     * and routes the app's media to the room that is actually playing, which gives the
     * media card a routing session to show (the room's name instead of "This phone").
     */
    private fun registerRouteDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val router = MediaRouter2.getInstance(this)
        val routes = object : MediaRouter2.RouteCallback() {
            override fun onRoutesUpdated(routes: List<MediaRoute2Info>) = ensureRoutedToSonos()
        }
        val transfers = object : MediaRouter2.TransferCallback() {
            override fun onTransfer(oldController: MediaRouter2.RoutingController, newController: MediaRouter2.RoutingController) {
                transferPending = false
                SonosRouteProvider.controllerId.value =
                    if (newController == router.systemController) null else newController.id
            }
            override fun onTransferFailure(requestedRoute: MediaRoute2Info) {
                transferPending = false
                Log.w("SonosRoutes", "transfer to ${requestedRoute.name} failed")
            }
            override fun onStop(controller: MediaRouter2.RoutingController) {
                if (controller.id == SonosRouteProvider.controllerId.value) SonosRouteProvider.controllerId.value = null
            }
        }
        val pref = RouteDiscoveryPreference.Builder(
            listOf(SonosRouteProvider.FEATURE, MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_REMOTE_PLAYBACK),
            true,
        ).build()
        router.registerRouteCallback(mainExecutor, routes, pref)
        router.registerTransferCallback(mainExecutor, transfers)
        routeCallback = routes
        transferCallback = transfers
        scope.launch { NowPlaying.state.collect { ensureRoutedToSonos() } }
    }

    private fun ensureRoutedToSonos() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (SonosRouteProvider.controllerId.value != null || transferPending) return
        val leader = NowPlaying.state.value.leader ?: return
        val router = MediaRouter2.getInstance(this)
        val route = router.routes.firstOrNull { it.id.endsWith(":" + leader.uuid) || it.id == leader.uuid } ?: return
        transferPending = true
        router.transferTo(route)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val router = MediaRouter2.getInstance(this)
            (routeCallback as? MediaRouter2.RouteCallback)?.let { router.unregisterRouteCallback(it) }
            (transferCallback as? MediaRouter2.TransferCallback)?.let { router.unregisterTransferCallback(it) }
        }
        NowPlaying.stopPolling()
        session?.run { player.release(); release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}

@OptIn(UnstableApi::class)
class SonosPlayer(private val scope: CoroutineScope) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val watcher = scope.launch { NowPlaying.state.collect { invalidateState() } }
    private val routeWatcher = scope.launch { SonosRouteProvider.controllerId.collect { invalidateState() } }

    private val commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_STOP,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_DEVICE_VOLUME,
        Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        Player.COMMAND_RELEASE,
    ).build()

    override fun getState(): State {
        val s = NowPlaying.state.value
        val b = State.Builder()
            .setAvailableCommands(commands)
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(100)
                    // Links this card to the Sonos routing session, so the output chip shows the rooms.
                    .setRoutingControllerId(SonosRouteProvider.controllerId.value)
                    .build()
            )
            .setDeviceVolume(s.volume)
        if (s.leader == null) {
            return b.setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .build()
        }
        val meta = MediaMetadata.Builder()
            .setTitle(s.title.ifBlank { if (s.isPlaying) "Sonos" else "Stopped" })
            .setArtist(listOf(s.track, s.roomsLabel).filter { it.isNotBlank() }.joinToString(" · "))
            .setStation(s.title)
            .apply { s.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        val item = MediaItemData.Builder("sonos-live")
            .setMediaItem(MediaItem.Builder().setMediaId("sonos-live").build())
            .setMediaMetadata(meta)
            .setIsDynamic(true)
            .setIsSeekable(false)
            .build()
        return b.setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (s.transport == "TRANSITIONING") Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .build()
    }

    private fun sonos(block: suspend () -> Unit): ListenableFuture<*> {
        val done = SettableFuture.create<Any?>()
        scope.launch(Dispatchers.IO) {
            try { block() } catch (e: Exception) { Log.w("SonosPlayer", "command failed", e) }
            done.set(null)
        }
        return done
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        sonos { if (playWhenReady) NowPlaying.play() else NowPlaying.stop() }

    override fun handleStop(): ListenableFuture<*> = sonos { NowPlaying.stop() }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> =
        sonos { NowPlaying.setVolume(deviceVolume) }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        sonos { NowPlaying.setVolume(NowPlaying.state.value.volume + 2) }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        sonos { NowPlaying.setVolume(NowPlaying.state.value.volume - 2) }

    override fun handleRelease(): ListenableFuture<*> {
        watcher.cancel()
        routeWatcher.cancel()
        return Futures.immediateVoidFuture()
    }
}
