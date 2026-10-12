package com.zying.zysu.ui.sumh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zying.zysu.R
import com.zying.zysu.ui.component.YukiAlertDialog
import com.zying.zysu.ui.component.YukiIcon
import com.zying.zysu.ui.sumh.util.SUMHFeatureFilter
import com.zying.zysu.ui.sumh.util.SUMHFeatureState
import com.zying.zysu.ui.sumh.util.SUMHFeatureSupport
import com.zying.zysu.ui.sumh.util.SUMHManagedFeature
import com.zying.zysu.ui.sumh.util.matches
import com.zying.zysu.ui.sumh.util.sumhFeatureStates
import com.zying.zysu.ui.sumh.util.unsupportedConfiguredFeatures
import com.zying.zysu.ui.util.SnackbarController
import ui.screen.moreSettings.component.MoreSettingsItemPosition
import com.zying.zysu.ui.sumh.util.SUMHUiAdapter as Controller

private val SUMHManagedFeature.titleRes: Int
    get() = when (this) {
        SUMHManagedFeature.Stealth -> R.string.sumh_stealth
        SUMHManagedFeature.MountHide -> R.string.sumh_mount_hide
        SUMHManagedFeature.OverlayXattr -> R.string.sumh_overlay_xattr
        SUMHManagedFeature.Statfs -> R.string.sumh_statfs_spoof
        SUMHManagedFeature.Maps -> R.string.sumh_maps_spoof
        SUMHManagedFeature.KernelBuild -> R.string.sumh_kernel_build_spoof
    }

private val SUMHManagedFeature.descriptionRes: Int
    get() = when (this) {
        SUMHManagedFeature.Stealth -> R.string.sumh_stealth_desc
        SUMHManagedFeature.MountHide -> R.string.sumh_mount_hide_desc
        SUMHManagedFeature.OverlayXattr -> R.string.sumh_overlay_xattr_desc
        SUMHManagedFeature.Statfs -> R.string.sumh_statfs_spoof_desc
        SUMHManagedFeature.Maps -> R.string.sumh_maps_spoof_desc
        SUMHManagedFeature.KernelBuild -> R.string.sumh_kernel_build_spoof_desc
    }

private val SUMHManagedFeature.icon: ImageVector
    get() = when (this) {
        SUMHManagedFeature.Stealth -> Icons.Filled.VisibilityOff
        SUMHManagedFeature.MountHide -> Icons.Filled.FolderOff
        SUMHManagedFeature.OverlayXattr -> Icons.Filled.Security
        SUMHManagedFeature.Statfs -> Icons.Filled.Storage
        SUMHManagedFeature.Maps, SUMHManagedFeature.KernelBuild -> Icons.Filled.Memory
    }

internal fun Controller.SUMHPConfig.configuredSUMHFeatures(): Set<SUMHManagedFeature> = buildSet {
    if (enableStealth) add(SUMHManagedFeature.Stealth)
    if (enableMountHide) add(SUMHManagedFeature.MountHide)
    if (enableOverlayXattrHide) add(SUMHManagedFeature.OverlayXattr)
    if (enableStatfsSpoof) add(SUMHManagedFeature.Statfs)
    if (enableMapsSpoof) add(SUMHManagedFeature.Maps)
    if (enableKernelBuildSpoof) add(SUMHManagedFeature.KernelBuild)
}

internal fun Controller.SUMHPConfig.disableSUMHFeatures(features: Set<SUMHManagedFeature>) = copy(
    enableStealth = enableStealth && SUMHManagedFeature.Stealth !in features,
    enableMountHide = enableMountHide && SUMHManagedFeature.MountHide !in features,
    enableOverlayXattrHide = enableOverlayXattrHide && SUMHManagedFeature.OverlayXattr !in features,
    enableStatfsSpoof = enableStatfsSpoof && SUMHManagedFeature.Statfs !in features,
    enableMapsSpoof = enableMapsSpoof && SUMHManagedFeature.Maps !in features,
    enableKernelBuildSpoof = enableKernelBuildSpoof && SUMHManagedFeature.KernelBuild !in features,
)

