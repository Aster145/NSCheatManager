package com.nscheatmanager.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nscheatmanager.app.R
import com.nscheatmanager.app.data.preferences.AppPreferences
import com.nscheatmanager.app.domain.DeviceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.ui.tooling.preview.Preview

data class DeviceEditorUiState(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val sysBotPort: String = "6000",
    val ftpPort: String = "21",
    val noexsPort: String = "7331",
    val error: DeviceEditorError? = null,
)

data class SettingsUiState(
    val devices: List<DeviceProfile> = emptyList(),
    val languageTag: String = AppPreferences.CHINESE_LANGUAGE_TAG,
    val editor: DeviceEditorUiState? = null,
    val isSaving: Boolean = false,
    val showMemoryPage: Boolean = false,
    val detachDmntBeforeConnect: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAddDevice: () -> Unit,
    onEditDevice: (DeviceProfile) -> Unit,
    onDeleteDevice: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onEditorChanged: (DeviceEditorUiState) -> Unit,
    onSaveEditor: () -> Unit,
    onDismissEditor: () -> Unit,
    onShowMemoryPageChanged: (Boolean) -> Unit = {},
    onDetachDmntBeforeConnectChanged: (Boolean) -> Unit = {},
    messages: Flow<SettingsMessage> = emptyFlow(),
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val deleteFailed = stringResource(R.string.error_delete_device)
    val defaultFailed = stringResource(R.string.error_default_device)
    val languageFailed = stringResource(R.string.error_language_change)
    val memoryVisibilityFailed = stringResource(R.string.error_memory_visibility_change)
    val autoDetachFailed = stringResource(R.string.error_auto_detach_change)
    LaunchedEffect(messages) {
        messages.collect { message ->
            snackbar.showSnackbar(when (message) {
                SettingsMessage.DELETE_FAILED -> deleteFailed
                SettingsMessage.DEFAULT_FAILED -> defaultFailed
                SettingsMessage.LANGUAGE_FAILED -> languageFailed
                SettingsMessage.MEMORY_VISIBILITY_FAILED -> memoryVisibilityFailed
                SettingsMessage.AUTO_DETACH_FAILED -> autoDetachFailed
            })
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.settings_title))
                        Text(
                            stringResource(R.string.settings_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("settings-message")) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader(
                    title = stringResource(R.string.default_device),
                    action = {
                        Button(onClick = onAddDevice) { Text(stringResource(R.string.add_device)) }
                    },
                )
            }
            items(state.devices, key = DeviceProfile::id) { device ->
                DeviceCard(
                    device = device,
                    onSetDefault = { onSetDefault(device.id) },
                    onEdit = { onEditDevice(device) },
                    onDelete = { onDeleteDevice(device.id) },
                )
            }
            if (state.devices.isEmpty()) {
                item { Text(stringResource(R.string.no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { SectionHeader(stringResource(R.string.interface_section)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.interface_language), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.language_updates_immediately),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.testTag("language-actions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.languageTag == AppPreferences.CHINESE_LANGUAGE_TAG,
                                onClick = { onLanguageSelected(AppPreferences.CHINESE_LANGUAGE_TAG) },
                                label = { Text(stringResource(R.string.language_chinese)) },
                            )
                            FilterChip(
                                selected = state.languageTag == AppPreferences.ENGLISH_LANGUAGE_TAG,
                                onClick = { onLanguageSelected(AppPreferences.ENGLISH_LANGUAGE_TAG) },
                                label = { Text("English") },
                            )
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("show-memory-setting"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.show_memory_page), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.show_memory_page_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = state.showMemoryPage, onCheckedChange = onShowMemoryPageChanged)
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("auto-detach-setting"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.detach_before_connect), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.detach_before_connect_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(modifier = Modifier.testTag("auto-detach-switch"), checked = state.detachDmntBeforeConnect, onCheckedChange = onDetachDmntBeforeConnectChanged)
                        }
                    }
                }
            }
        }
    }
    state.editor?.let { editor ->
        DeviceEditorDialog(
            editor = editor,
            isSaving = state.isSaving,
            onChanged = onEditorChanged,
            onSave = onSaveEditor,
            onDismiss = onDismissEditor,
        )
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

@Composable
private fun DeviceCard(
    device: DeviceProfile,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val defaultLabel = stringResource(R.string.set_default_device, device.name)
    val defaultState = stringResource(if (device.isDefault) R.string.selected else R.string.not_selected)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isDefault) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = device.isDefault,
                    onClick = onSetDefault,
                    modifier = Modifier.testTag("default-${device.id}").semantics {
                        contentDescription = defaultLabel
                        stateDescription = defaultState
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (device.isDefault) {
                            stringResource(R.string.device_host_default, device.host)
                        } else {
                            device.host
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit, modifier = Modifier.testTag("edit-${device.id}")) {
                    Text(stringResource(R.string.edit))
                }
                TextButton(onClick = onDelete, modifier = Modifier.testTag("delete-${device.id}")) {
                    Text(stringResource(R.string.delete))
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                PortValue(device.sysBotPort, "sys-botbase")
                PortValue(device.ftpPort, "FTP")
                PortValue(device.noexsPort, "Noexs")
            }
        }
    }
}

