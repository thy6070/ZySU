package com.zying.zysu.ui.sumh

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.zying.zysu.R
import com.zying.zysu.ui.sumh.util.SUMHUiAdapter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun searchMatchesSourceAndResetRestoresTheFileView() {
        val mapping = SUMHUiAdapter.ActiveRule("add", "/system/etc/example.conf", "/data/adb/modules/demo/配置 ")
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            RulesTab(listOf(mapping, SUMHUiAdapter.ActiveRule("hide", "/data/hidden")),
                SUMHUiAdapter.SUMHStatus.AVAILABLE, true, {}, {})
        } } }
        compose.onNode(hasSetTextAction()).performTextInput("配置 ")
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_runtime_filtered_count, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(mapping.src).performScrollTo().assertIsDisplayed()
        capture("mapping-search")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNode(hasSetTextAction()).performTextReplacement("missing")
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_runtime_reset_filters)).performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_rules_runtime_count, 2)).assertIsDisplayed()
    }

    @Test
    fun detailsCopyLiteralPathsAndKeepAmbiguousOperandsIntact() {
        val path = "/data/adb/modules/My Module/配置 "
        val raw = "/system/My Files /data/Other Files"
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            RulesTab(listOf(SUMHUiAdapter.ActiveRule("merge", "/system/etc", path),
                SUMHUiAdapter.ActiveRule("add", raw, rawOperands = true)),
                SUMHUiAdapter.SUMHStatus.AVAILABLE, true, {}, {})
        } } }
        compose.onNodeWithText(path).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_runtime_copy_source)).performScrollTo().performClick()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(path, clipboard.primaryClip!!.getItemAt(0).text.toString())
        capture("mapping-details", dialog = true)
        compose.onNodeWithText(compose.activity.getString(R.string.close)).performClick()
        compose.onNodeWithText(raw).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_runtime_copy_original)).performScrollTo().performClick()
        assertEquals(raw, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNode(hasText(compose.activity.getString(R.string.sumh_runtime_source_path)) and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }

    @Test
    fun typeFilterAndQuerySurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            RulesTab(listOf(SUMHUiAdapter.ActiveRule("add", "/system/example", "/data/source"),
                SUMHUiAdapter.ActiveRule("hide", "/data/source")),
                SUMHUiAdapter.SUMHStatus.AVAILABLE, true, {}, {})
        } } }
        compose.onNode(hasSetTextAction()).performTextInput("source")
        compose.onNode(isSelectable() and hasText(compose.activity.getString(R.string.sumh_runtime_filter_mappings))).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("source")
        compose.onNode(isSelectable() and hasText(compose.activity.getString(R.string.sumh_runtime_filter_mappings))).assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.sumh_runtime_filtered_count, 1, 2)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w760dp-h360dp-land-xhdpi")
    fun landscapeLargeTextCanReachRowsAndCloseDetails() {
        RuntimeEnvironment.setFontScale(2f)
        val path = "/system/very-long-directory/another-directory/example.conf"
        compose.setContent { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                RulesTab(listOf(SUMHUiAdapter.ActiveRule("merge", path, "/data/adb/modules/demo/system/etc")),
                    SUMHUiAdapter.SUMHStatus.AVAILABLE, true, {}, {})
            }
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(path))
        compose.onNodeWithText(path).assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.close)).assertIsDisplayed().performClick()
        capture("mapping-landscape-large-text")
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = root.captureToImage().asAndroidBitmap()
        val output = File("build/reports/sumh-layout/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
