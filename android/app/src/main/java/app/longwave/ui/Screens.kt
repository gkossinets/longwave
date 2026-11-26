@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.longwave.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.longwave.AppViewModel
import app.longwave.data.Artwork
import app.longwave.data.Station
import app.longwave.data.StreamCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- shared bits

@Composable
fun Header(title: String, busy: Boolean, actions: @Composable () -> Unit = {}) {
    Column {
        TopAppBar(
            title = { Text(title) },
            actions = { actions() },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun StationRow(
    st: Station,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    showDescription: Boolean = true,
    showArtwork: Boolean = false,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            if (showArtwork) StationArt(st)
            else Icon(if (st.favorite) Icons.Filled.Star else Icons.Filled.Radio, contentDescription = null)
        },
        headlineContent = { Text(st.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val line = listOf(
                if (showDescription) st.description else "",
                st.details,
            ).filter { it.isNotBlank() }.joinToString("\n")
            if (line.isNotBlank()) Text(line, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { Row { trailing() } },
    )
}

private val ART_SIZE = 48.dp

// Decoded row images, so a row scrolling back into view shows its logo at once instead of
// flashing the placeholder while the PNG is decoded again.
private val decodedArt = LruCache<String, ImageBitmap>(64)

/**
 * The station's logo (or its generated name tile), the same image as the control bar and
 * notification. Shows the radio icon until it loads, or if it can't be made at all.
 */
@Composable
private fun StationArt(st: Station) {
    val ctx = LocalContext.current.applicationContext
    val targetPx = with(LocalDensity.current) { ART_SIZE.roundToPx() }
    val cacheKey = Artwork.key(st) + "@" + targetPx
    val art by produceState(decodedArt.get(cacheKey), cacheKey) {
        if (value == null) value = withContext(Dispatchers.IO) {
            Artwork.forStation(ctx, st)?.let { decodeScaled(it, targetPx) }?.also { decodedArt.put(cacheKey, it) }
        }
    }
    Box(Modifier.size(ART_SIZE), contentAlignment = Alignment.Center) {
        val img = art
        if (img != null) Image(img, contentDescription = null,
            modifier = Modifier.size(ART_SIZE).clip(RoundedCornerShape(8.dp)))
        else Icon(if (st.favorite) Icons.Filled.Star else Icons.Filled.Radio, contentDescription = null)
    }
}

/** Artwork is 512 px; decode at the smallest power-of-two step that still covers the row. */
private fun decodeScaled(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

// ---------------------------------------------------------------- My stations

@Composable
fun StationsScreen(vm: AppViewModel, onPlay: (Station) -> Unit, onBrowse: () -> Unit) {
    var editing by remember { mutableStateOf<Station?>(null) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        Header("My stations", vm.busy)
        if (vm.myStations.isEmpty()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No stations yet.")
                Button(onClick = onBrowse) { Text("Browse stations") }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.myStations, key = { it.id }) { st ->
                var menu by remember { mutableStateOf(false) }
                StationRow(st, onClick = { onPlay(st) }, showArtwork = true, trailing = {
                    IconButton(onClick = { onPlay(st) }) { Icon(Icons.Filled.PlayArrow, "Play on Sonos") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = st })
                        DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; vm.moveStation(st, -1) })
                        DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; vm.moveStation(st, 1) })
                        if (st.homepage.isNotBlank())
                            DropdownMenuItem(text = { Text("Open website") }, onClick = {
                                menu = false; runCatching { uri.openUri(st.homepage) }
                            })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; vm.removeStation(st) })
                    }
                })
                HorizontalDivider()
            }
        }
    }
    editing?.let { st ->
        EditStationDialog(st, onDismiss = { editing = null }, onSave = { vm.updateStation(it); editing = null })
    }
}