@Composable
internal fun sumhFeatureSummary(description: String, state: SUMHFeatureState): String {
    val configured = stringResource(if (state.configuredEnabled) R.string.sumh_features_configured_on
        else R.string.sumh_features_configured_off)
    val support = stringResource(when (state.support) {
        SUMHFeatureSupport.Supported -> R.string.sumh_features_support_yes
        SUMHFeatureSupport.Unsupported -> if (state.configuredEnabled) R.string.sumh_disable_unsupported
            else R.string.sumh_features_support_no
        SUMHFeatureSupport.Unknown -> R.string.sumh_features_support_unknown
        SUMHFeatureSupport.KernelUnavailable -> R.string.sumh_features_kernel_unavailable
    })
    val status = stringResource(R.string.sumh_features_item_status, configured, support)
    val clearHint = if (state.configuredEnabled && state.support in
        setOf(SUMHFeatureSupport.Unknown, SUMHFeatureSupport.KernelUnavailable))
        "\n" + stringResource(R.string.sumh_features_can_disable) else ""
    return "$description\n$status$clearHint"
}

@Composable
internal fun IsolationFeatureSettings(
    config: Controller.SUMHPConfig,
    kernelAvailable: Boolean,
    features: Controller.FeaturesResult?,
    controlsEnabled: Boolean,
    snackbarHostState: SnackbarController,
    onConfigChanged: (Controller.SUMHPConfig) -> Unit,
    onUserRulesSavingChanged: (Boolean) -> Unit = {},
    onClearMapRules: () -> Unit,
    onAddMapRule: (Long, Long, Long, Long, String) -> Unit,
    headingActions: (@Composable RowScope.() -> Unit)?,
) {
    val resources = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SUMHFeatureFilter.All) }
    val editorState = rememberSaveableStateHolder()
    val states = sumhFeatureStates(config.configuredSUMHFeatures(), kernelAvailable, features?.names?.toSet())
    val visible = states.filter { state ->
        state.matches(filter, query, listOf(resources.getString(state.feature.titleRes),
            resources.getString(state.feature.descriptionRes)))
    }
    val cleanup = unsupportedConfiguredFeatures(states)
    var cleanupTargets by remember { mutableStateOf<Set<SUMHManagedFeature>?>(null) }

    FeatureManagementOverview(
        states = states,
        query = query,
        onQueryChanged = { query = it },
        filter = filter,
        onFilterChanged = { filter = it },
        visibleCount = visible.size,
        controlsEnabled = controlsEnabled,
        cleanupAvailable = cleanup.isNotEmpty(),
        onCleanup = { cleanupTargets = cleanup },
        headingActions = headingActions,
    )

    if (visible.isEmpty()) {
        ConfigSection {
            Text(stringResource(R.string.sumh_features_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { query = ""; filter = SUMHFeatureFilter.All },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.sumh_features_reset)) }
        }
    }

    val buildState = visible.find { it.feature == SUMHManagedFeature.KernelBuild }
    if (buildState != null) {
        // Filtering should not discard an unfinished kernel-build editor.
        editorState.SaveableStateProvider(SUMHManagedFeature.KernelBuild.name) {
            KernelBuildSpoofCard(
                config = config,
                controlsEnabled = controlsEnabled,
                kernelEnabled = controlsEnabled && kernelAvailable,
                support = buildState.support,
                onConfigChanged = onConfigChanged,
            )
        }
    }

    val groups = listOf(
        R.string.sumh_group_mount_visibility to setOf(SUMHManagedFeature.Stealth, SUMHManagedFeature.MountHide),
        R.string.sumh_group_filesystem to setOf(SUMHManagedFeature.OverlayXattr, SUMHManagedFeature.Statfs),
        R.string.sumh_maps_title to setOf(SUMHManagedFeature.Maps),
    )
    groups.forEach { (title, members) ->
        val group = visible.filter { it.feature in members }
        if (group.isNotEmpty()) {
            ConfigSection(stringResource(title), segmented = true) {
                group.forEachIndexed { index, state ->
                    val mapControls = state.feature == SUMHManagedFeature.Maps && state.configuredEnabled &&
                        state.support == SUMHFeatureSupport.Supported
                    val position = when {
                        group.size == 1 && !mapControls -> MoreSettingsItemPosition.Only
                        index == 0 -> MoreSettingsItemPosition.First
                        index == group.lastIndex -> MoreSettingsItemPosition.Last
                        else -> MoreSettingsItemPosition.Middle
                    }
                    SettingSwitch(
                        title = stringResource(state.feature.titleRes),
                        subtitle = sumhFeatureSummary(stringResource(state.feature.descriptionRes), state),
                        checked = state.configuredEnabled,
                        onCheckedChange = { checked ->
                            val next = when (state.feature) {
                                SUMHManagedFeature.Stealth -> config.copy(enableStealth = checked)
                                SUMHManagedFeature.MountHide -> config.copy(enableMountHide = checked)
                                SUMHManagedFeature.OverlayXattr -> config.copy(enableOverlayXattrHide = checked)
                                SUMHManagedFeature.Statfs -> config.copy(enableStatfsSpoof = checked)
                                SUMHManagedFeature.Maps -> config.copy(enableMapsSpoof = checked)
                                SUMHManagedFeature.KernelBuild -> config.copy(enableKernelBuildSpoof = checked)
                            }
                            onConfigChanged(next)
                        },
                        enabled = controlsEnabled && state.canChange,
                        icon = state.feature.icon,
                        position = position,
                    )
                    if (mapControls) MapsSpoofControls(controlsEnabled && kernelAvailable, onClearMapRules, onAddMapRule)
                }
            }
        }
    }
    // Path rules remain independent of the feature search and toggles.
    UserHideRulesCard(
        enabled = controlsEnabled,
        snackbar = snackbarHostState,
        kernelAvailable = kernelAvailable,
        onSavingChanged = onUserRulesSavingChanged,
    )

    cleanupTargets?.let { targets ->
        YukiAlertDialog(
            onDismissRequest = { cleanupTargets = null },
            title = { Text(stringResource(R.string.sumh_features_cleanup)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sumh_features_cleanup_confirm))
                    targets.forEach { Text("• " + stringResource(it.titleRes)) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = controlsEnabled && targets.isNotEmpty() && targets.all { it in cleanup },
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        if (controlsEnabled && targets.all { it in cleanup }) {
                            cleanupTargets = null
                            onConfigChanged(config.disableSUMHFeatures(targets))
                        }
                    },
                ) { Text(stringResource(R.string.sumh_features_cleanup_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { cleanupTargets = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.sumh_rules_cancel))
                }
            },
        )
    }
}

