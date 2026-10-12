package com.zying.zysu.ui.sumh.util

import org.json.JSONObject

/** Must run before the controller removes anything from persistent configuration. */
internal fun requireSUMHHideRemoval(path: String, stored: Set<String>, kernel: JSONObject) {
    val managed = kernel.optJSONArray("user_hide")
    val live = managed != null && (0 until managed.length()).any {
        managed.getJSONObject(it).getString("path") == path
    }
    check(path in stored || live) { "Rule is no longer owned by the user; refresh the list" }
    // JNI emits user_hide only when the controller's managed-hide mode is available.
    // A legacy text dump cannot prove ownership: it can be truncated, its paths are
    // unescaped, and DEL_RULE resolves aliases before deleting redirects/hides/injects.
    // Refuse the legacy fallback before `hide remove` changes the saved definitions.
    check(!kernel.getBoolean("sumh_available") || managed != null) {
        "This kernel cannot remove user rules separately from module rules. " +
            "The saved rule was retained; use a kernel with managed-hide support to remove it."
    }
}
