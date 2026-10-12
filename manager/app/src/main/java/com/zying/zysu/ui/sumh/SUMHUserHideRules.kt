package com.zying.zysu.ui.sumh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zying.zysu.R
import com.zying.zysu.ui.component.YukiAlertDialog
import com.zying.zysu.ui.component.YukiIcon
import com.zying.zysu.ui.sumh.util.SUMHHideAction
import com.zying.zysu.ui.sumh.util.SUMHHideBatchResult
import com.zying.zysu.ui.sumh.util.SUMHHideItemResult
import com.zying.zysu.ui.sumh.util.SUMHHidePathError
import com.zying.zysu.ui.sumh.util.SUMHManager
import com.zying.zysu.ui.sumh.util.SUMH_HIDE_BATCH_LIMIT
import com.zying.zysu.ui.sumh.util.previewSUMHHideImport
import com.zying.zysu.ui.sumh.util.SUMHUiAdapter as Controller
import com.zying.zysu.ui.util.SnackbarController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class UserHideFilter { All, Effective, Attention }

internal fun userHideNeedsAttention(rule: Controller.ActiveRule): Boolean = rule.hideState !in listOf(1, 2)

internal fun userHideCanRetry(rule: Controller.ActiveRule): Boolean = rule.persisted && userHideNeedsAttention(rule)

internal fun filterUserHideRules(
    rules: List<Controller.ActiveRule>, query: String, filter: UserHideFilter,
): List<Controller.ActiveRule> = rules.filter { rule ->
    rule.src.contains(query.trim(), ignoreCase = true) && when (filter) {
        UserHideFilter.All -> true
        UserHideFilter.Effective -> rule.hideState == 2
        UserHideFilter.Attention -> userHideNeedsAttention(rule)
    }
}

/** Only successful add commands remove lines from the draft; failed input remains editable. */
internal fun remainingUserHideDraft(draft: String, result: SUMHHideBatchResult): String =
    if (result.action != SUMHHideAction.Add) draft else {
        val completed = result.items.filter { it.succeeded }.mapTo(HashSet()) { it.path }
        draft.lineSequence().filter { it.trim() !in completed }.joinToString("\n")
    }

private val userHideResultSaver = listSaver<UserHideOperationResult?, String>(
    save = { saveUserHideResult(it) },
    restore = ::restoreUserHideResult,
)

/** Destination-scoped state receives operation completion even across an Activity recreation. */
internal class UserHideOperationStore : ViewModel() {
    var working by mutableStateOf(false)
    var batch by mutableStateOf<SUMHHideBatchResult?>(null)
    var result by mutableStateOf<UserHideOperationResult?>(null)
}

