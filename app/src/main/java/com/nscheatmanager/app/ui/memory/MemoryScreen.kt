package com.nscheatmanager.app.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.nscheatmanager.app.R
import com.nscheatmanager.app.core.model.ValueType

data class MemoryActions(
    val mode: (AddressMode) -> Unit, val address: (String) -> Unit, val type: (ValueType) -> Unit,
    val value: (String) -> Unit, val length: (String) -> Unit, val read: () -> Unit,
    val write: () -> Unit, val lock: (Boolean) -> Unit, val confirm: (Long) -> Unit, val dismiss: (Long) -> Unit,
    val applyBookmark: (MemoryBookmark) -> Unit = {}, val saveBookmark: (String, String, String?) -> Unit = { _, _, _ -> },
    val deleteBookmark: (String) -> Unit = {},
    val importBookmarks: () -> Unit = {}, val exportBookmarks: (Boolean) -> Unit = {},
    val confirmBookmarkImport: (Boolean) -> Unit = {}, val dismissBookmarkImport: () -> Unit = {},
) { companion object { val None = MemoryActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

@Composable fun MemoryScreen(state: MemoryUiState, actions: MemoryActions, modifier: Modifier = Modifier) {
    val enabled = state.ready && !state.parametersLocked && !state.busy
    var editing by remember { mutableStateOf<MemoryBookmark?>(null) }
    var showBookmarkEditor by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MemoryHeader()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.memory_subtitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(state.address, actions.address, Modifier.fillMaxWidth().testTag("memory-address"), enabled = enabled, singleLine = true, shape = RoundedCornerShape(13.dp), label = { Text(stringResource(R.string.memory_address)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { TypeSelector(state.type, enabled, actions.type) }
            OutlinedTextField(state.value, actions.value, Modifier.weight(1f).testTag("memory-value"), enabled = enabled, singleLine = true, shape = RoundedCornerShape(13.dp), label = { Text(stringResource(R.string.memory_value)) })
        }
        if (state.type == ValueType.Hex) OutlinedTextField(state.length, actions.length, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.memory_length)) })
        Row(Modifier.fillMaxWidth().testTag("memory-action-row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(actions.read, Modifier.weight(1f).testTag("memory-read"), enabled = enabled) { Text(stringResource(R.string.memory_read)) }
            Button(actions.write, Modifier.weight(1f).testTag("memory-write"), enabled = enabled) { Text(stringResource(R.string.memory_write)) }
        }
        state.result?.let { ResultCard(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.memory_bookmarks), style = MaterialTheme.typography.titleMedium)
            TextButton({ editing = null; showBookmarkEditor = true }, enabled = state.ready) { Text(stringResource(R.string.memory_add_bookmark)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(actions.importBookmarks, Modifier.weight(1f), enabled = state.ready) { Text(stringResource(R.string.memory_import_json)) }
            OutlinedButton({ actions.exportBookmarks(false) }, Modifier.weight(1f), enabled = state.ready) { Text(stringResource(R.string.memory_export_json)) }
            OutlinedButton({ actions.exportBookmarks(true) }, Modifier.weight(1f), enabled = state.ready) { Text("Noexes") }
        }
        state.bookmarks.forEach { bookmark ->
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) { Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("◆", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bookmark.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text("${bookmark.addressExpression} · ${bookmark.valueType.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (bookmark.note.isNotBlank()) Text(bookmark.note, style = MaterialTheme.typography.bodySmall)
                Row { TextButton({ actions.applyBookmark(bookmark) }) { Text(stringResource(R.string.memory_use_bookmark)) }
                    TextButton({ editing = bookmark; showBookmarkEditor = true }) { Text(stringResource(R.string.edit)) }
                    TextButton({ actions.deleteBookmark(bookmark.name) }) { Text(stringResource(R.string.delete)) } }
            } }
        }
        if (state.pendingCleanup.isNotEmpty()) Text(stringResource(R.string.memory_pending_cleanup, state.pendingCleanup.size), color = MaterialTheme.colorScheme.error)
        state.error?.let { Text(stringResource(it.resource()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory-error")) }
    }
    state.confirmation?.let { pending ->
        AlertDialog(onDismissRequest = { actions.dismiss(pending.id) }, title = { Text(stringResource(R.string.memory_confirm_title)) },
            text = { Text(stringResource(R.string.memory_confirm_detail, targetText(pending.display), pending.bytes.size, pending.bytes.copyToByteArray().joinToString(" ") { "%02X".format(it) }) + "\n${pending.type.name}: ${pending.inputValue}\n0x${pending.display.resolvedAbsolute.toString(16).uppercase()}") },
            dismissButton = { TextButton({ actions.dismiss(pending.id) }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { Button({ actions.confirm(pending.id) }, Modifier.testTag("memory-confirm")) { Text(stringResource(R.string.confirm)) } })
    }
    if (showBookmarkEditor) BookmarkEditor(editing, state.address, state.type, state.length,
        onDismiss = { showBookmarkEditor = false }, onSave = { name, note ->
            actions.saveBookmark(name, note, editing?.name); showBookmarkEditor = false
        })
    state.pendingBookmarkImport?.let { pending -> AlertDialog(onDismissRequest = actions.dismissBookmarkImport,
        title = { Text(stringResource(R.string.memory_import_conflicts)) },
        text = { Text(stringResource(R.string.memory_import_count, pending.size)) },
        dismissButton = { TextButton({ actions.confirmBookmarkImport(false) }) { Text(stringResource(R.string.memory_skip_conflicts)) } },
        confirmButton = { Button({ actions.confirmBookmarkImport(true) }) { Text(stringResource(R.string.memory_overwrite_conflicts)) } }) }
}

@Composable private fun MemoryHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(Brush.linearGradient(listOf(Color(0xFFFFC928), Color(0xFFEC9E12))), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("◆", color = Color.White) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("NSCheatManager", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun BookmarkEditor(existing: MemoryBookmark?, address: String, type: ValueType, length: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var note by remember(existing) { mutableStateOf(existing?.note.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (existing == null) R.string.memory_add_bookmark else R.string.memory_edit_bookmark)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.memory_bookmark_name)) }, singleLine = true)
            Text(existing?.addressExpression ?: address); Text((existing?.valueType ?: type).name + if ((existing?.valueType ?: type) == ValueType.Hex) " × ${existing?.hexLength ?: length}" else "")
            OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.memory_bookmark_note)) })
        } }, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { Button({ onSave(name, note) }, enabled = name.isNotBlank() && address.isNotBlank()) { Text(stringResource(R.string.save)) } })
}

