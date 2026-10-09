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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.winlator.cmod.ui.theme.WinZTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** The programs of a Linux container: start one, add one from storage or from the runtime, remove one. */
class LinuxProgramsFragment : Fragment() {
    companion object {
        private const val ARG_CONTAINER_ID = "container_id"

        @JvmStatic
        fun forContainer(containerId: Int): LinuxProgramsFragment = LinuxProgramsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_CONTAINER_ID, containerId) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        val containerId = arguments?.getInt(ARG_CONTAINER_ID, -1) ?: -1
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                LinuxProgramsScreen(containerId) { parentFragmentManager.popBackStack() }
            }
        }
    }
}

private data class LinuxProgram(val name: String, val path: String, val file: File)

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

private fun directorySize(dir: File): Long =
    if (dir.isFile) dir.length() else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun formatSize(bytes: Long): String =
    if (bytes >= (1L shl 30)) String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    else "${bytes shr 20} MB"

@Composable
private fun LinuxProgramsScreen(containerId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { ContainerManager(context).getContainerById(containerId) }
    if (container == null) {
        onBack()
        return
    }
    val main = remember { Handler(Looper.getMainLooper()) }

    var tick by remember { mutableIntStateOf(0) }
    val programs = remember(tick) { readPrograms(container) }
    var menuOpen by remember { mutableStateOf(false) }
    var pathDialog by remember { mutableStateOf(false) }
    var pendingCopy by remember { mutableStateOf<File?>(null) }
    var pendingCopySize by remember { mutableStateOf(0L) }
    var copying by remember { mutableStateOf(false) }

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
        // Shared storage cannot execute programs, so the program's folder is copied into the container first.
        pendingCopySize = directorySize(file.parentFile ?: file)
        pendingCopy = file
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
        if (programs.isEmpty()) {
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
                            IconButton(onClick = {
                                program.file.delete()
                                tick++
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
            }
        }
    }

    if (pathDialog) {
        var name by remember { mutableStateOf("") }
        var path by remember { mutableStateOf("/usr/bin/") }
        val valid = path.startsWith("/") && path.length > 1
        AlertDialog(
            onDismissRequest = { pathDialog = false },
            title = { Text("Program in the Linux system") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(path, { path = it }, label = { Text("Path") }, singleLine = true)
                    OutlinedTextField(
                        name, { name = it },
                        label = { Text("Name (optional)") }, singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    ContainerManager.addLinuxShortcut(
                        container, name.trim().ifEmpty { File(path.trim()).name }, path.trim()
                    )
                    pathDialog = false
                    tick++
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pathDialog = false }) { Text("Cancel") } }
        )
    }

    pendingCopy?.let { file ->
        val folder = file.parentFile ?: file
        AlertDialog(
            onDismissRequest = { pendingCopy = null },
            title = { Text("Copy into the container?") },
            text = {
                Text(
                    "Programs cannot run from shared storage, so the folder \"${folder.name}\" " +
                        "(${formatSize(pendingCopySize)}) will be copied into this container."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCopy = null
                    copying = true
                    Executors.newSingleThreadExecutor().execute {
                        val target = File(File(container.linuxHomeDir, "programs"), folder.name)
                        var ok = false
                        try {
                            ok = FileUtils.copy(folder, target, Callback<File> { copied -> FileUtils.chmod(copied, 505) /* octal 0771 */ })
                        } catch (e: Exception) {
                            ok = false
                        }
                        main.post {
                            copying = false
                            if (ok) {
                                ContainerManager.addLinuxShortcut(
                                    container, file.nameWithoutExtension, "/root/programs/${folder.name}/${file.name}"
                                )
                                tick++
                            } else {
                                Toast.makeText(context, "Copying failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { pendingCopy = null }) { Text("Cancel") } }
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