@Composable
private fun PortValue(port: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(port.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeviceEditorDialog(
    editor: DeviceEditorUiState,
    isSaving: Boolean,
    onChanged: (DeviceEditorUiState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().sizeIn(maxHeight = 620.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    stringResource(if (editor.id == null) R.string.add_device_title else R.string.edit_device_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onChanged(editor.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth().testTag("device-name"),
                    label = { Text(stringResource(R.string.device_name)) },
                    enabled = !isSaving,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = editor.host,
                    onValueChange = { onChanged(editor.copy(host = it)) },
                    modifier = Modifier.fillMaxWidth().testTag("device-host"),
                    label = { Text(stringResource(R.string.ipv4_address)) },
                    supportingText = { Text(stringResource(R.string.fixed_ipv4_only)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = !isSaving,
                    singleLine = true,
                )
                PortField(
                    value = editor.sysBotPort,
                    onValueChange = { onChanged(editor.copy(sysBotPort = it)) },
                    label = stringResource(R.string.sysbot_port),
                    tag = "sysbot-port",
                    enabled = !isSaving,
                )
                PortField(
                    value = editor.ftpPort,
                    onValueChange = { onChanged(editor.copy(ftpPort = it)) },
                    label = stringResource(R.string.ftp_port),
                    tag = "ftp-port",
                    enabled = !isSaving,
                )
                PortField(
                    value = editor.noexsPort,
                    onValueChange = { onChanged(editor.copy(noexsPort = it)) },
                    label = stringResource(R.string.noexs_port),
                    tag = "noexs-port",
                    enabled = !isSaving,
                )
                editor.error?.let { error ->
                    Text(
                        stringResource(error.stringResource),
                        modifier = Modifier.testTag("device-editor-error"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = onSave,
                        enabled = !isSaving,
                        modifier = Modifier.testTag("save-device"),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun PortField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.all(Char::isDigit) && candidate.length <= 5) onValueChange(candidate)
        },
        modifier = Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        enabled = enabled,
        singleLine = true,
    )
}

private val DeviceEditorError.stringResource: Int
    get() = when (this) {
        DeviceEditorError.NAME_REQUIRED -> R.string.error_name_required
        DeviceEditorError.INVALID_IPV4 -> R.string.error_invalid_ipv4
        DeviceEditorError.INVALID_PORT -> R.string.error_invalid_port
        DeviceEditorError.DUPLICATE_NAME -> R.string.error_duplicate_name
        DeviceEditorError.DUPLICATE_HOST -> R.string.error_duplicate_host
        DeviceEditorError.SAVE_FAILED -> R.string.error_save_device
    }

@Preview(
    name = "设置界面",
    showBackground = true,
    showSystemUi = true,
    widthDp = 412,
    heightDp = 915
)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(
            state = SettingsUiState(),
            onBack = {},
            onAddDevice = {},
            onEditDevice = {},
            onDeleteDevice = {},
            onSetDefault = {},
            onLanguageSelected = {},
            onEditorChanged = {},
            onSaveEditor = {},
            onDismissEditor = {},
            onShowMemoryPageChanged = {},
            onDetachDmntBeforeConnectChanged = {}
        )
    }
}
