package com.nscheatmanager.app.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.nscheatmanager.app.R
import com.nscheatmanager.app.domain.ConnectionState
import com.nscheatmanager.app.domain.DeviceProfile
import com.nscheatmanager.app.ui.editor.CheatEditorActions
import com.nscheatmanager.app.ui.editor.CheatEditorScreen
import com.nscheatmanager.app.ui.editor.CheatEditorUiState
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.tooling.preview.Preview
import com.nscheatmanager.app.ui.about.AboutScreen
import com.nscheatmanager.app.ui.memory.MemoryScreen
import com.nscheatmanager.app.ui.memory.MemoryUiState
import com.nscheatmanager.app.ui.memory.MemoryActions

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

enum class GameScreenContent {
    GameInfo,
    Cheats,
    Combined,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    state: GameUiState,
    actions: GameScreenActions,
    modifier: Modifier = Modifier,
    editorState: CheatEditorUiState = CheatEditorUiState(),
    editorActions: CheatEditorActions = CheatEditorActions.None,
    content: GameScreenContent = GameScreenContent.Combined,
    onOpenGame: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = Modifier.height(68.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(
                                Brush.linearGradient(listOf(Color(0xFFFFC928), Color(0xFFEC9E12))),
                            ), contentAlignment = Alignment.Center,
                        ) { Text("◆", color = Color.White, fontSize = 17.sp) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(if (content == GameScreenContent.Cheats) R.string.nav_cheats else R.string.current_game),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("NSCheatManager", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    val label = stringResource(R.string.more_options)
                    IconButton(
                        modifier = Modifier.testTag("overflow-menu").semantics { contentDescription = label },
                        onClick = { menuExpanded = true },
                    ) { Text("⋮") }
                    DropdownMenu(
                        expanded = menuExpanded, onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.width(210.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                    ) {
                        ToggleOrderedMenuItem(0, "menu-edit", true, state.editMode || editorState.isOpen, text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            Checkbox(modifier = Modifier.clearAndSetSemantics { }, checked = state.editMode || editorState.isOpen, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.edit_mode))
                            }
                        }) {
                            menuExpanded = false
                            actions.editModeChanged(!(state.editMode || editorState.isOpen))
                        }
                        OrderedMenuItem(1, "menu-download", true, { Text(stringResource(R.string.download_from_switch)) }) {
                            menuExpanded = false; actions.download()
                        }
                        OrderedMenuItem(2, "menu-upload", true, { Text(stringResource(R.string.upload_to_switch)) }) {
                            menuExpanded = false; actions.upload()
                        }
                        OrderedMenuItem(3, "menu-share-zip", true, { Text(stringResource(R.string.package_share_zip)) }) {
                            menuExpanded = false; actions.shareZip()
                        }
                        OrderedMenuItem(4, "menu-import-zip", true, { Text(stringResource(R.string.import_zip)) }) {
                            menuExpanded = false; actions.importZip()
                        }
                        OrderedMenuItem(5, "menu-settings", true, { Text(stringResource(R.string.settings_title)) }) {
                            menuExpanded = false; actions.settings()
                        }
                        OrderedMenuItem(6, "menu-about", true, { Text(stringResource(R.string.about_title)) }) {
                            menuExpanded = false; actions.about()
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (content) {
                GameScreenContent.GameInfo -> {
                    DeviceControls(state, actions)
                    Text(stringResource(R.string.game_information), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    GameIdentityCard(state)
                }
                GameScreenContent.Cheats -> {
                    if (editorState.isOpen) {
                        CheatEditorScreen(editorState, editorActions)
                    } else if (!state.gameValidated && state.groups.isEmpty()) {
                        Card(Modifier.fillMaxWidth().testTag("cheats-needs-game")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.recognize_game_first))
                                Button(onClick = onOpenGame) { Text(stringResource(R.string.go_to_game_page)) }
                            }
                        }
                    } else {
                        CheatGroups(state, actions)
                    }
                }
                GameScreenContent.Combined -> {
                    DeviceControls(state, actions)
                    Text(stringResource(R.string.game_information), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    GameIdentityCard(state)
                    if (editorState.isOpen) {
                        CheatEditorScreen(editorState, editorActions)
                    } else {
                        CheatGroups(state, actions)
                    }
                }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(9.dp).background(
                        if (state.connection == ConnectionState.Ready) Color(0xFF35A36B)
                        else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                )
            Spacer(Modifier.width(6.dp))
            DeviceSelector(state.devices, state.selectedDeviceId, actions.selectDevice, Modifier.weight(1f), enabled = !state.preparingConnection)
            Spacer(Modifier.width(6.dp))
            ConnectionButton(state, actions)
            }
            state.connectionSummary?.let { ConnectionSummaryText(it) }
            GameActionRow(state, actions)
        }
    }
}

