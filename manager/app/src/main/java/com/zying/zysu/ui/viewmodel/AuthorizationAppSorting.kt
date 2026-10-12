package com.zying.zysu.ui.viewmodel

internal fun sortAuthorizationApps(
    groups: List<SuperUserViewModel.AppGroup>,
    sortType: SortType,
): List<SuperUserViewModel.AppGroup> = groups.sortedWith(
    authorizationAppComparator(
        sortType = sortType,
        allowSu = { it.allowSu },
        name = { it.mainApp.label },
        installTime = { it.mainApp.packageInfo.firstInstallTime },
        uid = { it.uid },
    )
)

/** The selected order applies within both authorization groups; UID resolves ties. */
internal fun <T> authorizationAppComparator(
    sortType: SortType,
    allowSu: (T) -> Boolean,
    name: (T) -> String,
    installTime: (T) -> Long,
    uid: (T) -> Int,
): Comparator<T> {
    val selectedOrder = when (sortType) {
        SortType.NAME_ASC -> compareBy<T> { name(it).lowercase() }
        SortType.NAME_DESC -> compareByDescending<T> { name(it).lowercase() }
        SortType.INSTALL_TIME_NEW -> compareByDescending(installTime)
        SortType.INSTALL_TIME_OLD -> compareBy(installTime)
    }
    return compareByDescending(allowSu).then(selectedOrder).thenBy(uid)
}