@Composable
private fun EditStationDialog(st: Station, onDismiss: () -> Unit, onSave: (Station) -> Unit) {
    var name by remember { mutableStateOf(st.name) }
    var url by remember { mutableStateOf(st.url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Stream URL") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = { onSave(st.copy(name = name.trim(), url = url.trim())) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Play sheet

@Composable
fun PlaySheet(vm: AppViewModel, st: Station, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = remember(vm.speakers) {
        val last = vm.lastRooms.filter { id -> vm.speakers.any { it.uuid == id } }.toSet()
        last.ifEmpty { vm.speakers.take(1).map { it.uuid }.toSet() }
    }
    var selected by remember(initial) { mutableStateOf(initial) }
    var setVolume by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(vm.lastVolume.toFloat()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(st.name, style = MaterialTheme.typography.titleLarge)
            if (st.details.isNotBlank()) Text(st.details, style = MaterialTheme.typography.bodySmall)
            if (st.isHls) Text(
                "HLS stream: these usually play on Sonos, but some don't.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(16.dp))
            Text("Play on", style = MaterialTheme.typography.titleSmall)
            if (vm.speakers.isEmpty()) {
                Text("No speakers found yet.")
                TextButton(onClick = { vm.refreshAll() }) { Text("Search again") }
            }
            vm.speakers.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        selected = if (s.uuid in selected) selected - s.uuid else selected + s.uuid
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = s.uuid in selected, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(s.name)
                        vm.status[s.uuid]?.takeIf { it.state == "PLAYING" && it.title.isNotBlank() }?.let {
                            Text("Playing: ${it.title}", style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { setVolume = !setVolume }) {
                Checkbox(checked = setVolume, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(if (setVolume) "Set volume: ${volume.toInt()}" else "Keep current volume")
            }
            if (setVolume) Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..60f)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = selected.isNotEmpty(), onClick = {
                    vm.play(st, selected, if (setVolume) volume.toInt() else null)
                    onDismiss()
                }) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                }
                if (!vm.isSaved(st)) OutlinedButton(onClick = { vm.addStation(st) }) { Text("Add to My stations") }
            }
        }
    }
}

// ---------------------------------------------------------------- Browse

@Composable
fun BrowseScreen(vm: AppViewModel, sharedUrl: MutableState<String?>, onPlay: (Station) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(sharedUrl.value) { if (sharedUrl.value != null) tab = 2 }
    Column(Modifier.fillMaxSize()) {
        Header("Browse", vm.busy || vm.searching)
        TabRow(selectedTabIndex = tab) {
            listOf("Curated", "Worldwide", "Add URL").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> CuratedTab(vm, onPlay)
            1 -> WorldwideTab(vm, onPlay)
            else -> AddUrlTab(vm, sharedUrl)
        }
    }
}

@Composable
private fun AddOrSavedButton(vm: AppViewModel, st: Station) {
    if (vm.isSaved(st)) IconButton(onClick = {}, enabled = false) { Icon(Icons.Filled.Check, "Saved") }
    else IconButton(onClick = { vm.addStation(st) }) { Icon(Icons.Filled.Add, "Add to My stations") }
}

@Composable
private fun CuratedTab(vm: AppViewModel, onPlay: (Station) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    val genres = remember(vm.curated) { vm.curated.map { it.genre }.distinct() }
    val shown = remember(vm.curated, filter, genre) {
        val q = filter.trim().lowercase()
        vm.curated.filter {
            (genre.isBlank() || it.genre == genre) &&
                (q.isBlank() || (it.name + " " + it.description).lowercase().contains(q))
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                filter, { filter = it }, label = { Text("Filter") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = genre.isBlank(), onClick = { genre = "" }, label = { Text("All") }) }
                items(genres) { g ->
                    FilterChip(selected = genre == g, onClick = { genre = if (genre == g) "" else g }, label = { Text(g) })
                }
            }
            Text(
                "${shown.size} stations · ★ = list curator's favourite · source: github.com/deroverda/recommended-radio-streams",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(shown, key = { it.id }) { st ->
            StationRow(st, onClick = { onPlay(st) }, trailing = {
                IconButton(onClick = { onPlay(st) }) { Icon(Icons.Filled.PlayArrow, "Play on Sonos") }
                AddOrSavedButton(vm, st)
            })
            HorizontalDivider()
        }
    }
}

@Composable
private fun WorldwideTab(vm: AppViewModel, onPlay: (Station) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Station name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(country, { country = it.take(2) }, label = { Text("Country (FI, FR…)") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    OutlinedTextField(tag, { tag = it }, label = { Text("Genre tag") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.search(name, country, tag) }, enabled = !vm.searching) { Text("Search") }
                    Spacer(Modifier.width(12.dp))
                    Text("Empty search = most-voted stations", style = MaterialTheme.typography.bodySmall)
                }
                Text("Directory: radio-browser.info (community-maintained)", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(vm.searchResults, key = { it.id }) { st ->
            StationRow(st, onClick = { onPlay(st) }, trailing = {
                IconButton(onClick = { onPlay(st) }) { Icon(Icons.Filled.PlayArrow, "Play on Sonos") }
                AddOrSavedButton(vm, st)
            })
            HorizontalDivider()
        }
    }
}

@Composable
private fun AddUrlTab(vm: AppViewModel, sharedUrl: MutableState<String?>) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<StreamCheck.Result?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sharedUrl.value) {
        sharedUrl.value?.let { url = it; result = null; sharedUrl.value = null }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(url, { url = it; result = null }, label = { Text("Stream URL") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = url.isNotBlank() && !checking, onClick = {
                checking = true
                scope.launch {
                    val r = vm.checkStream(url)
                    result = r
                    if (name.isBlank() && r.stationName.isNotBlank()) name = r.stationName
                    checking = false
                }
            }) { Text(if (checking) "Checking…" else "Check stream") }
            Button(enabled = url.isNotBlank() && name.isNotBlank(), onClick = {
                vm.addStation(Station(name = name.trim(), url = url.trim(), source = "manual"))
                name = ""; url = ""; result = null
            }) { Text("Add") }
        }
        result?.let {
            Text(
                (if (it.ok) "✓ " else "✗ ") + it.message,
                color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Finding stream links", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Use the audio stream itself, not the station's web page. On fmstream.org, a station's " +
                        "stream links end in things like .mp3, .aac, /stream, /listen, .pls or .m3u. On a station " +
                        "website, look for 'Listen' or 'Other players'.\n\n" +
                        "Shortcut: in your browser, long-press a stream link, choose Share, and pick Longwave. " +
                        "The link lands here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Speakers

@Composable
fun SpeakersScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshStatus() }
    Column(Modifier.fillMaxSize()) {
        Header("Speakers", vm.busy) {
            IconButton(onClick = { vm.refreshAll() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (vm.speakers.isEmpty() && !vm.busy) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No speakers found. The phone must be on the same Wi-Fi network as your Sonos system.")
                Button(onClick = { vm.refreshAll() }) { Text("Search again") }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.speakers, key = { it.uuid }) { s ->
                val st = vm.status[s.uuid]
                val leader = vm.speakers.firstOrNull { it.uuid == s.coordinatorUuid }
                var vol by remember(s.uuid, st?.volume) { mutableFloatStateOf((st?.volume ?: 0).toFloat()) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (st?.state == "PLAYING" || st?.state == "TRANSITIONING")
                                IconButton(onClick = { vm.stop(s) }) { Icon(Icons.Filled.Stop, "Stop") }
                        }
                        val stateText = when (st?.state) {
                            null -> ""
                            "PLAYING" -> "Playing" + if (st.title.isNotBlank()) ": ${st.title}" else ""
                            "PAUSED_PLAYBACK" -> "Paused"
                            "STOPPED" -> "Stopped"
                            "TRANSITIONING" -> "Starting…"
                            else -> st.state
                        }
                        if (stateText.isNotBlank()) Text(stateText, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (leader != null && !s.isCoordinator) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Grouped with ${leader.name}", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.ungroup(s) }) { Text("Ungroup") }
                            }
                        }
                        Text("Volume ${vol.toInt()}", style = MaterialTheme.typography.bodySmall)
                        Slider(value = vol, onValueChange = { vol = it }, valueRange = 0f..100f,
                            onValueChangeFinished = { vm.setVolume(s, vol.toInt()) })
                    }
                }
            }
        }
    }
}

@Composable
fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
