package com.zying.zysu.ui.util.module

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.PersistableBundle
import app.entry.Launcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowShortcutManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [MigrationShortcutManager::class])
class ShortcutMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: ShortcutManager get() = context.getSystemService(ShortcutManager::class.java)
    private val shadow: MigrationShortcutManager get() = Shadow.extract(manager)
    private val previousLauncher get() = ComponentName(context.packageName, "legacy.entry.Home")
    private val launcher get() = ComponentName(context, Launcher::class.java)

    @Test
    fun pinnedAndDynamicActionsKeepTheirContentWhileTheirTargetsChange() {
        val pinned = actionShortcut("pinned")
        val dynamic = actionShortcut("dynamic")
        manager.requestPinShortcut(pinned, null)
        manager.addDynamicShortcuts(listOf(dynamic))
        val icons = listOf(pinned, dynamic).associate { it.id to iconOf(it) }

        Shortcut.migrateModuleShortcuts(context)

        val migrated = manager.pinnedShortcuts + manager.dynamicShortcuts
        assertEquals(setOf(pinned.id, dynamic.id), migrated.map { it.id }.toSet())
        assertEquals(1, shadow.updateRequests.size)
        for (shortcut in migrated) {
            val intent = assertNotNull(shortcut.intent)
            assertEquals(launcher, shortcut.activity)
            assertEquals(launcher, intent.component)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(Uri.parse("test://module/${shortcut.id}"), intent.data)
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.flags)
            assertEquals("module_action", intent.getStringExtra("shortcut_type"))
            assertEquals(shortcut.id, intent.getStringExtra("module_id"))
            assertEquals("Action ${shortcut.id}", shortcut.shortLabel)
            assertEquals("Long ${shortcut.id}", shortcut.longLabel)
            assertEquals(setOf("module"), shortcut.categories)
            assertEquals(3, shortcut.rank)
            assertEquals("saved", shortcut.extras?.getString("metadata"))
            assertSame(icons[shortcut.id], iconOf(shortcut))
        }
        for (update in shadow.updateRequests.single()) {
            assertNull(update.shortLabel)
            assertNull(iconOf(update))
        }

        Shortcut.migrateModuleShortcuts(context)
        assertEquals(1, shadow.updateRequests.size, "A later launch should not republish migrated actions")
    }

    @Test
    fun preservesUnrelatedBackStackEntriesAndLeavesOtherShortcutTypesAlone() {
        val external = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .setComponent(ComponentName("other.app", "other.app.Home"))
        val action = actionShortcut("stack", leadingIntent = external)
        val other = actionShortcut("other", idPrefix = "other_")
        manager.addDynamicShortcuts(listOf(action, other))

        Shortcut.migrateModuleShortcuts(context)

        val shortcuts = manager.dynamicShortcuts.associateBy { it.id }
        val migrated = assertNotNull(shortcuts[action.id])
        val intents = assertNotNull(migrated.intents)
        assertEquals(external.component, intents.first().component)
        assertEquals(external.data, intents.first().data)
        assertEquals(launcher, intents.last().component)
        assertEquals(previousLauncher, shortcuts[other.id]?.activity)
        assertEquals(listOf(action.id), shadow.updateRequests.single().map { it.id })
    }

    @Test
    fun webUiRecoversItsLauncherAssociationWithoutChangingItsLaunchIntent() {
        val webUiActivity = ComponentName(context, com.zying.zysu.ui.webui.WebUIActivity::class.java)
        val originalIntent = Intent(Intent.ACTION_VIEW, Uri.parse("kernelsu://webui/example"))
            .setComponent(webUiActivity)
            .putExtra("id", "example")
            .putExtra("name", "Example module")
            .putExtra("from_webui_shortcut", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val shortcut = ShortcutInfo.Builder(context, "module_webui_example")
            .setActivity(previousLauncher)
            .setShortLabel("Example module")
            .setIntent(originalIntent)
            .build()
        manager.requestPinShortcut(shortcut, null)
        disable(shortcut, ShortcutInfo.DISABLED_REASON_APP_CHANGED)

        Shortcut.migrateModuleShortcuts(context)

        assertEquals(launcher, shortcut.activity)
        assertTrue(shortcut.isEnabled)
        val intent = assertNotNull(shortcut.intent)
        assertEquals(webUiActivity, intent.component)
        assertEquals(originalIntent.action, intent.action)
        assertEquals(originalIntent.data, intent.data)
        assertEquals(originalIntent.flags, intent.flags)
        assertEquals("example", intent.getStringExtra("id"))
        assertEquals("Example module", intent.getStringExtra("name"))
        assertTrue(intent.getBooleanExtra("from_webui_shortcut", false))
        assertNull(shadow.updateRequests.single().single().intents)
        assertEquals(listOf(shortcut.id), shadow.enabledIds)

        Shortcut.migrateModuleShortcuts(context)
        assertEquals(1, shadow.updateRequests.size)
    }

    @Test
    fun recoversActivityRemovalButKeepsDeliberateDeletionsAndRestoreFailuresDisabled() {
        val removedActivity = actionShortcut("removed")
        val deleted = actionShortcut("deleted")
        val restoreFailure = actionShortcut("restore")
        val alreadyUpdated = actionShortcut("updated", activity = launcher)
        listOf(removedActivity, deleted, restoreFailure, alreadyUpdated).forEach {
            manager.requestPinShortcut(it, null)
        }
        disable(removedActivity, ShortcutInfo.DISABLED_REASON_APP_CHANGED)
        disable(deleted, ShortcutInfo.DISABLED_REASON_BY_APP)
        disable(restoreFailure, ShortcutInfo.DISABLED_REASON_SIGNATURE_MISMATCH)
        disable(alreadyUpdated, ShortcutInfo.DISABLED_REASON_APP_CHANGED)

        Shortcut.migrateModuleShortcuts(context)

        assertTrue(removedActivity.isEnabled)
        assertTrue(alreadyUpdated.isEnabled)
        assertFalse(deleted.isEnabled)
        assertFalse(restoreFailure.isEnabled)
        assertEquals(previousLauncher, restoreFailure.activity)
        assertEquals(setOf(removedActivity.id, alreadyUpdated.id), shadow.enabledIds.toSet())
        assertEquals(launcher, removedActivity.activity)
    }

    @Test
    fun aThrottledMigrationKeepsBrokenActionsDisabledAndRetriesAtTheNextLaunch() {
        val shortcut = actionShortcut("retry")
        manager.requestPinShortcut(shortcut, null)
        disable(shortcut, ShortcutInfo.DISABLED_REASON_APP_CHANGED)
        shadow.rateLimited = true

        Shortcut.migrateModuleShortcuts(context)

        assertEquals(previousLauncher, shortcut.activity)
        assertFalse(shortcut.isEnabled)
        assertTrue(shadow.enabledIds.isEmpty())

        shadow.rateLimited = false
        Shortcut.migrateModuleShortcuts(context)

        assertEquals(launcher, shortcut.activity)
        assertTrue(shortcut.isEnabled)
        assertEquals(2, shadow.updateRequests.size)
    }

    @Test
    fun unavailableShortcutServiceDoesNotAbortStartupOrPreventALaterRetry() {
        val shortcut = actionShortcut("locked")
        manager.addDynamicShortcuts(listOf(shortcut))
        shadow.failQueries = true
        Shortcut.migrateModuleShortcuts(context)
        assertEquals(previousLauncher, shortcut.activity)

        shadow.failQueries = false
        shadow.failUpdates = true
        Shortcut.migrateModuleShortcuts(context)
        assertEquals(previousLauncher, shortcut.activity)

        shadow.failUpdates = false
        Shortcut.migrateModuleShortcuts(context)
        assertEquals(launcher, shortcut.activity)
    }

    private fun actionShortcut(
        name: String,
        activity: ComponentName = previousLauncher,
        idPrefix: String = "module_action_",
        leadingIntent: Intent? = null,
    ): ShortcutInfo {
        val id = "$idPrefix$name"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("test://module/$id"))
            .setComponent(activity)
            .putExtra("shortcut_type", "module_action")
            .putExtra("module_id", id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return ShortcutInfo.Builder(context, id)
            .setActivity(activity)
            .setShortLabel("Action $id")
            .setLongLabel("Long $id")
            .setCategories(setOf("module"))
            .setRank(3)
            .setExtras(PersistableBundle().apply { putString("metadata", "saved") })
            .setIcon(Icon.createWithBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)))
            .setIntents(listOfNotNull(leadingIntent, intent).toTypedArray())
            .build()
    }

    private fun disable(shortcut: ShortcutInfo, reason: Int) {
        manager.disableShortcuts(listOf(shortcut.id))
        val disabledFlag = ReflectionHelpers.getStaticField<Int>(ShortcutInfo::class.java, "FLAG_DISABLED")
        ReflectionHelpers.callInstanceMethod<Void>(
            shortcut, "addFlags", ClassParameter.from(Int::class.javaPrimitiveType!!, disabledFlag),
        )
        ReflectionHelpers.callInstanceMethod<Void>(
            shortcut, "setDisabledReason", ClassParameter.from(Int::class.javaPrimitiveType!!, reason),
        )
    }

    private fun iconOf(shortcut: ShortcutInfo): Icon? =
        ReflectionHelpers.callInstanceMethod(shortcut, "getIcon")
}