@Composable
internal fun UserHideRulesCard(
    enabled: Boolean,
    snackbar: SnackbarController,
    onSavingChanged: (Boolean) -> Unit = {},
    kernelAvailable: Boolean = false,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val operation: UserHideOperationStore = viewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val savingCallback by rememberUpdatedState(onSavingChanged)
    val revision by SUMHManager.revision.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }
    var states by remember { mutableStateOf(emptyList<Controller.ActiveRule>()) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var readRevision by remember { mutableStateOf<Long?>(null) }
    var readRefreshKey by remember { mutableStateOf<Int?>(null) }
    val working = operation.working
    var loadError by remember { mutableStateOf<String?>(null) }
    var savedResult by rememberSaveable(stateSaver = userHideResultSaver) { mutableStateOf<UserHideOperationResult?>(null) }
    val result = operation.result ?: savedResult
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(UserHideFilter.All) }
    var draft by rememberSaveable { mutableStateOf("") }
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    var suggestionsOpen by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(stateSaver = listSaver(
        save = { paths: Set<String> -> paths.toList() },
        restore = { it.toSet() },
    )) { mutableStateOf(emptySet<String>()) }
    var removeConfirmation by remember { mutableStateOf<List<String>?>(null) }

    SideEffect {
        savingCallback(working)
        operation.result?.let { savedResult = it }
    }
    LaunchedEffect(operation.batch) {
        operation.batch?.let { draft = remainingUserHideDraft(draft, it) }
    }

    suspend fun reload() {
        readRevision = SUMHManager.revision.value
        readRefreshKey = refreshKey
        try {
            val latest = Controller.userHideStates()
            states = latest
            selection = selection.intersect(latest.mapTo(HashSet()) { it.src })
            loaded = true
            loadError = null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            loadError = error.message?.takeIf(String::isNotBlank) ?: resources.getString(R.string.operation_failed)
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, refreshKey, working) {
        // A mutation performs its own refresh. A revision emitted by that mutation
        // must not start a competing read or cancel its operation result.
        if (!working && (readRevision != revision || readRefreshKey != refreshKey)) {
            loading = true
            try { reload() } finally { loading = false }
        }
    }

    val canEdit = enabled && loaded && !loading && !working && loadError == null
    fun mutate(action: SUMHHideAction, paths: List<String>) {
        if (operation.working || loading || !enabled || !loaded || loadError != null ||
            paths.isEmpty() || paths.distinct().size > SUMH_HIDE_BATCH_LIMIT ||
            (action == SUMHHideAction.Retry && !kernelAvailable)) return
        val frozenPaths = paths.distinct().toList()
        operation.working = true
        savingCallback(true)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Finish accepted mutations and refresh even if this card leaves composition.
            withContext(NonCancellable) {
                try {
                    val outcome = try {
                        Controller.changeUserHideRules(action, frozenPaths)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        val message = error.message?.takeIf(String::isNotBlank)
                            ?: resources.getString(R.string.operation_failed)
                        SUMHHideBatchResult(action, frozenPaths.map { SUMHHideItemResult(it, message) })
                    }
                    operation.result = UserHideOperationResult.from(outcome)
                    operation.batch = outcome
                    draft = remainingUserHideDraft(draft, outcome)
                    reload()
                } finally {
                    operation.working = false
                    savingCallback(false)
                }
            }
        }
    }

    val visible = remember(states, query, filter) { filterUserHideRules(states, query, filter) }
    val selected = states.filter { it.src in selection }
    val retryable = selected.filter(::userHideCanRetry)
    val existing = states.filter { it.persisted }.mapTo(HashSet()) { it.src }
    val preview = remember(draft, existing) { previewSUMHHideImport(draft, existing) }
    fun copyPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(
            resources.getString(R.string.sumh_user_hide_title), paths.joinToString("\n"),
        ))
        scope.launch { snackbar.showSnackbar(resources.getString(R.string.sumh_user_rules_copied, paths.size)) }
    }

    if (previewOpen) {
        YukiAlertDialog(
            onDismissRequest = { previewOpen = false },
            title = { Text(stringResource(R.string.sumh_user_rules_preview)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sumh_user_rules_preview_count, preview.paths.size,
                        preview.lines.count { it.duplicate }, preview.lines.count { it.error != null }))
                    Text(stringResource(R.string.sumh_user_rules_batch_limit, SUMH_HIDE_BATCH_LIMIT),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (preview.paths.size > SUMH_HIDE_BATCH_LIMIT) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    preview.lines.forEach { line ->
                        Text(stringResource(R.string.sumh_user_rules_preview_line, line.number, line.path),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = when {
                                line.error != null -> userHidePathError(line.error)
                                line.duplicate -> stringResource(R.string.sumh_user_rules_duplicate)
                                else -> stringResource(R.string.sumh_user_rules_ready)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = canEdit && preview.canApply, onClick = {
                    if (canEdit && preview.canApply) {
                        previewOpen = false
                        mutate(SUMHHideAction.Add, preview.paths)
                    }
                }) { Text(stringResource(R.string.sumh_user_hide_add)) }
            },
            dismissButton = {
                TextButton(onClick = { previewOpen = false }) { Text(stringResource(R.string.sumh_rules_cancel)) }
            },
        )
    }

    removeConfirmation?.let { paths ->
        YukiAlertDialog(
            onDismissRequest = { removeConfirmation = null },
            title = { Text(stringResource(R.string.sumh_user_rules_remove_count, paths.size)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sumh_user_rules_remove_confirm))
                    paths.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = canEdit && paths.size <= SUMH_HIDE_BATCH_LIMIT,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = { removeConfirmation = null; mutate(SUMHHideAction.Remove, paths) }) {
                    Text(stringResource(R.string.sumh_user_hide_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirmation = null }) { Text(stringResource(R.string.sumh_rules_cancel)) }
            },
        )
    }

    ConfigSection(
        title = stringResource(R.string.sumh_user_hide_title),
        actions = {
            IconButton(onClick = { refreshKey++ }, enabled = !working && !loading) {
                YukiIcon(Icons.Filled.Refresh, stringResource(R.string.sumh_rules_refresh))
            }
        },
    ) {
        Text(stringResource(R.string.sumh_user_rules_description), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (working || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        loadError?.let {
            Text(stringResource(R.string.sumh_user_rules_load_error, it),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (!kernelAvailable) Text(stringResource(R.string.sumh_user_rules_kernel_unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sumh_user_rules_search)) },
            leadingIcon = { YukiIcon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                YukiIcon(Icons.Filled.Close, stringResource(R.string.sumh_user_rules_clear_search))
            } },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UserHideFilter.entries.forEach { item ->
                val count = when (item) {
                    UserHideFilter.All -> states.size
                    UserHideFilter.Effective -> states.count { it.hideState == 2 }
                    UserHideFilter.Attention -> states.count(::userHideNeedsAttention)
                }
                FilterChip(selected = filter == item, onClick = { filter = item },
                    modifier = Modifier.heightIn(min = 48.dp), label = {
                        Text(stringResource(when (item) {
                            UserHideFilter.All -> R.string.sumh_user_rules_filter_all
                            UserHideFilter.Effective -> R.string.sumh_user_rules_filter_effective
                            UserHideFilter.Attention -> R.string.sumh_user_rules_filter_attention
                        }, count))
                    })
            }
        }
        Text(stringResource(R.string.sumh_user_rules_selected, selection.size, selected.count { it in visible }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selection = selection + visible.map { it.src } },
                enabled = canEdit && visible.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sumh_user_rules_select_visible))
            }
            TextButton(onClick = { selection = emptySet() }, enabled = !working && selection.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sumh_user_rules_clear_selection)) }
        }
        if (loaded && visible.isEmpty()) {
            Text(stringResource(if (states.isEmpty()) R.string.sumh_user_rules_empty else R.string.sumh_user_rules_no_match),
                modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visible.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(visible, key = { it.src }) { rule ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .toggleable(value = rule.src in selection, enabled = canEdit, role = Role.Checkbox,
                            onValueChange = { checked -> selection = if (checked) selection + rule.src else selection - rule.src })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = rule.src in selection, onCheckedChange = null, enabled = canEdit)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(rule.src, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text(if (rule.hideState == null) stringResource(R.string.sumh_user_rules_unknown) else userHideStatus(rule),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (rule.hideState == 3) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!rule.persisted) Text(stringResource(R.string.sumh_user_rules_runtime_only),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (selection.isNotEmpty()) {
            if (selection.size > SUMH_HIDE_BATCH_LIMIT) Text(
                stringResource(R.string.sumh_user_rules_batch_limit, SUMH_HIDE_BATCH_LIMIT),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { mutate(SUMHHideAction.Retry, retryable.map { it.src }) },
                    enabled = canEdit && kernelAvailable && retryable.size in 1..SUMH_HIDE_BATCH_LIMIT,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (retryable.size == 1) stringResource(R.string.sumh_hide_retry)
                        else stringResource(R.string.sumh_user_rules_retry_count, retryable.size))
                }
                OutlinedButton(onClick = { removeConfirmation = selected.map { it.src }.toList() },
                    enabled = canEdit && selected.size in 1..SUMH_HIDE_BATCH_LIMIT,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.sumh_user_rules_remove_count, selected.size))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { copyPaths(selected.map { it.src }) }, enabled = selected.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sumh_user_rules_copy_selected))
            }
            TextButton(onClick = { copyPaths(visible.filter { it.persisted }.map { it.src }) },
                enabled = visible.any { it.persisted }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sumh_user_rules_copy_visible))
            }
        }

        result?.let { outcome ->
            HorizontalDivider()
            val failed = outcome.failures
            Text(stringResource(when (outcome.action) {
                SUMHHideAction.Add -> R.string.sumh_user_rules_result_add
                SUMHHideAction.Remove -> R.string.sumh_user_rules_result_remove
                SUMHHideAction.Retry -> R.string.sumh_user_rules_result_retry
            }), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.sumh_user_rules_result, outcome.completedCount, outcome.failedCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(stringResource(R.string.sumh_user_rules_result_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (outcome.omittedFailures > 0) Text(
                stringResource(R.string.sumh_user_rules_result_omitted, outcome.omittedFailures),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (outcome.shortenedErrors) Text(stringResource(R.string.sumh_user_rules_result_shortened),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(failed, key = { it.path }) { item ->
                        Column {
                            Text(item.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text(item.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedButton(onClick = {
                    if (outcome.action == SUMHHideAction.Remove) removeConfirmation = failed.map { it.path }
                    else mutate(outcome.action, failed.map { it.path })
                }, enabled = canEdit && (outcome.action != SUMHHideAction.Retry || kernelAvailable),
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (outcome.omittedFailures > 0) R.string.sumh_user_rules_retry_restored_failed
                        else R.string.sumh_user_rules_retry_failed, failed.size))
                }
            }
        }

        HorizontalDivider()
        OutlinedTextField(value = draft, onValueChange = { draft = it }, enabled = !working,
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
            label = { Text(stringResource(R.string.sumh_user_rules_add_label)) },
            placeholder = { Text(stringResource(R.string.sumh_user_hide_placeholder)) },
            supportingText = { Text(stringResource(R.string.sumh_user_rules_add_hint, SUMH_HIDE_BATCH_LIMIT)) },
        )
        Button(onClick = { previewOpen = true }, enabled = canEdit && draft.isNotBlank(),
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            YukiIcon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sumh_user_rules_preview))
        }
        TextButton(onClick = { suggestionsOpen = !suggestionsOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
            YukiIcon(if (suggestionsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sumh_quick_hide))
        }
        if (suggestionsOpen) {
            val external = android.os.Environment.getExternalStorageDirectory()
            listOf("/dev/scene", "/dev/cpuset/scene-daemon", external.resolve("Download/advanced").path,
                external.resolve("MT2").path).forEach { path ->
                TextButton(onClick = { draft = listOf(draft.trimEnd(), path).filter(String::isNotEmpty).joinToString("\n") },
                    enabled = !working && path !in existing && path !in draft.lineSequence().map { it.trim() }.toSet(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(path, modifier = Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun userHidePathError(error: SUMHHidePathError): String = stringResource(when (error) {
    SUMHHidePathError.NotAbsolute -> R.string.sumh_user_rules_invalid_absolute
    SUMHHidePathError.Root -> R.string.sumh_user_rules_invalid_root
    SUMHHidePathError.InvalidComponent -> R.string.sumh_user_rules_invalid_component
    SUMHHidePathError.ControlCharacter -> R.string.sumh_user_rules_invalid_control
    SUMHHidePathError.TooLong -> R.string.sumh_user_rules_invalid_length
    SUMHHidePathError.Protected -> R.string.sumh_user_rules_invalid_protected
})
