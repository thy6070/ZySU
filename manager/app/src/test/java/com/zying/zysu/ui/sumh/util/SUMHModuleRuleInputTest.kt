package com.zying.zysu.ui.sumh.util

import android.app.Application
import com.zying.zysu.ui.sumh.util.SUMHManager.ModuleRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SUMHModuleRuleInputTest {
    @Test fun rejectsAmbiguousPathsWithoutNormalizingLiteralCharacters() {
        for (path in listOf("//system/file", "/system//file", "/system/./file", "/system/../file", "/system/")) {
            assertEquals(SUMHModuleRuleError.NonCanonicalPath, validateSUMHModuleRulePath(path), path)
        }
        for (path in listOf("/", "/system/file ", "/system/ 文件\t", "/system/...", "/system/a\\b")) {
            assertNull(validateSUMHModuleRulePath(path), path)
        }
        assertEquals(SUMHModuleRuleError.AbsolutePath, validateSUMHModuleRulePath(" /system"))
        for (suffix in listOf("\u0000", "\r", "\n"))
            assertEquals(SUMHModuleRuleError.ControlCharacter, validateSUMHModuleRulePath("/file$suffix"))
    }

    @Test fun pathLimitUsesUtf8AndReservesTheTerminator() {
        assertNull(validateSUMHModuleRulePath("/" + "a".repeat(4094)))
        assertEquals(SUMHModuleRuleError.TooLong, validateSUMHModuleRulePath("/" + "a".repeat(4095)))
        assertNull(validateSUMHModuleRulePath("/" + "中".repeat(1364) + "aa"))
        assertEquals(SUMHModuleRuleError.TooLong, validateSUMHModuleRulePath("/" + "中".repeat(1365)))
    }

    @Test fun editingReplacesInPlaceAndDoesNotDuplicateOrResurrectRules() {
        val rules = listOf(ModuleRule("/first", "sumh"), ModuleRule("/second", "hide"))
        val renamed = updateSUMHModuleRule(rules, "/first", ModuleRule("/renamed ", "none"))!!
        assertEquals(listOf(ModuleRule("/renamed ", "none"), rules[1]), renamed)
        assertNull(updateSUMHModuleRule(rules, "/missing", ModuleRule("/new", "hide")))
        assertEquals(SUMHModuleRuleError.DuplicatePath,
            validateSUMHModuleRules(updateSUMHModuleRule(rules, "/first", ModuleRule("/second", "none"))!!))
        assertNull(validateSUMHModuleRules(listOf(ModuleRule("/file", "sumh"), ModuleRule("/file ", "hide"))))
    }

    @Test fun countLimitAllowsEditingAndRemovingAtTheLimit() {
        val rules = List(SUMH_MAX_MODULE_RULES) { ModuleRule("/p$it", "sumh") }
        assertNull(validateSUMHModuleRules(rules))
        assertEquals(SUMHModuleRuleError.RuleLimit, validateSUMHModuleRules(rules + ModuleRule("/extra", "hide")))
        assertNull(validateSUMHModuleRules(updateSUMHModuleRule(rules, "/p0", ModuleRule("/renamed", "hide"))!!))
    }

    @Test fun payloadBudgetAccountsForUnicodeAndRepeatedJsonEscapes() {
        // The same fixture is checked by the production C++ validator test.
        assertEquals(53, moduleRulesEncodedBytes(listOf(ModuleRule("/system/a", "hide"))))
        val rules = List(14) { ModuleRule("/" + "a".repeat(3890) + it, "sumh") }
        assertTrue(moduleRulesWithinBudget(rules))
        assertFalse(moduleRulesWithinBudget(rules + ModuleRule("/" + "b".repeat(3000), "hide")))
        val escaped = listOf(ModuleRule("/a\"\\\t中 ", "hide"))
        assertEquals("/a\"\\\t中 ", moduleRulesJson(escaped).getJSONObject(0).getString("path"))
        assertTrue(moduleRulesEncodedBytes(escaped) > moduleRulesJson(escaped).toString().toByteArray().size)
    }

    @Test fun legacyBackendModesRemainReadableButUnknownModesFail() {
        assertNull(validateSUMHModuleRules(listOf(ModuleRule("/legacy", "overlay"), ModuleRule("/legacy2", "magic"))))
        assertEquals(SUMHModuleRuleError.InvalidMode, validateSUMHModuleRules(listOf(ModuleRule("/file", "redirect"))))
    }
}
