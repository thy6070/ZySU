package com.zying.zysu.ui.sumh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/** Each rule owns its draft, so visiting another editor does not discard an unfinished edit. */
internal class UserHideDrafts {
    var add by mutableStateOf("")
    var bulk by mutableStateOf("")
    private val edits = mutableStateMapOf<String, String>()

    fun edit(path: String): String = edits[path] ?: path
    fun update(path: String, value: String) {
        if (path == value) edits.remove(path) else edits[path] = value
    }
    fun forget(path: String) { edits.remove(path) }

    companion object {
        val Saver = listSaver<UserHideDrafts, String>(
            save = { listOf(it.add, it.bulk) + it.edits.flatMap { (path, draft) -> listOf(path, draft) } },
            restore = { values -> UserHideDrafts().apply {
                add = values[0]
                bulk = values[1]
                values.drop(2).chunked(2).forEach { (path, draft) -> update(path, draft) }
            } },
        )
    }
}

/** Selection changes only affect the filtered list; hidden selections remain intact. */
internal fun toggleVisibleUserHideSelection(selected: Set<String>, visible: Set<String>): Set<String> =
    if (visible.isNotEmpty() && selected.containsAll(visible)) selected - visible else selected + visible

internal fun invertVisibleUserHideSelection(selected: Set<String>, visible: Set<String>): Set<String> =
    (selected - visible) + (visible - selected)