@Composable private fun TypeSelector(selected: ValueType, enabled: Boolean, select: (ValueType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled) { Text(selected.name) }
        DropdownMenu(expanded, { expanded = false }) { ValueType.entries.forEach { DropdownMenuItem({ Text(it.name) }, { expanded = false; select(it) }) } }
    }
}
@Composable private fun ResultCard(result: MemoryResultUi) {
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(Modifier.fillMaxWidth().testTag("memory-result")) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("0x${result.address.toString(16).uppercase()}"); Text(result.raw); Text("${result.type.name}: ${result.value}"); Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(result.atMillis)), Modifier.testTag("memory-result-time"))
        TextButton({ clipboard.setText(AnnotatedString("${result.raw}\n${result.value}")) }, Modifier.testTag("memory-copy")) { Text(stringResource(R.string.memory_copy)) }
    } }
}
@Composable private fun targetText(display: MemoryTargetDisplay) = when(display.mode) {
    AddressMode.Absolute -> stringResource(R.string.memory_target_absolute, display.inputHex)
    AddressMode.Main -> stringResource(R.string.memory_target_main, display.inputHex)
    AddressMode.Heap -> stringResource(R.string.memory_target_heap, display.inputHex)
}
private fun MemoryError.resource() = when(this) {
    MemoryError.SessionRequired -> R.string.memory_error_session; MemoryError.InvalidAddress -> R.string.memory_error_address
    MemoryError.InvalidLength -> R.string.memory_error_length; MemoryError.InvalidValue -> R.string.memory_error_value
    MemoryError.OperationFailed -> R.string.memory_error_operation; MemoryError.SessionChanged -> R.string.memory_error_changed
}
