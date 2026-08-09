package com.nscheatmanager.app.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
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
) { companion object { val None = MemoryActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

@Composable fun MemoryScreen(state: MemoryUiState, actions: MemoryActions, modifier: Modifier = Modifier) {
    val enabled = state.ready && !state.parametersLocked && !state.busy
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.memory_subtitle), style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AddressMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(selected = state.mode == mode, onClick = { actions.mode(mode) }, enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, AddressMode.entries.size),
                    label = { Text(when(mode) { AddressMode.Absolute -> stringResource(R.string.memory_absolute); AddressMode.Main -> "Main +"; AddressMode.Heap -> "Heap +" }) })
            }
        }
        OutlinedTextField(state.address, actions.address, Modifier.fillMaxWidth().testTag("memory-address"), enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.memory_address)) })
        TypeSelector(state.type, enabled, actions.type)
        if (state.type == ValueType.Hex) OutlinedTextField(state.length, actions.length, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.memory_length)) })
        OutlinedTextField(state.value, actions.value, Modifier.fillMaxWidth().testTag("memory-value"), enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.memory_value)) })
        Row(Modifier.fillMaxWidth().testTag("memory-action-row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(actions.read, Modifier.weight(1f).testTag("memory-read"), enabled = enabled) { Text(stringResource(R.string.memory_read)) }
            Button(actions.write, Modifier.weight(1f).testTag("memory-write"), enabled = enabled) { Text(stringResource(R.string.memory_write)) }
            Row(Modifier.weight(1f).testTag("memory-lock").toggleable(state.locked != null, enabled = state.ready && !state.busy, role = Role.Checkbox, onValueChange = actions.lock).semantics(mergeDescendants = true) {}) {
                Checkbox(state.locked != null, null, enabled = state.ready && !state.busy)
                Text(stringResource(R.string.memory_lock), Modifier.padding(top = 12.dp))
            }
        }
        state.result?.let { ResultCard(it) }
        if (state.pendingCleanup.isNotEmpty()) Text(stringResource(R.string.memory_pending_cleanup, state.pendingCleanup.size), color = MaterialTheme.colorScheme.error)
        state.error?.let { Text(stringResource(it.resource()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory-error")) }
    }
    state.confirmation?.let { pending ->
        AlertDialog(onDismissRequest = { actions.dismiss(pending.id) }, title = { Text(stringResource(R.string.memory_confirm_title)) },
            text = { Text(stringResource(R.string.memory_confirm_detail, targetText(pending.display), pending.bytes.size, pending.bytes.copyToByteArray().joinToString(" ") { "%02X".format(it) }) + "\n${pending.type.name}: ${pending.inputValue}\n0x${pending.display.resolvedAbsolute.toString(16).uppercase()}") },
            dismissButton = { TextButton({ actions.dismiss(pending.id) }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { Button({ actions.confirm(pending.id) }, Modifier.testTag("memory-confirm")) { Text(stringResource(R.string.confirm)) } })
    }
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
