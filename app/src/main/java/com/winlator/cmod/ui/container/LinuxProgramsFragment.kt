package com.winlator.cmod.ui.container

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.Callback
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.gameprofile.GameProfile
import com.winlator.cmod.gameprofile.GameProfileCatalog
import com.winlator.cmod.gameprofile.GameProfiles
import com.winlator.cmod.linux.LinuxEmulator
import com.winlator.cmod.linux.LinuxSession
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** The programs of a Linux container: start one, add one from storage or from the runtime, remove one. */
class LinuxProgramsFragment : Fragment() {
    companion object {
        private const val ARG_CONTAINER_ID = "container_id"
        private const val ARG_PICK_ON_OPEN = "pick_on_open"

        @JvmStatic
        fun forContainer(containerId: Int): LinuxProgramsFragment = forContainer(containerId, false)

        /** With [pickOnOpen] the storage picker opens at once, for a caller that already knows a program is to be added. */
        @JvmStatic
        fun forContainer(containerId: Int, pickOnOpen: Boolean): LinuxProgramsFragment = LinuxProgramsFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_CONTAINER_ID, containerId)
                putBoolean(ARG_PICK_ON_OPEN, pickOnOpen)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        val containerId = arguments?.getInt(ARG_CONTAINER_ID, -1) ?: -1
        val pickOnOpen = savedInstanceState == null && arguments?.getBoolean(ARG_PICK_ON_OPEN, false) == true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                LinuxProgramsScreen(containerId, pickOnOpen) { parentFragmentManager.popBackStack() }
            }
        }
    }
}

private data class LinuxProgram(val name: String, val path: String, val file: File)

/** A program whose name is taken by another shortcut of the container, waiting for the user to name it. */
private data class NameClash(val name: String, val path: String, val extras: Map<String, String>? = null)

/** The registry's game (and the version of it, "" to recognise it) a new program is added with; empty is none. */
private data class ProfileChoice(val gameId: String = "", val versionId: String = "")

/**
 * Picks a game of the registry for a program that is being added, and its version. [onProfile] is told the game's profile once it
 * is read (null for none), so the dialog can show what the program will be.
 */