@Composable
private fun FeatureManagementOverview(
    states: List<SUMHFeatureState>,
    query: String,
    onQueryChanged: (String) -> Unit,
    filter: SUMHFeatureFilter,
    onFilterChanged: (SUMHFeatureFilter) -> Unit,
    visibleCount: Int,
    controlsEnabled: Boolean,
    cleanupAvailable: Boolean,
    onCleanup: () -> Unit,
    headingActions: (@Composable RowScope.() -> Unit)?,
) {
    val focusManager = LocalFocusManager.current
    ConfigSection(stringResource(R.string.sumh_features_title), actions = headingActions) {
        Text(
            stringResource(R.string.sumh_features_configured_count, states.count { it.configuredEnabled }, states.size),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when {
                states.any { it.support == SUMHFeatureSupport.KernelUnavailable } ->
                    stringResource(R.string.sumh_features_kernel_unavailable)
                states.any { it.support == SUMHFeatureSupport.Unknown } ->
                    stringResource(R.string.sumh_features_support_unknown)
                else -> stringResource(R.string.sumh_features_supported_count,
                    states.count { it.support == SUMHFeatureSupport.Supported }, states.size)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.sumh_features_saved_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            label = { Text(stringResource(R.string.sumh_features_search)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            leadingIcon = { YukiIcon(Icons.Filled.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQueryChanged("") }) {
                    YukiIcon(Icons.Filled.Close, stringResource(R.string.sumh_features_clear_search))
                } }
            } else null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SUMHFeatureFilter.entries.forEach { choice ->
                FilterChip(
                    selected = filter == choice,
                    onClick = { onFilterChanged(choice) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(stringResource(when (choice) {
                        SUMHFeatureFilter.All -> R.string.sumh_filter_all
                        SUMHFeatureFilter.ConfiguredOn -> R.string.sumh_features_filter_on
                        SUMHFeatureFilter.Supported -> R.string.sumh_features_filter_supported
                        SUMHFeatureFilter.Unsupported -> R.string.sumh_features_filter_unsupported
                    })) },
                    leadingIcon = if (filter == choice) {
                        { YukiIcon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
            }
        }
        Text(stringResource(R.string.sumh_features_result_count, visibleCount, states.size),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (cleanupAvailable) {
            TextButton(onClick = onCleanup, enabled = controlsEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sumh_features_cleanup))
            }
        }
    }
}
