package com.winlator.cmod.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.container.ContainerProfile
import com.winlator.cmod.contents.ContainerCatalog
import com.winlator.cmod.contents.Downloader
import com.winlator.cmod.ui.settings.ContainersSettingsActivity
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "For a game": pick a game, then a game version, then a ready-made config, and create the container. */
class GameContainerFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                GameContainerScreen(
                    onBack = { parentFragmentManager.popBackStack() },
                    onImport = { (activity as? ContainersSettingsActivity)?.importEnvelope(it) }
                )
            }
        }
    }
}

private sealed interface CatalogState {
    data object Loading : CatalogState
    data object Failed : CatalogState
    class Ready(val entries: List<ContainerCatalog.Entry>) : CatalogState
}

private class GameItem(val id: String, val name: String, val versions: Int)

private class PendingImport(val envelope: ContainerProfile.Envelope, val defaultName: String)

/** Newest first when used as compareVersions(b, a). Numeric parts compare as numbers. */
private fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.', '-', '_')
    val pb = b.split('.', '-', '_')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrNull(i) ?: return -1
        val y = pb.getOrNull(i) ?: return 1
        val nx = x.toIntOrNull()
        val ny = y.toIntOrNull()
        val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
        if (c != 0) return c
    }
    return 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameContainerScreen(onBack: () -> Unit, onImport: (ContainerProfile.Envelope) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<CatalogState>(CatalogState.Loading) }
    LaunchedEffect(reload) {
        state = CatalogState.Loading
        val entries = withContext(Dispatchers.IO) { ContainerCatalog.load(context) }
        state = if (entries == null) CatalogState.Failed else CatalogState.Ready(entries)
    }

    // The three picks. Changing one clears everything to its right.
    var gameId by rememberSaveable { mutableStateOf<String?>(null) }
    var version by rememberSaveable { mutableStateOf<String?>(null) }
    var configId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }

    val entries = (state as? CatalogState.Ready)?.entries.orEmpty()
    val games = remember(entries) {
        entries.groupBy { it.gameId }.map { (id, list) ->
            GameItem(id, list.first().gameName, list.map { it.gameVersion }.distinct().size)
        }.sortedBy { it.name.lowercase() }
    }
    fun versionsOf(id: String?) = entries.filter { it.gameId == id }.map { it.gameVersion }.distinct()
        .sortedWith { a, b -> compareVersions(b, a) }
    val versions = remember(entries, gameId) { versionsOf(gameId) }
    val configs = remember(entries, gameId, version) {
        entries.filter { it.gameId == gameId && it.gameVersion == version }.sortedBy { it.name.lowercase() }
    }
    val selectedConfig = configs.firstOrNull { it.id == configId }
    val gameName = games.firstOrNull { it.id == gameId }?.name

    fun selectGame(id: String) {
        gameId = id
        configId = null
        version = versionsOf(id).singleOrNull()
    }
    fun selectVersion(v: String) {
        version = v
        configId = null
    }
    fun resetTo(step: Int) {
        if (step <= 1) gameId = null
        if (step <= 2) version = null
        configId = null
    }

    fun create(config: ContainerCatalog.Entry) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val text = Downloader.downloadString(config.url)
                    ?: return@withContext Result.failure<ContainerProfile.Envelope>(Exception("Couldn't download the config"))
                runCatching { ContainerProfile.parse(text) }
            }
            busy = false
            result.onSuccess { pending = PendingImport(it, it.container.optString("name", "").ifBlank { config.name }) }
                .onFailure { Toast.makeText(context, "Invalid container profile: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("For a game") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } }
            )
        },
        bottomBar = {
            if (state is CatalogState.Ready) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Button(
                        onClick = { selectedConfig?.let(::create) },
                        enabled = selectedConfig != null && !busy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (busy) "Downloading config…" else "Create container")
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                CatalogState.Loading -> StatusCard(loading = true, "Loading game configs…")
                CatalogState.Failed -> StatusCard(loading = false, "Couldn't load the game list. Check your connection.") {
                    OutlinedButton(onClick = { reload++ }) { Text("Retry") }
                }
                is CatalogState.Ready -> if (s.entries.isEmpty()) {
                    StatusCard(loading = false, "No game configs published yet.")
                } else if (landscape) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            ColumnHeader("Game")
                            SearchField(query) { query = it }
                            Spacer(Modifier.height(8.dp))
                            GameList(games, gameId, query, Modifier.weight(1f)) { selectGame(it) }
                        }
                        Column(Modifier.weight(.8f).fillMaxHeight()) {
                            ColumnHeader("Version")
                            if (gameId == null) Hint("Choose a game")
                            else VersionList(versions, version, entries, gameId, Modifier.weight(1f)) { selectVersion(it) }
                        }
                        Column(Modifier.weight(1.4f).fillMaxHeight()) {
                            ColumnHeader("Config")
                            if (version == null) Hint(if (gameId == null) "Choose a game" else "Choose a version")
                            else ConfigList(configs, configId, Modifier.weight(1f)) { configId = it }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Breadcrumb(gameName, version, selectedConfig?.name, ::resetTo)
                        Spacer(Modifier.height(10.dp))
                        when {
                            gameId == null -> {
                                SearchField(query) { query = it }
                                Spacer(Modifier.height(8.dp))
                                GameList(games, gameId, query, Modifier.weight(1f)) { selectGame(it) }
                            }
                            version == null -> VersionList(versions, version, entries, gameId, Modifier.weight(1f)) { selectVersion(it) }
                            else -> ConfigList(configs, configId, Modifier.weight(1f)) { configId = it }
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        ConfirmDialog(
            p,
            onDismiss = { pending = null },
            onConfirm = { name ->
                p.envelope.container.put("name", name)
                pending = null
                onImport(p.envelope)
            }
        )
    }
}

