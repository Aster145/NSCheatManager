package com.nscheatmanager.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nscheatmanager.app.R
import com.nscheatmanager.app.cheats.parser.CheatParseDiagnosticKind

data class CheatEditorActions(
    val selectTab: (EditorTab) -> Unit,
    val cheatChanged: (String) -> Unit,
    val notesChanged: (String) -> Unit,
    val save: () -> Unit,
    val cancel: () -> Unit,
    val requestNavigation: (String) -> Unit,
    val confirmDiscard: (Long) -> Unit,
    val dismissDiscard: (Long) -> Unit,
    val acknowledgeNavigation: (String) -> Unit,
) {
    companion object {
        val None = CheatEditorActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Composable
fun CheatEditorScreen(
    state: CheatEditorUiState,
    actions: CheatEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().testTag("cheat-editor"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (state.isLoading || state.isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        TabRow(selectedTabIndex = state.selectedTab.ordinal) {
            Tab(
                modifier = Modifier.testTag("editor-cheat-tab"),
                selected = state.selectedTab == EditorTab.Cheat,
                onClick = { actions.selectTab(EditorTab.Cheat) },
                text = { Text(state.cheatTabLabel) },
            )
            Tab(
                modifier = Modifier.testTag("editor-notes-tab"),
                selected = state.selectedTab == EditorTab.Notes,
                onClick = { actions.selectTab(EditorTab.Notes) },
                text = { Text(state.notesTabLabel) },
            )
        }
        if (state.selectedTab == EditorTab.Cheat) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp).testTag("editor-cheat-text"),
                value = state.cheatText,
                onValueChange = actions.cheatChanged,
                enabled = !state.isLoading && !state.isSaving,
                label = { Text(state.cheatTabLabel) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        } else {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp).testTag("editor-notes-text"),
                value = state.notesText,
                onValueChange = actions.notesChanged,
                enabled = !state.isLoading && !state.isSaving,
                label = { Text(state.notesTabLabel) },
            )
        }
        state.parseDiagnostic?.let { diagnostic ->
            val localizedMessage = stringResource(
                when (diagnostic.kind) {
                    CheatParseDiagnosticKind.MalformedGroupHeader -> R.string.parse_malformed_group_header
                    CheatParseDiagnosticKind.InstructionBeforeGroup -> R.string.parse_instruction_before_group
                    CheatParseDiagnosticKind.InvalidInstructionWord -> R.string.parse_invalid_instruction_word
                },
                diagnostic.line,
            )
            Text(
                localizedMessage,
                modifier = Modifier.testTag("editor-validation"),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                modifier = Modifier.testTag("editor-cancel"),
                onClick = actions.cancel,
                enabled = !state.isSaving,
            ) { Text(stringResource(R.string.cancel)) }
            Button(
                modifier = Modifier.padding(start = 8.dp).testTag("editor-save"),
                onClick = actions.save,
                enabled = state.dirty && !state.isLoading && !state.isSaving,
            ) { Text(stringResource(R.string.save)) }
        }
    }
}
