package com.zying.zysu.ui.sumh

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.zying.zysu.R
import com.zying.zysu.ui.sumh.util.SUMHUiAdapter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w360dp-h760dp-xhdpi")
class SUMHRuntimeRuleDisplayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun partialKernelListExplainsCountAndKeepsRefreshAvailable() {
        var refreshed = 0
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            RulesTab(listOf(SUMHUiAdapter.ActiveRule("hide", "/data/bound", hideState = 2)),
                SUMHUiAdapter.SUMHStatus.AVAILABLE, true, { refreshed++ }, {}, rulesIncomplete = true)
        } } }
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_rules_runtime_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_rules_runtime_incomplete)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_rules_refresh)).performClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun featureIsNamedWithoutClaimingModuleOwnership() {
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            RulesTab(listOf(SUMHUiAdapter.ActiveRule("kernel_build_spoof", "enabled")),
                SUMHUiAdapter.SUMHStatus.AVAILABLE, true, {}, {})
        } } }
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_kernel_build_spoof)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_rules_runtime_feature)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_rule_module)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_rules_runtime_incomplete)).assertDoesNotExist()
    }
}
