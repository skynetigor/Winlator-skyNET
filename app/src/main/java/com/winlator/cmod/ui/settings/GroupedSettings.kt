package com.winlator.cmod.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// The layout the editors of a Linux container and of a Linux shortcut, and the app's Settings, share: groups of settings, listed
// beside the page of the chosen one when the room allows it, and stacked as sections that open and close when it does not.

/** The width, as given to [GroupedSettings] (not the screen's), from which the groups are listed beside the page. */
internal val WIDE_LAYOUT_WIDTH = 720.dp

/** One group: its name, a line saying how it is set, and its page. A group that [attention] is red and the one that opens first. */
internal class EditorCategory(
    val id: String,
    val title: String,
    val summary: String,
    val attention: Boolean,
    val icon: ImageVector? = null,
    val content: @Composable () -> Unit
)

/**
 * The groups of a screen. A wide area lists them beside the page of the chosen one, with [footer] under the list; a narrow
 * one stacks them as sections that open and close, with [footer] after the last. [header] stands above the pages (above the
 * sections), whatever is chosen. With [enabled] false the pages are shown but cannot be touched. What is chosen or open
 * survives the area changing width (a foldable opening or closing) and a rotation.
 */
@Composable
internal fun GroupedSettings(
    categories: List<EditorCategory>,
    header: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null
) {
    var chosen by rememberSaveable { mutableStateOf(categories.firstOrNull { it.attention }?.id ?: categories.firstOrNull()?.id ?: "") }
    val opened: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { categories.filter { it.attention }.map { it.id }.toMutableStateList() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (categories.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                header?.invoke()
                footer?.invoke()
            }
        } else if (maxWidth >= WIDE_LAYOUT_WIDTH) {
            val page = categories.firstOrNull { it.id == chosen } ?: categories.first()
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(280.dp).fillMaxHeight().padding(12.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { category ->
                            CategoryRow(category, selected = category.id == page.id) { chosen = category.id }
                        }
                    }
                    if (footer != null) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { footer() }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    header?.invoke()
                    Text(page.title, style = MaterialTheme.typography.titleLarge)
                    DisabledWhen(!enabled) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { page.content() } }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                header?.invoke()
                categories.forEach { category ->
                    CollapsibleSection(category, expanded = category.id in opened, enabled = enabled) {
                        if (category.id in opened) opened.remove(category.id) else opened.add(category.id)
                    }
                }
                footer?.invoke()
            }
        }
    }
}

@Composable
internal fun CategoryRow(category: EditorCategory, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            category.icon?.let {
                Icon(it, null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    category.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (category.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    category.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
internal fun CollapsibleSection(category: EditorCategory, expanded: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Surface(onClick = onToggle, color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    category.icon?.let {
                        Icon(it, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            category.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (category.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            category.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null)
                }
            }
            if (expanded) {
                DisabledWhen(!enabled) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) { category.content() }
                }
            }
        }
    }
}

/**
 * Shows [content] dimmed and lets no tap through to it. Only taps are swallowed, a drag still reaches the scrolling parent, so
 * a page that is disabled can be scrolled and read.
 */
@Composable
internal fun DisabledWhen(disabled: Boolean, content: @Composable () -> Unit) {
    if (!disabled) {
        content()
        return
    }
    Box {
        Box(Modifier.alpha(0.45f)) { content() }
        // matchParentSize: as large as the page, not as large as the room (inside a scroll that room has no end).
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
    }
}
