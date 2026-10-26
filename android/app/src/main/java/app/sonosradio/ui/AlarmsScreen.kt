@file:OptIn(ExperimentalMaterial3Api::class)

package app.sonosradio.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonosradio.AlarmDraft
import app.sonosradio.AlarmGroup
import app.sonosradio.AppViewModel
import app.sonosradio.sonos.Recurrence
import app.sonosradio.sonos.SonosControl
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun parseTime(s: String): LocalTime = runCatching { LocalTime.parse(s) }.getOrDefault(LocalTime.of(7, 0))
private fun showTime(t: LocalTime): String = t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@Composable
fun AlarmsScreen(vm: AppViewModel) {
    var editing by remember { mutableStateOf<AlarmGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Alarms", vm.busy) {
                IconButton(onClick = { vm.refreshAlarms() }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
            val groups = vm.alarmGroups()
            if (groups.isEmpty() && !vm.busy) {
                Text("No alarms on your Sonos system yet.", Modifier.padding(24.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups, key = { it.key }) { g ->
                    val a = g.first
                    Card(Modifier.fillMaxWidth().clickable { editing = g }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(showTime(parseTime(a.startTime)), fontSize = 32.sp,
                                    color = if (a.enabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outline)
                                Text(Recurrence.describe(a.recurrence) + " · " +
                                    g.alarms.joinToString(", ") { vm.roomName(it.roomUuid) })
                                Text(vm.alarmSourceName(a) + " · volume ${a.volume}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = a.enabled, onCheckedChange = { vm.setAlarmEnabled(g, it) })
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Filled.Add, "New alarm") }
    }
    if (creating || editing != null) {
        AlarmEditor(
            vm, editing,
            onDismiss = { creating = false; editing = null },
        )
    }
}

private const val KEEP = "__keep__"
private const val CHIME = "__chime__"

@Composable
private fun AlarmEditor(vm: AppViewModel, existing: AlarmGroup?, onDismiss: () -> Unit) {
    val base = existing?.first
    val ctx = LocalContext.current
    var time by remember { mutableStateOf(base?.let { parseTime(it.startTime) } ?: LocalTime.of(7, 0)) }
    var days by remember { mutableStateOf(base?.let { Recurrence.toDays(it.recurrence) } ?: (1..5).toSet()) }
    var rooms by remember {
        mutableStateOf(existing?.roomUuids ?: vm.lastRooms.ifEmpty { vm.speakers.take(1).map { it.uuid }.toSet() })
    }
    // Sound: an existing alarm keeps its current source until another one is picked.
    val currentStation = base?.let { a ->
        SonosControl.streamUrlOf(a.programUri)?.let { url -> vm.myStations.firstOrNull { it.url == url } }
    }
    val baseIsChime = base != null && (base.programUri.isBlank() || base.programUri.startsWith("x-rincon-buzzer"))
    var sound by remember {
        mutableStateOf(
            when {
                base == null -> vm.myStations.firstOrNull()?.id ?: CHIME
                baseIsChime -> CHIME
                currentStation != null -> currentStation.id
                else -> KEEP
            }
        )
    }
    var volume by remember { mutableFloatStateOf((base?.volume ?: 15).toFloat()) }
    var durationMin by remember {
        mutableIntStateOf(base?.duration?.let { d ->
            val p = d.split(":").mapNotNull { it.toIntOrNull() }
            if (p.size >= 2) p[0] * 60 + p[1] else 60
        } ?: 60)
    }
    var enabled by remember { mutableStateOf(base?.enabled ?: true) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (existing == null) "New alarm" else "Edit alarm", style = MaterialTheme.typography.titleLarge)

                Text(showTime(time), fontSize = 48.sp, modifier = Modifier.clickable { pickTime = true })

                Section("Repeat")
                Chips {
                    Recurrence.uiDays.forEach { (code, label) ->
                        FilterChip(
                            selected = code in days,
                            onClick = { days = if (code in days) days - code else days + code },
                            label = { Text(label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Weekdays" to (1..5).toSet(), "Weekends" to setOf(0, 6), "Daily" to (0..6).toSet(),
                        "Once" to emptySet()).forEach { (label, set) ->
                        TextButton(onClick = { days = set }) { Text(label) }
                    }
                }
                Text(Recurrence.describe(Recurrence.fromDays(days)), style = MaterialTheme.typography.bodySmall)

                Section("Rooms")
                vm.speakers.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable {
                        rooms = if (s.uuid in rooms) rooms - s.uuid else rooms + s.uuid
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = s.uuid in rooms, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp)); Text(s.name)
                    }
                }

                Section("Sound")
                if (base != null && !baseIsChime && currentStation == null)
                    SoundOption(sound == KEEP, { sound = KEEP }, "Keep current: ${vm.alarmSourceName(base)}")
                vm.myStations.forEach { st -> SoundOption(sound == st.id, { sound = st.id }, st.name) }
                SoundOption(sound == CHIME, { sound = CHIME }, "Sonos chime")
                Text("Stations come from My stations. If a stream fails at alarm time, Sonos plays its chime instead.",
                    style = MaterialTheme.typography.bodySmall)

                Section("Volume ${volume.toInt()}")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..60f)

                Section("Play for")
                Chips {
                    listOf(15, 30, 60, 90, 120).forEach { m ->
                        FilterChip(selected = durationMin == m, onClick = { durationMin = m },
                            label = { Text(if (m < 60) "$m min" else "${m / 60}${if (m % 60 == 30) ".5" else ""} h") })
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f)); Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = rooms.isNotEmpty(), onClick = {
                        val station = vm.myStations.firstOrNull { it.id == sound }
                        val (uri, meta, mode) = when {
                            sound == KEEP && base != null -> Triple(base.programUri, base.programMetaData, base.playMode)
                            station != null -> Triple(SonosControl.streamUri(station.url), SonosControl.didl(station.name), "NORMAL")
                            else -> Triple(SonosControl.CHIME_URI, "", "NORMAL")
                        }
                        vm.saveAlarm(existing, AlarmDraft(
                            startTime = time.withSecond(0).toString().let { if (it.length == 5) "$it:00" else it },
                            recurrence = Recurrence.fromDays(days),
                            roomUuids = rooms,
                            programUri = uri, programMetaData = meta, playMode = mode,
                            volume = volume.toInt(),
                            duration = "%02d:%02d:00".format(durationMin / 60, durationMin % 60),
                            enabled = enabled,
                        ))
                        onDismiss()
                    }) { Text("Save") }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    if (existing != null) TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (pickTime) {
        val st = rememberTimePickerState(time.hour, time.minute, DateFormat.is24HourFormat(ctx))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(st.hour, st.minute); pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = st) },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this alarm?") },
            text = { Text("It will be removed from ${existing.alarms.size} room(s) on your Sonos system.") },
            confirmButton = { TextButton(onClick = { vm.deleteAlarm(existing); confirmDelete = false; onDismiss() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SoundOption(selected: Boolean, onSelect: () -> Unit, label: String) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp)); Text(label)
    }
}
