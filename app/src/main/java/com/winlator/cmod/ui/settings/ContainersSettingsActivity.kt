package com.winlator.cmod.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.XrActivity
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerImporter
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.ContainerProfile
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.PreloaderDialog
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.linux.LinuxRuntime
import com.winlator.cmod.steam.SteamContainer
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.container.ContainerCreateComposeFragment
import com.winlator.cmod.ui.container.GameContainerFragment
import com.winlator.cmod.ui.container.NewContainerFragment
import com.winlator.cmod.ui.container.LinuxContainerCreateFragment
import com.winlator.cmod.ui.container.LinuxProgramsFragment
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

class ContainersSettingsActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private val containersState = mutableStateOf<List<Container>>(emptyList())
    private val propertiesState = mutableStateOf<Container?>(null)
    private var showingEditor = false

    private var pendingExportId = -1
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeExport(it) }
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { startImport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppFullscreen(this)
        root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0 && showingEditor) {
                showingEditor = false
                refresh()
                showHome()
            }
        }

        if (savedInstanceState == null) {
            showHome()
            // Started from the first-run screen with a way of making a container already chosen.
            intent.getStringExtra(EXTRA_START)?.let { startContainer(it) }
        }
    }

    /** Opens the screen for one way of making a container: "windows", "linux", "game" or "import"; "steam" opens the Steam settings. */
    fun startContainer(kind: String) {
        when (kind) {
            "windows" -> openFragment(ContainerCreateComposeFragment())
            "linux" -> openFragment(LinuxContainerCreateFragment())
            "steam" -> openFragment(LinuxContainerCreateFragment.forSteam())
            "game" -> openFragment(GameContainerFragment())
            "import" -> importContainer()
        }
    }

    companion object {
        /** "linux", "game", "import" or "steam": the screen to open straight away. */
        const val EXTRA_START = "start"
    }

    override fun onResume() {
        super.onResume()
        applyAppFullscreen(this)
        refresh()
    }

    private fun showHome() {
        root.removeAllViews()
        root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                WinZTheme {
                    ContainersSettingsScreen(
                        containers = containersState.value,
                        propertiesContainer = propertiesState.value,
                        onBack = { finish() },
                        onAdd = { openFragment(NewContainerFragment()) },
                        onEdit = { id -> editContainer(id) },
                        onProperties = { id ->
                            propertiesState.value = ContainerManager(this@ContainersSettingsActivity).getContainerById(id)
                        },
                        onDismissProperties = { propertiesState.value = null },
                        onRun = ::runContainer,
                        onDuplicate = ::duplicateContainer,
                        onRemove = ::removeContainer,
                        onExport = ::exportContainer
                    )
                }
            }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun openFragment(fragment: androidx.fragment.app.Fragment) {
        propertiesState.value = null
        showingEditor = true
        supportFragmentManager.beginTransaction()
            .replace(root.id, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun refresh() {
        containersState.value = ContainerManager(this).containers.toList()
    }

    private fun duplicateContainer(id: Int) {
        val manager = ContainerManager(this)
        val container = manager.getContainerById(id) ?: return
        manager.duplicateContainerAsync(container) {
            refresh()
            Toast.makeText(this, "Container duplicated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeContainer(id: Int) {
        val manager = ContainerManager(this)
        val container = manager.getContainerById(id) ?: return
        AppCompatAlertDialog.Builder(this)
            .setTitle("Remove container?")
            .setMessage("${container.name} and its container files will be deleted.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Remove") { _, _ ->
                manager.removeContainerAsync(container) {
                    refresh()
                    Toast.makeText(this, "Container removed", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun editContainer(id: Int) {
        val container = ContainerManager(this).getContainerById(id)
        openFragment(
            if (SteamContainer.isManaged(container)) LinuxContainerCreateFragment.forSteam()
            else if (container?.isLinux == true) LinuxContainerCreateFragment.forEdit(id)
            else ContainerCreateComposeFragment.forEdit(id)
        )
    }

    private fun runContainer(id: Int) {
        if (ContainerManager(this).getContainerById(id)?.isLinux == true) {
            // A Linux container starts one of its programs, so ▶ opens the program list.
            openFragment(LinuxProgramsFragment.forContainer(id))
            return
        }
        if (!XrActivity.isEnabled(this)) {
            startActivity(Intent(this, XServerDisplayActivity::class.java).putExtra("container_id", id))
        } else {
            XrActivity.openIntent(this, id, null)
        }
    }

    // ---- Import / export ---------------------------------------------------

    private fun appVersionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun exportContainer(id: Int) {
        val container = ContainerManager(this).getContainerById(id) ?: return
        pendingExportId = id
        exportLauncher.launch(ContainerProfile.suggestedFileName(container))
    }

    private fun writeExport(uri: Uri) {
        val container = ContainerManager(this).getContainerById(pendingExportId) ?: return
        try {
            val json = ContainerProfile.export(container, appVersionName(), this)
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Cannot open destination")
            Toast.makeText(this, "Exported ${container.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importContainer() {
        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
    }

    private fun startImport(uri: Uri) {
        val json = try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) { null }
        if (json.isNullOrEmpty()) {
            Toast.makeText(this, "Couldn't read the profile file", Toast.LENGTH_LONG).show()
            return
        }
        val envelope = try {
            ContainerProfile.parse(json)
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid container profile: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        importEnvelope(envelope)
    }

    /** Runs the container import for an already-parsed profile (from a file or from the game list). */
    internal fun importEnvelope(envelope: ContainerProfile.Envelope) {
        if (envelope.container.optString("type") != Container.TYPE_LINUX) {
            runImport(envelope, null)
            return
        }
        // A Linux container needs its runtime, driver and emulator: the user sees what is missing and confirms the download.
        val preloader = PreloaderDialog(this)
        preloader.showOnUiThread(com.winlator.cmod.R.string.loading)
        Thread {
            val plan = ContainerImporter.planLinux(this, envelope.container)
            runOnUiThread {
                preloader.closeOnUiThread()
                if (plan.install.isEmpty()) {
                    runImport(envelope, plan)
                    return@runOnUiThread
                }
                val total = plan.install.sumOf { it.size }
                val lines = plan.install.joinToString("\n") { "• ${it.name}" + if (it.size > 0) " (${formatDownloadSize(it.size)})" else "" }
                val message = StringBuilder("The following components will be installed:\n\n").append(lines)
                if (total > 0) message.append("\n\nTotal download: ").append(formatDownloadSize(total))
                if (plan.unavailable.isNotEmpty())
                    message.append("\n\nNot in the catalog, so not installed:\n")
                        .append(plan.unavailable.joinToString("\n") { "• $it" })
                AppCompatAlertDialog.Builder(this)
                    .setTitle("Install components")
                    .setMessage(message.toString())
                    .setPositiveButton("Download and install") { _, _ -> runImport(envelope, plan) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(false)
                    .show()
            }
        }.start()
    }

    private fun formatDownloadSize(bytes: Long): String =
        if (bytes >= (1L shl 30)) String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else String.format(java.util.Locale.US, "%d MB", bytes shr 20)

    /** One line of the install dialog: a component's name, its progress bar and what it is doing. */
    private class ComponentRow(val bar: android.widget.ProgressBar, val state: android.widget.TextView)

    /** A dialog with a progress bar for each component about to be installed; null when there is nothing to install. */
    private fun showInstallDialog(plan: ContainerImporter.LinuxPlan?): Pair<AppCompatAlertDialog, Map<String, ComponentRow>>? {
        if (plan == null || plan.install.isEmpty()) return null
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
        }
        val rows = LinkedHashMap<String, ComponentRow>()
        for (entry in plan.install) {
            val name = android.widget.TextView(this).apply {
                text = entry.name + if (entry.size > 0) " (${formatDownloadSize(entry.size)})" else ""
                textSize = 15f
            }
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            val state = android.widget.TextView(this).apply {
                text = "Waiting"
                textSize = 12f
                alpha = 0.7f
            }
            list.addView(name)
            list.addView(bar, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            list.addView(state)
            (state.layoutParams as android.widget.LinearLayout.LayoutParams).bottomMargin = dp(14)
            rows[entry.id] = ComponentRow(bar, state)
        }
        val dialog = AppCompatAlertDialog.Builder(this)
            .setTitle("Installing components")
            .setView(list)
            .setCancelable(false)
            .create()
        dialog.show()
        return dialog to rows
    }

    private fun runImport(envelope: ContainerProfile.Envelope, plan: ContainerImporter.LinuxPlan?) {
        val install = showInstallDialog(plan)
        val preloader = PreloaderDialog(this)
        if (install == null) preloader.showOnUiThread(com.winlator.cmod.R.string.loading)
        Thread {
            val manager = ContainerManager(this)
            val contents = ContentsManager(this)
            contents.syncContents()
            val progress = object : ContainerImporter.Progress {
                override fun status(message: String) {
                    if (install == null) preloader.setStatusText(message)
                }

                override fun component(id: String, state: String, percent: Int) {
                    val row = install?.second?.get(id) ?: return
                    runOnUiThread {
                        if (percent >= 0) row.bar.progress = percent
                        row.state.text = if (percent in 0..99 && state != "Starting") "$state $percent%" else state
                    }
                }
            }
            val result = ContainerImporter.run(this, manager, contents, envelope, progress, plan)
            runOnUiThread {
                install?.first?.dismiss()
                preloader.closeOnUiThread()
                // Leave the game list (if that is where the import started) before reporting.
                if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
                refresh()
                showImportSummary(result)
            }
        }.start()
    }

    private fun showImportSummary(result: ContainerImporter.Result) {
        val ok = result.container != null
        val title = if (ok) "Import complete" else "Import failed"
        val body = StringBuilder()
        if (ok) body.append("Created \"").append(result.container!!.name).append("\".")
        if (result.warnings.isNotEmpty()) {
            if (body.isNotEmpty()) body.append("\n\n")
            body.append(result.warnings.joinToString("\n• ", prefix = "• "))
        }
        if (body.isEmpty()) body.append(if (ok) "Container created." else "Could not create the container.")
        AppCompatAlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }
}

@Composable
private fun ContainersSettingsScreen(
    containers: List<Container>,
    propertiesContainer: Container?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onProperties: (Int) -> Unit,
    onDismissProperties: () -> Unit,
    onRun: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onExport: (Int) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Containers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Outlined.Add, "Add container") }
        }
    ) { padding ->
        if (containers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(74.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(36.dp)) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("No containers", style = MaterialTheme.typography.titleLarge)
                    Text("Create a Windows environment for your games.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(containers, key = { it.id }) { container ->
                    SettingsContainerCard(container, onEdit, onProperties, onRun, onDuplicate, onRemove, onExport)
                }
            }
        }
    }

    propertiesContainer?.let {
        ContainerPropertiesDialog(it, onDismissProperties)
    }
}

@Composable
private fun SettingsContainerCard(
    container: Container,
    onEdit: (Int) -> Unit,
    onProperties: (Int) -> Unit,
    onRun: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onExport: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(50.dp), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (container.isLinux) Icons.Outlined.Terminal else Icons.Outlined.Dns, null, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(container.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val subtitle = if (container.isLinux) {
                        val context = LocalContext.current
                        val runtime = remember(container.id, container.linuxRuntime) {
                            LinuxRuntime.resolve(context, container)?.name ?: "runtime missing"
                        }
                        "Linux • $runtime • ${container.screenSize}"
                    } else "${container.wineVersion} • ${container.screenSize}"
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (SteamContainer.isManaged(container)) {
                        // The Steam container: a badge and how many games Steam has installed in it.
                        val games = com.winlator.cmod.linux.LinuxSteamGames.count(container)
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    "STEAM",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                if (games == 0) "no games installed" else if (games == 1) "1 game" else "$games games",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Surface(
                    onClick = { onRun(container.id) },
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, null) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton(Icons.Outlined.Edit, "Edit", Modifier.weight(1f)) { onEdit(container.id) }
                ActionButton(Icons.Outlined.Info, "Properties", Modifier.weight(1f)) { onProperties(container.id) }
                // The Steam container is the app's own: it is reset from its settings, and has nothing to duplicate, remove or export here.
                if (SteamContainer.isManaged(container)) Spacer(Modifier.weight(1f))
                else ContainerMoreButton(
                    modifier = Modifier.weight(1f),
                    onDuplicate = { onDuplicate(container.id) },
                    onRemove = { onRemove(container.id) },
                    onExport = { onExport(container.id) },
                    canExport = !container.isLinux
                )
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Surface(
        onClick = action,
        modifier = modifier,
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContainerMoreButton(
    modifier: Modifier = Modifier,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onExport: () -> Unit,
    canExport: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        ActionButton(Icons.Outlined.MoreVert, "More", Modifier.fillMaxWidth()) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Duplicate") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = {
                    expanded = false
                    onDuplicate()
                }
            )
            if (canExport) DropdownMenuItem(
                text = { Text("Export settings") },
                leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                onClick = {
                    expanded = false
                    onExport()
                }
            )
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                onClick = {
                    expanded = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun ContainerPropertiesDialog(container: Container, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember(container.id) { mutableIntStateOf(0) }
    var loading by remember(container.id) { mutableStateOf(true) }
    var clearing by remember(container.id) { mutableStateOf(false) }
    var driveSize by remember(container.id) { mutableStateOf(0L) }
    var cacheSize by remember(container.id) { mutableStateOf(0L) }
    var totalSize by remember(container.id) { mutableStateOf(0L) }

    val rootDir = container.rootDir
    val driveCDir = remember(container.id) { File(rootDir, ".wine/drive_c") }
    val cacheDir = remember(container.id) { File(rootDir, ".cache") }

    LaunchedEffect(container.id, refreshKey) {
        loading = true
        val sizes = withContext(Dispatchers.IO) {
            Triple(
                directorySize(driveCDir),
                directorySize(cacheDir),
                directorySize(rootDir)
            )
        }
        driveSize = sizes.first
        cacheSize = sizes.second
        totalSize = sizes.third
        loading = false
    }

    val storageSize = FileUtils.getInternalStorageSize().coerceAtLeast(1L)
    val usedPercent = ((totalSize.toDouble() / storageSize.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Storage, null) },
        title = { Text("Container properties") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    container.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Calculating storage…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StorageValue("Drive C", driveSize)
                            StorageValue("Cache", cacheSize)
                            StorageValue("Total", totalSize, emphasize = true)
                        }
                        Surface(
                            modifier = Modifier.size(90.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$usedPercent%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text("storage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !clearing,
                onClick = {
                    if (!clearing) {
                        clearing = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                FileUtils.clear(cacheDir)
                                container.putExtra("desktopTheme", null)
                                container.saveData()
                            }
                            clearing = false
                            refreshKey++
                        }
                    }
                }
            ) {
                if (clearing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Clearing…")
                } else {
                    Text("Clear cache")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun StorageValue(label: String, bytes: Long, emphasize: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            StringUtils.formatBytes(bytes),
            style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

private fun directorySize(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return root.length().coerceAtLeast(0L)

    var total = 0L
    val stack = ArrayDeque<File>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (FileUtils.isSymlink(current)) continue
        val files = current.listFiles() ?: continue
        for (file in files) {
            if (file.isDirectory) {
                if (!FileUtils.isSymlink(file)) stack.add(file)
            } else {
                total += file.length().coerceAtLeast(0L)
            }
        }
    }
    return total
}
