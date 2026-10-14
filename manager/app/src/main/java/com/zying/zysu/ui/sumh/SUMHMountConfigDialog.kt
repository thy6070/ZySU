package com.zying.zysu.ui.sumh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.zying.zysu.R
import com.zying.zysu.ui.component.YukiIcon
import com.zying.zysu.ui.component.YukiAlertDialog
import com.zying.zysu.ui.sumh.util.SUMHManager
import com.zying.zysu.ui.sumh.util.SUMHModuleRuleError
import com.zying.zysu.ui.sumh.util.updateSUMHModuleRule
import com.zying.zysu.ui.sumh.util.validateSUMHModuleRulePath
import com.zying.zysu.ui.sumh.util.validateSUMHModuleRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val ModuleRulesSaver = listSaver<List<SUMHManager.ModuleRule>, String>(
    save = { rules -> rules.flatMap { listOf(it.path, it.mode) } },
    restore = { values -> values.chunked(2).map { SUMHManager.ModuleRule(it[0], it[1]) } },
)

internal val SUMH_MODE_COLORS = mapOf(
    "auto" to Color(0xFF1976D2),
    "sumh" to Color(0xFF388E3C),
    "overlay" to Color(0xFFF57C00),
    "magic" to Color(0xFF7B1FA2),
    "none" to Color(0xFF616161)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SUMHMountConfigDialog(
    moduleId: String,
    moduleName: String,
    initialInfo: SUMHManager.ModuleInfo?,
    sumhAvailable: Boolean,
    globalMode: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onSave: suspend (String, String, List<SUMHManager.ModuleRule>) -> Unit = { id, mode, rules ->
        SUMHManager.saveModule(id, mode, rules)
    },
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var error by rememberSaveable(moduleId) { mutableStateOf<String?>(null) }
    val selectableModes = SUMHManager.mountModes.filter { it != "sumh" || sumhAvailable }
    val selectableRuleModes = listOf("auto", "sumh", "none", "hide").filter { sumhAvailable || it !in listOf("sumh", "hide") }

    var selectedMode by rememberSaveable(moduleId) {
        mutableStateOf(initialInfo?.mode ?: "auto")
    }
    var rules by rememberSaveable(moduleId, stateSaver = ModuleRulesSaver) {
        mutableStateOf(initialInfo?.rules ?: emptyList<SUMHManager.ModuleRule>())
    }
    var newPath by rememberSaveable(moduleId) { mutableStateOf("") }
    var newMode by rememberSaveable(moduleId) { mutableStateOf(if (sumhAvailable) "sumh" else "auto") }
    var editingPath by rememberSaveable(moduleId) { mutableStateOf<String?>(null) }
    var modeExpanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var rulesExpanded by rememberSaveable(moduleId) { mutableStateOf(false) }
    val hasDraft = newPath.isNotEmpty() || editingPath != null
    val candidate = remember(rules, editingPath, newPath, newMode) {
        if (hasDraft) updateSUMHModuleRule(rules, editingPath, SUMHManager.ModuleRule(newPath, newMode)) else rules
    }
    val draftError = when {
        !hasDraft -> null
        validateSUMHModuleRulePath(newPath) != null -> validateSUMHModuleRulePath(newPath)
        rules.any { it.path == newPath && it.path != editingPath } -> SUMHModuleRuleError.DuplicatePath
        else -> null
    }
    val rulesError = remember(candidate) { candidate?.let(::validateSUMHModuleRules) }
    val original = rules.firstOrNull { it.path == editingPath }
    val editorModes = selectableRuleModes + listOfNotNull(original?.mode?.takeIf { it in listOf("overlay", "magic") })
    val draftModeAvailable = !hasDraft || newMode in editorModes || original == SUMHManager.ModuleRule(newPath, newMode)
    val canSave = !isSaving && candidate != null && rulesError == null && draftModeAvailable

    fun cancelEdit() {
        editingPath = null
        newPath = ""
        newMode = if (sumhAvailable) "sumh" else "auto"
        error = null
    }

    val modeLabels = mapOf(
        "auto" to stringResource(R.string.sumh_mount_mode_auto),
        "sumh" to stringResource(R.string.sumh_mount_mode_sumh),
        "overlay" to stringResource(R.string.sumh_mount_mode_overlay),
        "magic" to stringResource(R.string.sumh_mount_mode_magic),
        "none" to stringResource(R.string.sumh_mount_mode_none),
        "hide" to stringResource(R.string.sumh_mount_mode_hide),
    )

    YukiAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Column {
                Text(stringResource(R.string.sumh_mount_config))
                Text(
                    text = moduleName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!rulesExpanded && rulesError != null) Text(stringResource(moduleRuleErrorResource(rulesError)),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.sumh_module_rules_reboot), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (globalMode != "auto") Text(stringResource(R.string.sumh_global_override, globalMode))
                Text(
                    text = stringResource(R.string.sumh_mount_mode),
                    style = MaterialTheme.typography.titleSmall
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectableModes.forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            enabled = !isSaving,
                            label = { Text(modeLabels[mode] ?: mode) },
                            leadingIcon = {
                                YukiIcon(
                                    if (selectedMode == mode) Icons.Outlined.Check else when (mode) {
                                        "sumh" -> Icons.Outlined.Memory
                                        "overlay" -> Icons.Outlined.Layers
                                        "magic" -> Icons.Outlined.AutoFixHigh
                                        "none" -> Icons.Outlined.Block
                                        else -> Icons.Outlined.Settings
                                    },
                                    null, Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !isSaving, role = Role.Button) { rulesExpanded = !rulesExpanded }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sumh_module_rules_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall
                    )
                    YukiIcon(
                        imageVector = if (rulesExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(if (rulesExpanded) R.string.collapse_menu else R.string.expand_menu)
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (rulesExpanded) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                        Text(stringResource(R.string.sumh_module_rules_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!sumhAvailable) Text(stringResource(R.string.sumh_module_rules_unavailable),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        rules.forEach { rule: SUMHManager.ModuleRule ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(rule.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    Text(
                                        modeLabels[rule.mode] ?: rule.mode,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    enabled = !isSaving,
                                    onClick = { editingPath = rule.path; newPath = rule.path; newMode = rule.mode; error = null }
                                ) { YukiIcon(Icons.Outlined.Edit, stringResource(R.string.sumh_module_rule_edit_label, rule.path)) }
                                IconButton(
                                    enabled = !isSaving,
                                    onClick = {
                                        rules = rules.filter { r -> r != rule }
                                        if (editingPath == rule.path) cancelEdit()
                                        error = null
                                    }
                                ) {
                                    YukiIcon(Icons.Outlined.Delete, stringResource(R.string.sumh_module_rule_remove_label, rule.path))
                                }
                            }
                        }
                        OutlinedTextField(
                            value = newPath,
                            onValueChange = { newPath = it; error = null },
                            enabled = !isSaving,
                            isError = draftError != null,
                            supportingText = draftError?.let { issue -> { Text(stringResource(moduleRuleErrorResource(issue))) } },
                            label = { Text(stringResource(R.string.sumh_absolute_path)) },
                            placeholder = { Text(stringResource(R.string.sumh_module_rules_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        )
                        if (rulesError != null && rulesError != draftError) Text(
                            stringResource(moduleRuleErrorResource(rulesError)), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!draftModeAvailable) Text(stringResource(R.string.sumh_module_rules_unavailable),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (newMode in listOf("overlay", "magic")) Text(stringResource(R.string.sumh_module_rules_legacy_mode),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box {
                                FilledTonalButton(
                                    enabled = !isSaving,
                                    onClick = { modeExpanded = true },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(modeLabels[newMode] ?: newMode)
                                    YukiIcon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = modeExpanded,
                                    onDismissRequest = { modeExpanded = false }
                                ) {
                                    editorModes.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(modeLabels[mode] ?: mode) },
                                            onClick = {
                                                newMode = mode
                                                modeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            FilledTonalButton(
                                enabled = canSave && hasDraft,
                                onClick = {
                                    if (canSave) {
                                        rules = candidate
                                        cancelEdit()
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(if (editingPath == null) R.string.sumh_module_rules_add else R.string.sumh_rule_save))
                            }
                            if (hasDraft) TextButton(
                                enabled = !isSaving, onClick = { cancelEdit() }, modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.sumh_module_rule_cancel_edit)) }
                        }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isSaving || !canSave) return@Button
                    val rulesToSave = candidate
                    val modeToSave = selectedMode
                    isSaving = true
                    scope.launch {
                        try {
                            onSave(moduleId, modeToSave, rulesToSave)
                            onSaved()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            error = e.message ?: resources.getString(R.string.operation_failed)
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = canSave
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

private fun moduleRuleErrorResource(error: SUMHModuleRuleError): Int = when (error) {
    SUMHModuleRuleError.AbsolutePath -> R.string.sumh_maps_input_path_absolute
    SUMHModuleRuleError.ControlCharacter -> R.string.sumh_maps_path_control
    SUMHModuleRuleError.TooLong -> R.string.sumh_maps_path_target_length
    SUMHModuleRuleError.NonCanonicalPath -> R.string.sumh_module_rule_canonical
    SUMHModuleRuleError.DuplicatePath -> R.string.sumh_module_rule_duplicate
    SUMHModuleRuleError.InvalidMode -> R.string.sumh_module_rule_invalid_mode
    SUMHModuleRuleError.RuleLimit -> R.string.sumh_module_rule_limit
    SUMHModuleRuleError.PayloadLimit -> R.string.sumh_module_rule_budget
}
