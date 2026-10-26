@file:OptIn(ExperimentalMaterial3Api::class)

package app.longwave.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.longwave.AppViewModel
import app.longwave.sonos.NowPlaying

/** Control bar shown above the tabs on every screen: what the active group is playing, play/stop. */
@Composable
fun MiniPlayer(vm: AppViewModel) {
    val s by NowPlaying.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    var showRooms by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { showSheet = true }.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Speaker, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = when {
                    s.leader == null -> "No speaker yet"
                    s.isPlaying && s.title.isNotBlank() -> s.title
                    s.isPlaying -> "Playing"
                    s.title.isNotBlank() -> "Stopped · ${s.title}"
                    else -> "Stopped"
                }
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (s.leader == null) "Tap to choose a room" else "${s.roomsLabel} · volume ${s.volume}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { showRooms = true }, enabled = vm.speakers.isNotEmpty()) {
                Icon(Icons.Filled.SpeakerGroup, contentDescription = "Choose speakers")
            }
            FilledTonalIconButton(onClick = { vm.togglePlay() }, enabled = s.leader != null) {
                Icon(if (s.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (s.isPlaying) "Stop" else "Play")
            }
        }
    }
    if (showSheet) ActiveRoomSheet(vm, onDismiss = { showSheet = false })
    if (showRooms) SpeakerSwitcherSheet(vm, onDismiss = { showRooms = false })
}

/** Checkbox list of all rooms; each tap regroups the active group immediately. */
@Composable
private fun SpeakerSwitcherSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val s by NowPlaying.state.collectAsState()
    val actual = s.members.map { it.uuid }.toSet()
    var pending by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(actual) { pending = null }
    val shown = pending ?: actual
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speakers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                if (s.title.isNotBlank()) "Checked rooms play ${s.title}" else "Checked rooms are grouped together",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            vm.speakers.forEach { sp ->
                val checked = sp.uuid in shown
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !vm.busy) {
                        val next = if (checked) shown - sp.uuid else shown + sp.uuid
                        if (next.isNotEmpty()) { pending = next; vm.setRooms(next) }
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = !vm.busy)
                    Spacer(Modifier.width(8.dp))
                    Text(sp.name)
                }
            }
        }
    }
}

@Composable
private fun ActiveRoomSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val s by NowPlaying.state.collectAsState()
    var vol by remember(s.leader?.uuid, s.volume) { mutableFloatStateOf(s.volume.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Control", style = MaterialTheme.typography.titleLarge)
            Text("The bar and the phone's media notification control this room or group.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            val groups = vm.speakers.filter { it.isCoordinator }.map { c ->
                c to vm.speakers.filter { it.coordinatorUuid == c.uuid }
            }
            if (groups.isEmpty()) Text("No speakers found yet.")
            groups.forEach { (leader, members) ->
                val selected = s.leader?.uuid == leader.uuid
                val st = vm.status[leader.uuid]
                Row(
                    Modifier.fillMaxWidth().selectable(selected = selected, onClick = { vm.setActiveRoom(leader.uuid) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(members.joinToString(" + ") { it.name })
                        if (st?.state == "PLAYING" && st.title.isNotBlank())
                            Text("Playing: ${st.title}", style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (s.leader != null) {
                Spacer(Modifier.height(12.dp))
                Text("Volume ${vol.toInt()}" + if (s.members.size > 1) " (whole group)" else "",
                    style = MaterialTheme.typography.titleSmall)
                Slider(value = vol, onValueChange = { vol = it }, valueRange = 0f..100f,
                    onValueChangeFinished = { vm.setGroupVolume(vol.toInt()) })
                Button(onClick = { vm.togglePlay() }) {
                    Icon(if (s.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (s.isPlaying) "Stop" else "Play")
                }
            }
        }
    }
}