@Composable
private fun Breadcrumb(game: String?, version: String?, config: String?, resetTo: (Int) -> Unit) {
    val step = if (game == null) 1 else if (version == null) 2 else 3
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StepChip("1  ${game ?: "Game"}", active = step == 1, done = game != null) { resetTo(1) }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        StepChip("2  ${version ?: "Version"}", active = step == 2, done = version != null, enabled = game != null) { resetTo(2) }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        StepChip("3  ${config ?: "Config"}", active = step == 3, done = config != null, enabled = false) {}
    }
}

@Composable
private fun StepChip(label: String, active: Boolean, done: Boolean, enabled: Boolean = done, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ColumnHeader(text: String) {
    Text(
        text,
        Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search games") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Clear") }
        },
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun GameList(games: List<GameItem>, selected: String?, query: String, modifier: Modifier, onSelect: (String) -> Unit) {
    val q = query.trim().lowercase()
    val visible = games.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visible.isEmpty()) item { Hint("No games match.") }
        items(visible, key = { it.id }) {
            PickRow(it.name, if (it.versions == 1) "1 version" else "${it.versions} versions", it.id == selected) { onSelect(it.id) }
        }
    }
}

@Composable
private fun VersionList(
    versions: List<String>,
    selected: String?,
    entries: List<ContainerCatalog.Entry>,
    gameId: String?,
    modifier: Modifier,
    onSelect: (String) -> Unit
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(versions, key = { it }) { v ->
            val count = entries.count { it.gameId == gameId && it.gameVersion == v }
            val latest = if (v == versions.first()) " · latest" else ""
            PickRow(v, (if (count == 1) "1 config" else "$count configs") + latest, v == selected) { onSelect(v) }
        }
    }
}

@Composable
private fun ConfigList(configs: List<ContainerCatalog.Entry>, selected: String?, modifier: Modifier, onSelect: (String) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(configs, key = { it.id }) { c ->
            val isSelected = c.id == selected
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(c.id) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(selected = isSelected, onClick = { onSelect(c.id) })
                    Column(Modifier.weight(1f).padding(start = 4.dp, top = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.name,
                                Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (c.channel != null) {
                                Spacer(Modifier.width(8.dp))
                                ChannelBadge(c.channel)
                            }
                        }
                        if (c.description.isNotEmpty()) {
                            Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (c.tags.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                c.tags.forEach { Tag(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun PickRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(loading: Boolean, message: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                }
                Text(message)
                if (action != null) {
                    Spacer(Modifier.height(12.dp))
                    action()
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(pending: PendingImport, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(pending.defaultName) }
    val components = remember(pending) {
        (0 until pending.envelope.components.length()).mapNotNull { i ->
            val c = pending.envelope.components.optJSONObject(i) ?: return@mapNotNull null
            val type = c.optString("type")
            val version = c.optString("version")
            if (type.isEmpty() || version.isEmpty()) null else "$type  $version"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create container") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Container name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (components.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Uses", style = MaterialTheme.typography.labelLarge)
                    components.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Missing components are downloaded automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