/**
 * Robolectric's default shadow replaces complete objects in updateShortcuts. Android merges
 * supplied fields instead. Invoke the real framework merge so tests also protect retained icons,
 * labels and disabled flags, then add controllable service failures and rate limiting.
 */
@Implements(ShortcutManager::class)
class MigrationShortcutManager : ShadowShortcutManager() {
    var rateLimited = false
    var failQueries = false
    var failUpdates = false
    val updateRequests = mutableListOf<List<ShortcutInfo>>()
    val enabledIds = mutableListOf<String>()

    @Implementation
    public override fun getPinnedShortcuts(): List<ShortcutInfo> {
        check(!failQueries) { "User is locked" }
        return super.getPinnedShortcuts()
    }

    @Implementation
    public override fun updateShortcuts(shortcutInfoList: List<ShortcutInfo>): Boolean {
        check(!failUpdates) { "Shortcut service unavailable" }
        updateRequests += shortcutInfoList
        if (rateLimited) return false
        val existing = (super.getPinnedShortcuts() + super.getDynamicShortcuts()).associateBy { it.id }
        for (update in shortcutInfoList) {
            val target = existing[update.id] ?: continue
            ReflectionHelpers.callInstanceMethod<Void>(
                target, "copyNonNullFieldsFrom", ClassParameter.from(ShortcutInfo::class.java, update),
            )
        }
        return true
    }

    @Implementation
    public override fun enableShortcuts(shortcutIds: List<String>) {
        enabledIds += shortcutIds
        super.enableShortcuts(shortcutIds)
        val disabledFlag = ReflectionHelpers.getStaticField<Int>(ShortcutInfo::class.java, "FLAG_DISABLED")
        for (shortcut in super.getPinnedShortcuts().filter { it.id in shortcutIds }) {
            ReflectionHelpers.callInstanceMethod<Void>(
                shortcut, "clearFlags", ClassParameter.from(Int::class.javaPrimitiveType!!, disabledFlag),
            )
            ReflectionHelpers.callInstanceMethod<Void>(
                shortcut, "setDisabledReason", ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            )
        }
    }
}
