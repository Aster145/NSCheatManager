package com.nscheatmanager.app.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.nscheatmanager.app.R
import com.nscheatmanager.app.domain.ConnectionState
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.ui.editor.CheatEditorActions
import com.nscheatmanager.app.ui.editor.CheatEditorScreen
import com.nscheatmanager.app.ui.editor.CheatEditorUiState
import java.text.DateFormat
import java.util.Date

data class GameScreenActions(
    val selectDevice: (String) -> Unit,
    val connectionToggle: () -> Unit,
    val detachDmnt: () -> Unit,
    val cheatChecked: (String, Boolean, Boolean) -> Unit,
    val editModeChanged: (Boolean) -> Unit,
    val recognize: () -> Unit,
    val download: () -> Unit,
    val upload: () -> Unit,
    val shareZip: () -> Unit,
    val importZip: () -> Unit,
    val settings: () -> Unit,
    val about: () -> Unit,
) {
    companion object {
        val None = GameScreenActions({}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    state: GameUiState,
    actions: GameScreenActions,
    modifier: Modifier = Modifier,
    editorState: CheatEditorUiState = CheatEditorUiState(),
    editorActions: CheatEditorActions = CheatEditorActions.None,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color(0xFF5E478D)),
                        ) {
                            Image(
                                painter = painterResource(R.mipmap.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.current_game),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    val label = stringResource(R.string.more_options)
                    IconButton(
                        modifier = Modifier.testTag("overflow-menu").semantics { contentDescription = label },
                        onClick = { menuExpanded = true },
                    ) { Text("⋮") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        ToggleOrderedMenuItem(0, "menu-edit", state.gameValidated && !state.missingMirror, state.editMode || editorState.isOpen, text = {
                            Checkbox(modifier = Modifier.clearAndSetSemantics { }, checked = state.editMode || editorState.isOpen, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.edit_mode))
                        }) {
                            menuExpanded = false
                            actions.editModeChanged(!(state.editMode || editorState.isOpen))
                        }
                        OrderedMenuItem(1, "menu-recognize", state.connection == ConnectionState.Ready, { Text(stringResource(R.string.recognize_game)) }) {
                            menuExpanded = false; actions.recognize()
                        }
                        OrderedMenuItem(2, "menu-download", state.gameValidated, { Text(stringResource(R.string.download_from_switch)) }) {
                            menuExpanded = false; actions.download()
                        }
                        OrderedMenuItem(3, "menu-upload", state.gameValidated && !state.missingMirror, { Text(stringResource(R.string.upload_to_switch)) }) {
                            menuExpanded = false; actions.upload()
                        }
                        OrderedMenuItem(4, "menu-share-zip", state.gameValidated && !state.missingMirror, { Text(stringResource(R.string.package_share_zip)) }) {
                            menuExpanded = false; actions.shareZip()
                        }
                        OrderedMenuItem(5, "menu-import-zip", state.gameValidated, { Text(stringResource(R.string.import_zip)) }) {
                            menuExpanded = false; actions.importZip()
                        }
                        OrderedMenuItem(6, "menu-settings", true, { Text(stringResource(R.string.settings_title)) }) {
                            menuExpanded = false; actions.settings()
                        }
                        OrderedMenuItem(7, "menu-about", true, { Text(stringResource(R.string.about_title)) }) {
                            menuExpanded = false; actions.about()
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceControls(state, actions)
            if (editorState.isOpen) {
                CheatEditorScreen(editorState, editorActions)
            } else {
                GameIdentityCard(state)
                GameActionRow(state, actions)
                CheatGroups(state, actions)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun OrderedMenuItem(
    order: Int,
    tag: String,
    enabled: Boolean = true,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Box(Modifier.testTag("menu-order-$order")) {
        DropdownMenuItem(modifier = Modifier.testTag(tag), text = text, onClick = onClick, enabled = enabled)
    }
}

@Composable
private fun ToggleOrderedMenuItem(
    order: Int,
    tag: String,
    enabled: Boolean,
    checked: Boolean,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Box(Modifier.testTag("menu-order-$order")) {
        DropdownMenuItem(
            modifier = Modifier.testTag(tag).semantics {
                role = Role.Checkbox
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
            text = text,
            onClick = onClick,
            enabled = enabled,
        )
    }
}

@Composable
private fun DeviceControls(state: GameUiState, actions: GameScreenActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Box(
                    Modifier.size(9.dp).background(
                        if (state.connection == ConnectionState.Ready) Color(0xFF35A36B)
                        else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                )
            Spacer(Modifier.width(6.dp))
            DeviceSelector(state.devices, state.selectedDeviceId, actions.selectDevice, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            ConnectionButton(state, actions)
        }
    }
}

@Composable
private fun DeviceSelector(
    devices: List<DeviceProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.id == selectedId }
    Box(modifier) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().testTag("device-selector"),
            onClick = { expanded = true },
            enabled = devices.isNotEmpty(),
            border = null,
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    selected?.name ?: stringResource(R.string.select_device),
                    style = MaterialTheme.typography.labelLarge,
                )
                selected?.let {
                    Text(
                        it.host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text("${device.name} · ${device.host}") },
                    onClick = { expanded = false; onSelect(device.id) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionButton(state: GameUiState, actions: GameScreenActions, modifier: Modifier = Modifier) {
    Button(
        modifier = modifier.testTag("connect-toggle"),
        onClick = actions.connectionToggle,
        enabled = state.selectedDeviceId != null && !state.busy,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Text(
            stringResource(
                if (state.connection in setOf(ConnectionState.Connecting, ConnectionState.Recognizing, ConnectionState.Ready)) {
                    R.string.disconnect
                } else {
                    R.string.connect
                },
            ),
        )
    }
}

@Composable
private fun GameActionRow(state: GameUiState, actions: GameScreenActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            modifier = Modifier.weight(1f).testTag("recognize-game-primary"),
            onClick = actions.recognize,
            enabled = state.connection == ConnectionState.Ready && !state.busy,
        ) { Text(stringResource(R.string.recognize_game)) }
        DetachButton(state, actions, Modifier.weight(1f))
    }
}

@Composable
private fun DetachButton(state: GameUiState, actions: GameScreenActions, modifier: Modifier = Modifier) {
    OutlinedButton(
        modifier = modifier.testTag("detach-dmnt"),
        onClick = actions.detachDmnt,
        enabled = state.connection == ConnectionState.Ready,
    ) { Text(stringResource(R.string.detach_dmnt)) }
}

@Composable
private fun GameIdentityCard(state: GameUiState) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("game-identity"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.current_game),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
                    )
                    Text(
                        state.titleId?.let { "TID $it" } ?: "TID —",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (state.gameValidated) Text("●", color = Color(0xFFB7F2CF))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IdentityBlock("BID", state.buildId, Modifier.weight(1f))
                IdentityBlock(stringResource(R.string.main_base), state.mainBase, Modifier.weight(1f))
            }
            IdentityBlock(stringResource(R.string.heap_base), state.heapBase)
        }
    }
}

@Composable
private fun IdentityBlock(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
        )
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CheatGroups(state: GameUiState, actions: GameScreenActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.nav_cheats),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        if (!state.missingMirror) {
            Text(
                stringResource(R.string.available_cheats_count, state.groups.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.missingMirror) {
        Card(Modifier.fillMaxWidth().testTag("missing-cheat-file")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cheat_file_missing))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.testTag("missing-import"),
                        onClick = actions.importZip,
                        enabled = state.canImport,
                    ) { Text(stringResource(R.string.import_zip)) }
                    OutlinedButton(
                        modifier = Modifier.testTag("missing-download"),
                        onClick = actions.download,
                        enabled = state.canDownload,
                    ) { Text(stringResource(R.string.download_from_switch)) }
                }
            }
        }
        return
    }
    state.groups.forEachIndexed { index, group ->
        if (index > 0) HorizontalDivider()
        val diagnosticText = if (!group.executable) localizedDiagnostic(group) else null
        val accessibilityLabel = diagnosticText?.let {
            stringResource(R.string.message_join, group.name, it)
        } ?: group.name
        val stateLabel = stringResource(
            when {
                !group.executable -> R.string.cheat_state_unsupported
                group.checked -> R.string.cheat_state_checked
                else -> R.string.cheat_state_unchecked
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("cheat-${group.name}")
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityLabel
                    stateDescription = stateLabel
                }
                .toggleable(
                    value = group.checked,
                    enabled = group.executable && !group.executing,
                    role = Role.Checkbox,
                    onValueChange = { checked -> actions.cheatChecked(group.name, group.checked, checked) },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                modifier = Modifier.clearAndSetSemantics { },
                checked = group.checked,
                enabled = group.executable && !group.executing,
                onCheckedChange = null,
            )
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleSmall)
                if (!group.executable) {
                    Text(
                        diagnosticText.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                group.lastExecutedAtEpochMillis?.let { timestamp ->
                    val locale = LocalConfiguration.current.locales[0]
                    val formatted = remember(timestamp, locale) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale)
                            .format(Date(timestamp))
                    }
                    Text(
                        stringResource(R.string.last_executed, formatted),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (group.executing) CircularProgressIndicator(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun localizedDiagnostic(group: CheatGroupUiState): String {
    val diagnostic = group.diagnostic
    if (diagnostic != null) {
        return when (diagnostic.kind) {
            CheatDiagnosticKind.UnsupportedOpcode -> stringResource(
                R.string.diagnostic_unsupported_opcode,
                diagnostic.line,
                diagnostic.opcode.orEmpty(),
            )
            CheatDiagnosticKind.UnsupportedForm -> stringResource(
                R.string.diagnostic_unsupported_form,
                diagnostic.line,
                diagnostic.argument.orEmpty(),
            )
            CheatDiagnosticKind.UnsupportedMemoryRegion -> stringResource(
                R.string.diagnostic_unsupported_memory_region,
                diagnostic.line,
                diagnostic.argument.orEmpty(),
            )
            CheatDiagnosticKind.ArithmeticOverflow -> stringResource(
                R.string.diagnostic_arithmetic_overflow,
                diagnostic.line,
            )
            CheatDiagnosticKind.InstructionLimitExceeded -> stringResource(
                R.string.diagnostic_instruction_limit,
                diagnostic.line,
                diagnostic.argument.orEmpty(),
            )
            CheatDiagnosticKind.IoLimitExceeded -> stringResource(
                R.string.diagnostic_io_limit,
                diagnostic.line,
                diagnostic.argument.orEmpty(),
            )
            CheatDiagnosticKind.Connection -> stringResource(R.string.diagnostic_connection, diagnostic.line)
            CheatDiagnosticKind.Timeout -> stringResource(
                R.string.diagnostic_timeout,
                diagnostic.line,
                diagnostic.argument.orEmpty(),
            )
            CheatDiagnosticKind.Disconnected -> stringResource(R.string.diagnostic_disconnected, diagnostic.line)
            CheatDiagnosticKind.MalformedResponse ->
                stringResource(R.string.diagnostic_malformed_response, diagnostic.line)
            CheatDiagnosticKind.ResponseTooLarge -> stringResource(
                R.string.diagnostic_response_too_large,
                diagnostic.line,
                diagnostic.argument.orEmpty(),
            )
            CheatDiagnosticKind.CommandTooLarge -> stringResource(
                R.string.diagnostic_command_too_large,
                diagnostic.line,
                diagnostic.argument.orEmpty(),
            )
        }
    }
    val line = group.unsupportedLine?.let { stringResource(R.string.line_number, it) }
    val opcode = group.unsupportedOpcode?.let { stringResource(R.string.opcode_value, it) }
    return when {
        line != null && opcode != null -> stringResource(R.string.message_join, line, opcode)
        line != null -> line
        opcode != null -> opcode
        else -> stringResource(R.string.unsupported_cheat)
    }
}
