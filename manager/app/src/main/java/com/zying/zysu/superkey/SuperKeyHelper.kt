package com.zying.zysu.superkey

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.zying.zysu.boot.BootCompletedReceiver as BaseBootCompletedReceiver

object SuperKeyHelper {
    private const val TAG = "ZySUSuperKey"
    private const val PREFS_NAME = "superkey"
    // Older releases used this field for both patch inputs and authenticated keys.
    // Keep it as an unverified upgrade candidate until authentication succeeds.
    private const val LEGACY_SUPERKEY_KEY = "saved_superkey"
    private const val AUTHENTICATED_SUPERKEY_KEY = "authenticated_superkey"
    private const val PATCH_SUPERKEY_KEY = "patch_superkey"
    private const val SKIP_STORE_SUPERKEY_KEY = "skip_store_superkey"
    private const val AUTO_AUTHENTICATE_KEY = "auto_authenticate_superkey"

    fun getSavedSuperKey(context: Context): String? {
        val prefs = preferences(context)
        return prefs.getString(AUTHENTICATED_SUPERKEY_KEY, null)?.validAuthenticationKey()
            ?: prefs.getString(LEGACY_SUPERKEY_KEY, null)?.validAuthenticationKey()
    }

    fun hasSavedSuperKey(context: Context): Boolean = getSavedSuperKey(context) != null

    /** Previous patch input, including an intentionally empty signature-only input. */
    fun getSavedPatchSuperKey(context: Context): String? {
        return preferences(context).getString(PATCH_SUPERKEY_KEY, null)
            ?.takeIf { encodeSuperKey(it) != null }
            ?: getSavedSuperKey(context)
    }

    /** Call only after the kernel has accepted this key. */
    fun saveAuthenticatedSuperKey(context: Context, superKey: String) {
        if (shouldSkipStorage(context) || superKey.validAuthenticationKey() == null) {
            return
        }
        val prefs = preferences(context)
        val legacyPatchKey = prefs.getString(LEGACY_SUPERKEY_KEY, null)
            ?.takeIf { encodeSuperKey(it) != null }
        prefs.edit {
            if (!prefs.contains(PATCH_SUPERKEY_KEY) && legacyPatchKey != null) {
                putString(PATCH_SUPERKEY_KEY, legacyPatchKey)
            }
            putString(AUTHENTICATED_SUPERKEY_KEY, superKey)
            remove(LEGACY_SUPERKEY_KEY)
        }
        updateBootReceiver(context)
    }

    /** A patched image may never be booted, so its key is never an auth candidate. */
    fun savePatchSuperKey(context: Context, superKey: String?) {
        val patchKey = superKey.orEmpty()
        if (shouldSkipStorage(context) || encodeSuperKey(patchKey) == null) return
        preferences(context).edit { putString(PATCH_SUPERKEY_KEY, patchKey) }
    }

    fun clearSavedSuperKey(context: Context) {
        preferences(context).edit {
            remove(LEGACY_SUPERKEY_KEY)
            remove(AUTHENTICATED_SUPERKEY_KEY)
            remove(PATCH_SUPERKEY_KEY)
            putBoolean(AUTO_AUTHENTICATE_KEY, false)
        }
        updateBootReceiver(context)
    }

    fun shouldSkipStorage(context: Context): Boolean {
        return preferences(context).getBoolean(SKIP_STORE_SUPERKEY_KEY, false)
    }

    fun setSkipStorage(context: Context, skip: Boolean) {
        preferences(context).edit {
            putBoolean(SKIP_STORE_SUPERKEY_KEY, skip)
            if (skip) {
                remove(LEGACY_SUPERKEY_KEY)
                remove(AUTHENTICATED_SUPERKEY_KEY)
                remove(PATCH_SUPERKEY_KEY)
                putBoolean(AUTO_AUTHENTICATE_KEY, false)
            }
        }
        updateBootReceiver(context)
    }

    fun isAutoAuthenticationEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(AUTO_AUTHENTICATE_KEY, false)
    }

    fun setAutoAuthenticationEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean(AUTO_AUTHENTICATE_KEY, enabled) }
        updateBootReceiver(context)
    }

    fun shouldAutoAuthenticate(context: Context): Boolean {
        return isAutoAuthenticationEnabled(context) && hasSavedSuperKey(context)
    }

    fun launchAutoAuthentication(context: Context, trigger: String): Boolean {
        val appContext = context.applicationContext
        if (!shouldAutoAuthenticate(appContext)) {
            updateBootReceiver(appContext)
            return false
        }
        return runCatching {
            appContext.startService(
                Intent(appContext, SuperKeyAuthenticationService::class.java).apply {
                    action = SuperKeyAuthenticationService.ACTION_AUTHENTICATE_SAVED_SUPERKEY
                    putExtra(SuperKeyAuthenticationService.EXTRA_TRIGGER, trigger)
                },
            )
            true
        }.getOrElse {
            Log.e(TAG, "failed to start automatic SuperKey authentication", it)
            false
        }
    }

    private fun updateBootReceiver(context: Context) {
        BaseBootCompletedReceiver.setEnabled(
            context,
            BootCompletedReceiver::class.java,
            shouldAutoAuthenticate(context),
            TAG,
        )
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun String.validAuthenticationKey(): String? =
        takeIf { it.isNotEmpty() && encodeSuperKey(it) != null }
}
