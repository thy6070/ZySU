package com.zying.zysu.ui.sumh

import com.zying.zysu.ui.sumh.util.SUMHUiAdapter.ActiveRule
import org.junit.Test
import kotlin.test.assertEquals

class SUMHRuntimeRuleFilterTest {
    @Test
    fun typeAndBothLiteralPathsAreCombinedWithoutNormalizing() {
        val file = ActiveRule("add", "/system/etc/A.conf", "/data/My Module/配置 ")
        val directory = ActiveRule("merge", "/system/app", "/data/Apps")
        val hidden = ActiveRule("hide", "/data/Apps/private")
        val feature = ActiveRule("stealth", "enabled")
        val all = listOf(file, directory, hidden, feature)
        assertEquals(listOf(file, directory), filterRuntimeRules(all, "", RuntimeRuleFilter.Mappings))
        assertEquals(listOf(directory), filterRuntimeRules(all, "APPS", RuntimeRuleFilter.Mappings))
        assertEquals(listOf(file), filterRuntimeRules(all, "配置 ", RuntimeRuleFilter.All))
        assertEquals(emptyList(), filterRuntimeRules(all, "配置  ", RuntimeRuleFilter.All))
        assertEquals(listOf(hidden), filterRuntimeRules(all, "", RuntimeRuleFilter.Hidden))
        assertEquals(listOf(feature), filterRuntimeRules(all, "", RuntimeRuleFilter.Features))
    }
}
