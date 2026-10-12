package com.zying.zysu.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorizationAppSortingTest {
    private data class App(val uid: Int, val name: String, val installTime: Long, val allowSu: Boolean)

    private val apps = listOf(
        App(2000, "delta", 500, false),
        App(10002, "Charlie", 100, true),
        App(10005, "Echo", 600, false),
        App(10001, "alpha", 200, true),
        App(10004, "Foxtrot", 400, false),
        App(10003, "Bravo", 300, true),
    )

    private fun List<App>.sortedUids(sortType: SortType): List<Int> = sortedWith(
        authorizationAppComparator(sortType, App::allowSu, App::name, App::installTime, App::uid)
    ).map { it.uid }

    @Test fun everySortKeepsAuthorizedAppsFirstAndOrdersBothGroups() {
        val expected = mapOf(
            SortType.NAME_ASC to listOf(10001, 10003, 10002, 2000, 10005, 10004),
            SortType.NAME_DESC to listOf(10002, 10003, 10001, 10004, 10005, 2000),
            SortType.INSTALL_TIME_NEW to listOf(10003, 10001, 10002, 10005, 2000, 10004),
            SortType.INSTALL_TIME_OLD to listOf(10002, 10001, 10003, 10004, 2000, 10005),
        )
        expected.forEach { (sortType, order) ->
            assertEquals(order, apps.sortedUids(sortType), sortType.name)
        }
    }

    @Test fun grantAndRevocationMoveAppsBetweenSortedAuthorizationGroups() {
        val granted = apps.map { if (it.uid == 10005) it.copy(allowSu = true) else it }
        assertEquals(listOf(10001, 10003, 10002, 10005, 2000, 10004), granted.sortedUids(SortType.NAME_ASC))

        val revoked = granted.map { if (it.uid == 10001) it.copy(allowSu = false) else it }
        assertEquals(listOf(10003, 10002, 10005, 10001, 2000, 10004), revoked.sortedUids(SortType.NAME_ASC))
    }

    @Test fun equalNamesAndInstallTimesUseAscendingUidForEverySort() {
        val tiedApps = listOf(
            App(10004, "same", 100, false),
            App(10002, "SAME", 100, true),
            App(2000, "Same", 100, false),
            App(10001, "sAME", 100, true),
        )
        SortType.entries.forEach { sortType ->
            assertEquals(listOf(10001, 10002, 2000, 10004), tiedApps.sortedUids(sortType), sortType.name)
        }
    }
}
