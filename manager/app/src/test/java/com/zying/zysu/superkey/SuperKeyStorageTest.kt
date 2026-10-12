package com.zying.zysu.superkey

import android.app.Application
import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SuperKeyStorageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("superkey", Context.MODE_PRIVATE)

    @Before
    fun resetPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun generatingAnotherImageDoesNotReplaceTheRunningKernelsCredential() {
        SuperKeyHelper.saveAuthenticatedSuperKey(context, "running-kernel-A")
        SuperKeyHelper.setAutoAuthenticationEnabled(context, true)

        SuperKeyHelper.savePatchSuperKey(context, "unbooted-image-B")

        assertEquals("running-kernel-A", SuperKeyHelper.getSavedSuperKey(context))
        assertEquals("unbooted-image-B", SuperKeyHelper.getSavedPatchSuperKey(context))
        assertTrue(SuperKeyHelper.shouldAutoAuthenticate(context))
    }

    @Test
    fun patchingBeforeAuthenticationDoesNotCreateAnAuthenticationCandidate() {
        SuperKeyHelper.savePatchSuperKey(context, "unbooted-image")
        SuperKeyHelper.setAutoAuthenticationEnabled(context, true)

        assertNull(SuperKeyHelper.getSavedSuperKey(context))
        assertFalse(SuperKeyHelper.hasSavedSuperKey(context))
        assertFalse(SuperKeyHelper.shouldAutoAuthenticate(context))
    }

    @Test
    fun signatureOnlyPatchDoesNotRestoreAnOldKeyIntoTheNextPatch() {
        SuperKeyHelper.saveAuthenticatedSuperKey(context, "running-kernel")
        SuperKeyHelper.savePatchSuperKey(context, "previous-patch")

        SuperKeyHelper.savePatchSuperKey(context, null)

        assertEquals("", SuperKeyHelper.getSavedPatchSuperKey(context))
        assertEquals("running-kernel", SuperKeyHelper.getSavedSuperKey(context))
    }

    @Test
    fun upgradeKeepsTheLegacyCandidateUntilSuccessfulAuthentication() {
        preferences.edit().putString("saved_superkey", "legacy-key").commit()

        assertEquals("legacy-key", SuperKeyHelper.getSavedSuperKey(context))
        assertEquals("legacy-key", SuperKeyHelper.getSavedPatchSuperKey(context))
        assertFalse(preferences.contains("authenticated_superkey"))
        SuperKeyHelper.savePatchSuperKey(context, "new-patch")
        assertEquals("legacy-key", SuperKeyHelper.getSavedSuperKey(context))

        SuperKeyHelper.saveAuthenticatedSuperKey(context, "confirmed-key")

        assertFalse(preferences.contains("saved_superkey"))
        assertEquals("confirmed-key", SuperKeyHelper.getSavedSuperKey(context))
        assertEquals("new-patch", SuperKeyHelper.getSavedPatchSuperKey(context))
    }

    @Test
    fun promotingALegacyKeyKeepsThePreviousPatchInput() {
        preferences.edit().putString("saved_superkey", "legacy-patch").commit()

        SuperKeyHelper.saveAuthenticatedSuperKey(context, "confirmed-key")

        assertEquals("confirmed-key", SuperKeyHelper.getSavedSuperKey(context))
        assertEquals("legacy-patch", SuperKeyHelper.getSavedPatchSuperKey(context))
    }

    @Test
    fun whitespaceKeysRemainExactAndStorageOptOutClearsBothRoles() {
        val key = " \t\u3000"
        SuperKeyHelper.saveAuthenticatedSuperKey(context, key)
        SuperKeyHelper.savePatchSuperKey(context, key)
        SuperKeyHelper.setAutoAuthenticationEnabled(context, true)

        assertEquals(key, SuperKeyHelper.getSavedSuperKey(context))
        assertEquals(key, SuperKeyHelper.getSavedPatchSuperKey(context))
        assertTrue(SuperKeyHelper.shouldAutoAuthenticate(context))

        SuperKeyHelper.setSkipStorage(context, true)
        SuperKeyHelper.saveAuthenticatedSuperKey(context, key)
        SuperKeyHelper.savePatchSuperKey(context, key)

        assertNull(SuperKeyHelper.getSavedSuperKey(context))
        assertNull(SuperKeyHelper.getSavedPatchSuperKey(context))
        assertFalse(SuperKeyHelper.shouldAutoAuthenticate(context))
    }

    @Test
    fun clearingKeysRemovesLegacyAndPatchInputsAsWellAsAuthentication() {
        preferences.edit().putString("saved_superkey", "legacy-key").commit()
        SuperKeyHelper.savePatchSuperKey(context, "patch-key")
        SuperKeyHelper.saveAuthenticatedSuperKey(context, "verified-key")
        SuperKeyHelper.setAutoAuthenticationEnabled(context, true)

        SuperKeyHelper.clearSavedSuperKey(context)

        assertNull(SuperKeyHelper.getSavedSuperKey(context))
        assertNull(SuperKeyHelper.getSavedPatchSuperKey(context))
        assertFalse(SuperKeyHelper.shouldAutoAuthenticate(context))
    }

    @Test
    fun malformedKeysCannotReplaceUsableSavedCredentials() {
        SuperKeyHelper.saveAuthenticatedSuperKey(context, "verified-key")
        SuperKeyHelper.savePatchSuperKey(context, "patch-key")

        for (invalid in listOf("contains\u0000nul", "a".repeat(65), "\uD800")) {
            SuperKeyHelper.saveAuthenticatedSuperKey(context, invalid)
            SuperKeyHelper.savePatchSuperKey(context, invalid)
        }
        SuperKeyHelper.saveAuthenticatedSuperKey(context, "")

        assertEquals("verified-key", SuperKeyHelper.getSavedSuperKey(context))
        assertEquals("patch-key", SuperKeyHelper.getSavedPatchSuperKey(context))
    }
}