@Composable
private fun ProfilePicker(
    games: List<GameProfileCatalog.IndexEntry>,
    choice: ProfileChoice,
    onChoice: (ProfileChoice) -> Unit,
    onProfile: (GameProfile?) -> Unit
) {
    val context = LocalContext.current
    var profile by remember(choice.gameId) { mutableStateOf<GameProfile?>(null) }
    LaunchedEffect(choice.gameId) {
        profile = if (choice.gameId.isEmpty()) null else withContext(Dispatchers.IO) {
            GameProfileCatalog.game(context, choice.gameId, false) ?: GameProfileCatalog.game(context, choice.gameId, true)
        }
        onProfile(profile)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingMappedChoice(
            "Game profile",
            choice.gameId,
            LinkedHashMap<String, String>().apply {
                put("", "None")
                games.forEach { put(it.id, it.name) }
            }
        ) { onChoice(ProfileChoice(it, "")) }
        profile?.let { current ->
            SettingMappedChoice(
                "Version",
                choice.versionId,
                LinkedHashMap<String, String>().apply {
                    put("", "Recognise it")
                    current.versions.forEach { put(it.id, it.label) }
                }
            ) { onChoice(choice.copy(versionId = it)) }
            Text(
                "The profile sets the program's arguments, the resolution and what works for this version of the game.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A folder name that does not exist in [parent] yet: [name], or [name]-2, [name]-3 and so on. */
private fun freeFolder(parent: File, name: String): File {
    var candidate = File(parent, name)
    var n = 2
    while (candidate.exists()) candidate = File(parent, "$name-${n++}")
    return candidate
}

private fun readPrograms(container: Container): List<LinuxProgram> {
    val files = container.desktopDir.listFiles { f -> f.name.endsWith(".desktop") } ?: return emptyList()
    return files.sortedBy { it.name.lowercase(Locale.ROOT) }.map { file ->
        var name = file.nameWithoutExtension
        var path = ""
        for (line in FileUtils.readLines(file)) {
            if (line.startsWith("Name=")) name = line.removePrefix("Name=").trim()
            else if (line.startsWith("Exec=")) path = line.removePrefix("Exec=").trim()
        }
        LinuxProgram(name, path, file)
    }
}

/** The shared-storage path of a document the user picked, or null when it is not a plain file on storage. */
private fun documentPath(uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getDocumentId(uri)
        when {
            docId.startsWith("raw:") -> docId.removePrefix("raw:")
            uri.authority == "com.android.externalstorage.documents" -> {
                val parts = docId.split(":", limit = 2)
                val root = if (parts[0] == "primary") Environment.getExternalStorageDirectory().path else "/storage/${parts[0]}"
                if (parts.size == 2) "$root/${parts[1]}" else null
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** The folder of the container's programs/ that a program was copied into, or null for a program that lives elsewhere. */
private fun copiedFolder(container: Container, path: String): File? {
    val prefix = "/root/programs/"
    if (!path.startsWith(prefix)) return null
    val name = path.removePrefix(prefix).substringBefore('/')
    if (name.isEmpty()) return null
    return File(container.linuxHomeDir, "programs/$name").takeIf { it.isDirectory }
}

/** Copied folders that no shortcut starts a program from: what a copy that failed or was cut short leaves behind. */
private fun unusedCopies(container: Container, programs: List<LinuxProgram>): List<File> {
    val used = programs.mapNotNull { copiedFolder(container, it.path)?.name }.toSet()
    val all = File(container.linuxHomeDir, "programs").listFiles { f -> f.isDirectory } ?: return emptyList()
    return all.filter { it.name !in used }.sortedBy { it.name.lowercase(Locale.ROOT) }
}

private fun directorySize(dir: File): Long =
    if (dir.isFile) dir.length() else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun formatSize(bytes: Long): String =
    if (bytes >= (1L shl 30)) String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    else "${bytes shr 20} MB"

@Composable
private fun LinuxProgramsScreen(containerId: Int, pickOnOpen: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { ContainerManager(context).getContainerById(containerId) }
    if (container == null) {
        onBack()
        return
    }
    val main = remember { Handler(Looper.getMainLooper()) }

    var tick by remember { mutableIntStateOf(0) }
    // The registry's games for the "Game profile" choice: what is on hand at once, then what the network has.
    var games by remember { mutableStateOf<List<GameProfileCatalog.IndexEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        games = withContext(Dispatchers.IO) { GameProfileCatalog.index(context, false) }
        games = withContext(Dispatchers.IO) { GameProfileCatalog.index(context, true) }
    }
    var pendingDirect by remember { mutableStateOf<File?>(null) }
    val programs = remember(tick) { readPrograms(container) }
    var menuOpen by remember { mutableStateOf(false) }
    var pathDialog by remember { mutableStateOf(false) }
    var pendingCopy by remember { mutableStateOf<File?>(null) }
    var pendingCopySize by remember { mutableStateOf(0L) }
    var copying by remember { mutableStateOf(false) }
    var nameClash by remember { mutableStateOf<NameClash?>(null) }
    var removing by remember { mutableStateOf<LinuxProgram?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val unused = remember(tick) { unusedCopies(container, programs) }

    /** Deleting a game's files can take a while (tens of GB), so it runs on its own thread. */
    fun deleteFolders(folders: List<File>) {
        deleting = true
        Thread {
            folders.forEach { FileUtils.delete(it) }
            main.post {
                deleting = false
                tick++
            }
        }.start()
    }

    /**
     * The game whose profile names this program as its executable (the path ends with it), for a program picked from storage: the
     * registry knows BeamNG's is BinLinux/BeamNG.drive.x64, so a file picked there is that game.
     */
    fun detectChoice(path: String): ProfileChoice {
        for (entry in games) {
            val profile = GameProfileCatalog.game(context, entry.id, false) ?: continue
            val executable = profile.defaults.executable
            if (executable != null && path.endsWith("/$executable")) return ProfileChoice(entry.id, "")
        }
        return ProfileChoice()
    }

    /** What a program added with this profile starts with: the profile, the version, and the game's folder worked out from the path. */
    fun profileExtras(path: String, choice: ProfileChoice?): Map<String, String>? {
        if (choice == null || choice.gameId.isEmpty()) return null
        val profile = GameProfileCatalog.game(context, choice.gameId, false)
        val executable = profile?.effective(profile.version(choice.versionId))?.executable
        val root = if (executable != null && path.endsWith("/$executable")) path.removeSuffix("/$executable") else ""
        return mapOf(
            GameProfiles.EXTRA_PROFILE to choice.gameId,
            GameProfiles.EXTRA_VERSION to choice.versionId,
            GameProfiles.EXTRA_ROOT to root
        )
    }

    /** Adds the shortcut, or asks for another name when this one is taken: two games can share an executable's name. */
    fun addProgram(name: String, path: String, choice: ProfileChoice? = null) {
        val extras = profileExtras(path, choice)
        if (ContainerManager.linuxShortcutExists(container, name)) {
            nameClash = NameClash(name, path, extras)
        } else {
            ContainerManager.addLinuxShortcut(container, name, path, extras)
            tick++
        }
    }

    fun start(program: LinuxProgram) {
        context.startActivity(
            Intent().setClassName(context.packageName, "com.winlator.cmod.XServerDisplayActivity")
                .putExtra("container_id", container.id)
                .putExtra("shortcut_path", program.file.path)
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = documentPath(uri)
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
            Toast.makeText(
                context,
                "Cannot read this file's location. Pick it from \"Internal storage\" in the file browser.",
                Toast.LENGTH_LONG
            ).show()
            return@rememberLauncherForActivityResult
        }
        // A Linux container runs ELF programs and shared objects (.so is ELF too) and shell scripts, never a .exe.
        if (LinuxSession.elfMachine(file) == -1 && !file.name.endsWith(".sh")) {
            Toast.makeText(
                context,
                "Not a Linux program: pick an ELF executable, a .so or a .sh script.",
                Toast.LENGTH_LONG
            ).show()
            return@rememberLauncherForActivityResult
        }
        if (LinuxSession.elfMachine(file) == 62 && LinuxEmulator.choice(container) == LinuxEmulator.BOX64) {
            // Box64 reads an x86-64 program instead of mapping it, so it can stay on shared storage. FEX maps the program
            // and its libraries into memory as executable, which shared storage refuses: they are copied like ARM64 ones.
            if (detectChoice(file.path).gameId.isNotEmpty()) pendingDirect = file
            else addProgram(file.nameWithoutExtension, file.path)
            return@rememberLauncherForActivityResult
        }
        // Shared storage cannot execute programs, so the program's folder is copied into the container first.
        pendingCopySize = directorySize(file.parentFile ?: file)
        pendingCopy = file
    }

    var autoPicked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (pickOnOpen && !autoPicked) {
            autoPicked = true
            picker.launch(arrayOf("*/*"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(container.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.Add, "Add program") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("From storage…") },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                        onClick = {
                            menuOpen = false
                            picker.launch(arrayOf("*/*"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("From the Linux system…") },
                        leadingIcon = { Icon(Icons.Outlined.Terminal, null) },
                        onClick = {
                            menuOpen = false
                            pathDialog = true
                        }
                    )
                }
            }
        }
    ) { padding ->
        if (programs.isEmpty() && unused.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No programs yet. Use + to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(programs, key = { it.file.path }) { program ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(program.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    program.path,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // The container's own Steam shortcut goes with the container, not by hand.
                            if (!LinuxSession.isSteam(program.path)) IconButton(onClick = {
                                if (copiedFolder(container, program.path) != null) removing = program
                                else {
                                    program.file.delete()
                                    tick++
                                }
                            }) { Icon(Icons.Outlined.DeleteOutline, "Remove") }
                            Surface(
                                onClick = { start(program) },
                                modifier = Modifier.size(44.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, "Start") } }
                        }
                    }
                }
                items(unused, key = { "unused:" + it.path }) { folder ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Copied files without a program", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "programs/${folder.name}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(enabled = !deleting, onClick = { deleteFolders(listOf(folder)) }) {
                                Icon(Icons.Outlined.DeleteOutline, "Delete the files")
                            }
                        }
                    }
                }
            }
        }
    }

    if (pathDialog) {
        var name by remember { mutableStateOf("") }
        var path by remember { mutableStateOf("/usr/bin/") }
        var choice by remember { mutableStateOf(ProfileChoice()) }
        var profile by remember { mutableStateOf<GameProfile?>(null) }
        // With a game profile the path is the game's folder and the program is the profile's executable inside it.
        val executable = profile?.effective(profile?.version(choice.versionId))?.executable
        val finalPath = if (choice.gameId.isNotEmpty() && executable != null) path.trim().trimEnd('/') + "/" + executable else path.trim()
        val valid = path.startsWith("/") && path.length > 1
        AlertDialog(
            onDismissRequest = { pathDialog = false },
            title = { Text("Program in the Linux system") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfilePicker(games, choice, { choice = it }) { profile = it }
                    OutlinedTextField(
                        path, { path = it },
                        label = { Text(if (choice.gameId.isEmpty()) "Path" else "Game folder") },
                        placeholder = { if (choice.gameId.isNotEmpty()) Text("/root/programs/the_game") },
                        singleLine = true
                    )
                    if (choice.gameId.isNotEmpty() && executable != null) {
                        Text(
                            "Program: $finalPath",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        name, { name = it },
                        label = { Text("Name (optional)") }, singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    val defaultName = if (choice.gameId.isNotEmpty()) profile?.name ?: File(finalPath).name else File(finalPath).name
                    addProgram(name.trim().ifEmpty { defaultName }, finalPath, choice)
                    pathDialog = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pathDialog = false }) { Text("Cancel") } }
        )
    }

    pendingDirect?.let { file ->
        // A program on storage that is a game of the registry (Box64 runs it where it is): add it with the game's profile, or without.
        var choice by remember(file) { mutableStateOf(detectChoice(file.path)) }
        AlertDialog(
            onDismissRequest = { pendingDirect = null },
            title = { Text("Add \"${file.nameWithoutExtension}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This looks like a game the registry has a profile for.")
                    ProfilePicker(games, choice, { choice = it }) { }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDirect = null
                    addProgram(file.nameWithoutExtension, file.path, choice)
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pendingDirect = null }) { Text("Cancel") } }
        )
    }

    pendingCopy?.let { file ->
        val folder = file.parentFile ?: file
        // A game keeps its data beside the folder of its program (BeamNG's BinLinux sits next to content, lua, ...) and
        // finds it from the program's path, so the folder alone is not always enough: the game's folder can be copied too.
        // Its virtual file system does not follow symbolic links, so a link back to the data on shared storage will not do.
        val gameFolder = folder.parentFile?.takeIf { folder != file && it.parentFile != null && it.name.isNotEmpty() }
        var copyChoice by remember(file, games) { mutableStateOf(detectChoice(file.path)) }
        var gameSize by remember(file) { mutableStateOf<Long?>(null) }
        LaunchedEffect(file) {
            if (gameFolder != null) {
                Thread { val size = directorySize(gameFolder); main.post { gameSize = size } }.start()
            }
        }

        fun copyAndAdd(source: File, programInside: String) {
            pendingCopy = null
            copying = true
            Executors.newSingleThreadExecutor().execute {
                // Another version of the game may already sit in a folder of this name: do not overwrite it.
                val programs = File(container.linuxHomeDir, "programs")
                val target = freeFolder(programs, source.name)
                var ok = false
                try {
                    ok = FileUtils.copy(source, target, Callback<File> { copied -> FileUtils.chmod(copied, 505) /* octal 0771 */ })
                } catch (e: Exception) {
                    ok = false
                }
                if (!ok) FileUtils.delete(target)
                main.post {
                    copying = false
                    if (ok) {
                        addProgram(file.nameWithoutExtension, "/root/programs/${target.name}/$programInside${file.name}", copyChoice)
                    } else {
                        Toast.makeText(context, "Copying failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        /** Copies only the program's folder; the rest of the game stays where it is and is bound in beside it. */
        fun copyProgramAndBindData(game: File, programFolder: File) {
            pendingCopy = null
            copying = true
            Executors.newSingleThreadExecutor().execute {
                val target = freeFolder(File(container.linuxHomeDir, "programs"), game.name)
                var ok = false
                try {
                    target.mkdirs()
                    ok = FileUtils.copy(programFolder, File(target, programFolder.name), Callback<File> { copied -> FileUtils.chmod(copied, 505) })
                    if (ok) {
                        // The places the data is bound to must exist, or the game does not see them when it lists the folder.
                        val binds = StringBuilder()
                        for (entry in game.listFiles().orEmpty()) {
                            if (entry.name == programFolder.name) continue
                            val place = File(target, entry.name)
                            if (entry.isDirectory) place.mkdirs() else place.createNewFile()
                            binds.append(entry.path).append('\t').append("/root/programs/${target.name}/${entry.name}").append('\n')
                        }
                        File(target, ".binds").writeText(binds.toString())
                    }
                } catch (e: Exception) {
                    ok = false
                }
                if (!ok) FileUtils.delete(target)
                main.post {
                    copying = false
                    if (ok) addProgram(file.nameWithoutExtension, "/root/programs/${target.name}/${programFolder.name}/${file.name}", copyChoice)
                    else Toast.makeText(context, "Copying failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        AlertDialog(
            onDismissRequest = { pendingCopy = null },
            title = { Text("Copy into the container?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Programs cannot run from shared storage, so the folder \"${folder.name}\" " +
                        "(${formatSize(pendingCopySize)}) will be copied into this container." +
                        if (gameFolder != null) "\n\nIf the game keeps its data next to this folder, copy the whole game folder \"${gameFolder.name}\" " +
                            "(${gameSize?.let { formatSize(it) } ?: "measuring..."}) instead, or only the program and read the rest from storage." else ""
                )
                    ProfilePicker(games, copyChoice, { copyChoice = it }) { }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (gameFolder != null) {
                        TextButton(onClick = { copyProgramAndBindData(gameFolder, folder) }) { Text("Program only, data stays on storage") }
                        TextButton(onClick = { copyAndAdd(gameFolder, "${folder.name}/") }) { Text("Whole game") }
                    }
                    TextButton(onClick = { copyAndAdd(folder, "") }) { Text("Folder only") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingCopy = null }) { Text("Cancel") } }
        )
    }

    removing?.let { program ->
        val folder = copiedFolder(container, program.path)
        // Another shortcut may start from the same copy (a game with two executables): its files stay while one is left.
        val shared = programs.any { it.file != program.file && copiedFolder(container, it.path)?.name == folder?.name }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${program.name}\"?") },
            text = {
                Text(
                    if (shared) "Another program uses the same copied files, so they stay."
                    else "Its files were copied into this container (programs/${folder?.name}). Delete them too?"
                )
            },
            confirmButton = {
                Row {
                    if (!shared && folder != null) {
                        TextButton(onClick = {
                            removing = null
                            program.file.delete()
                            deleteFolders(listOf(folder))
                        }) { Text("Remove and delete files") }
                    }
                    TextButton(onClick = {
                        removing = null
                        program.file.delete()
                        tick++
                    }) { Text(if (shared) "Remove" else "Keep files") }
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Deleting…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }

    nameClash?.let { clash ->
        // Offer a free name to start from: "Name (2)", "Name (3)" ...
        var newName by remember(clash) {
            var n = 2
            while (ContainerManager.linuxShortcutExists(container, "${clash.name} ($n)")) n++
            mutableStateOf("${clash.name} ($n)")
        }
        val taken = ContainerManager.linuxShortcutExists(container, newName.trim())
        AlertDialog(
            onDismissRequest = { nameClash = null },
            title = { Text("This program already exists") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("A shortcut named \"${clash.name}\" is already in this container. Give the new one a name.")
                    OutlinedTextField(
                        newName, { newName = it },
                        label = { Text("Name") }, singleLine = true, isError = taken
                    )
                    if (taken) Text("This name is taken.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank() && !taken, onClick = {
                    ContainerManager.addLinuxShortcut(container, newName.trim(), clash.path, clash.extras)
                    nameClash = null
                    tick++
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { nameClash = null }) { Text("Cancel") } }
        )
    }

    if (copying) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Copying…") },
            text = { CircularProgressIndicator() },
            confirmButton = {}
        )
    }
}