@Composable
private fun DeviceSelector(
    devices: List<DeviceProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.id == selectedId }
    Box(modifier) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().testTag("device-selector"),
            onClick = { expanded = true },
            enabled = devices.isNotEmpty() && enabled,
            border = null,
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    selected?.name ?: stringResource(if (devices.isEmpty()) R.string.add_device_first else R.string.select_device),
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
        enabled = state.selectedDeviceId != null && (!state.busy || state.preparingConnection),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(
            stringResource(
                if (state.preparingConnection || state.connection in setOf(ConnectionState.Connecting, ConnectionState.Recognizing)) {
                    R.string.cancel
                } else if (state.connection in setOf(ConnectionState.Connecting, ConnectionState.Recognizing, ConnectionState.Ready)) {
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
        enabled = state.selectedDeviceId != null && !state.detachingDmnt && !state.preparingConnection && state.connection !in setOf(ConnectionState.Connecting, ConnectionState.Recognizing),
    ) { Text(stringResource(R.string.detach_dmnt)) }
}

@Composable
private fun ConnectionSummaryText(summary: ConnectionSummary) {
    Text(
        stringResource(when (summary) {
            ConnectionSummary.DETACHED_CONNECTED -> R.string.connection_summary_detached_connected
            ConnectionSummary.DETACH_FAILED_CONNECTED -> R.string.connection_summary_detach_failed_connected
            ConnectionSummary.DETACHED_CONNECT_FAILED -> R.string.connection_summary_detached_connect_failed
            ConnectionSummary.DETACH_FAILED_CONNECT_FAILED -> R.string.connection_summary_both_failed
            ConnectionSummary.CONNECTED -> R.string.connection_summary_connected
            ConnectionSummary.CONNECT_FAILED -> R.string.connection_summary_connect_failed
            ConnectionSummary.DETACH_SUCCEEDED -> R.string.detach_complete
            ConnectionSummary.DETACH_FAILED -> R.string.connection_summary_detach_failed
            ConnectionSummary.CANCELLED -> R.string.connection_cancelled
        }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("connection-summary"),
    )
}

@Composable
private fun GameIdentityCard(state: GameUiState) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("game-identity"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(Color(0xFF654695), Color(0xFF8F67BF)))).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.current_game), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.72f))
            Text(stringResource(if (state.gameValidated) R.string.game_recognized else R.string.current_game), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IdentityBlock("TITLE ID", state.titleId, Modifier.weight(1f))
                IdentityBlock("BUILD ID", state.buildId, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IdentityBlock(stringResource(R.string.main_base), state.mainBase, Modifier.weight(1f))
                IdentityBlock(stringResource(R.string.heap_base), state.heapBase, Modifier.weight(1f))
            }
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
        Text(value ?: "—", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, maxLines = 1)
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
                .padding(vertical = 12.dp),
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

@Preview(
    name = "游戏主页",
    showBackground = true,
    showSystemUi = true,
    widthDp = 412,
    heightDp = 915
)
@Composable
private fun GameScreenPreview() {
    MaterialTheme {
        GameScreen(
            state = GameUiState(),
            actions = GameScreenActions.None,
            content = GameScreenContent.GameInfo
        )
    }
}

@Preview(
    name = "金手指页面",
    showBackground = true,
    showSystemUi = true,
    widthDp = 412,
    heightDp = 915
)
@Composable
private fun CheatsScreenPreview() {
    MaterialTheme {
        GameScreen(
            state = GameUiState(),
            actions = GameScreenActions.None,
            content = GameScreenContent.Cheats
        )
    }
}

@Preview(
    name = "内存界面",
    showBackground = true,
    showSystemUi = true,
    widthDp = 412,
    heightDp = 915
)
@Composable
private fun MemoryScreenPreview() {
    MaterialTheme {
        MemoryScreen(
            state = MemoryUiState(),
            actions = MemoryActions.None
        )
    }
}

@Preview(
    name = "关于",
    showBackground = true,
    showSystemUi = true,
    widthDp = 412,
    heightDp = 915
)
@Composable
private fun AboutScreenPreview() {
    MaterialTheme {
        AboutScreen(
            versionName = "1.0.0",
            onBack = {}
        )
    }
}
