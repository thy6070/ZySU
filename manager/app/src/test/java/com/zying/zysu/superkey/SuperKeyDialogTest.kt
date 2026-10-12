package com.zying.zysu.superkey

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.zying.zysu.R
import com.zying.zysu.ui.component.SuperKeyDialog
import com.zying.zysu.ui.component.SuperKeyDialogState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SuperKeyDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun whitespaceOnlyKeyCanReachTheAuthenticationCallbackUnchanged() {
        val submitted = showDialog()
        val key = "  \u3000"

        compose.onNode(hasSetTextAction()).performTextInput(key)
        compose.onNodeWithText(compose.activity.getString(R.string.superkey_authenticate))
            .assertIsEnabled()
            .performClick()

        compose.waitUntil { submitted.get() != null }
        assertEquals(key, submitted.get())
    }

    @Test
    fun oversizedUtf8KeyIsRejectedBeforeAuthentication() {
        val submitted = showDialog()

        compose.onNode(hasSetTextAction()).performTextInput("密".repeat(22))
        compose.onNodeWithText(compose.activity.getString(R.string.superkey_authenticate))
            .performClick()

        compose.onNodeWithText(compose.activity.getString(R.string.superkey_invalid))
            .assertIsDisplayed()
        assertNull(submitted.get())
    }

    private fun showDialog(): AtomicReference<String?> {
        val submitted = AtomicReference<String?>(null)
        val state = SuperKeyDialogState().apply { show() }
        compose.setContent {
            MaterialTheme {
                SuperKeyDialog(
                    state = state,
                    onAuthenticate = { key ->
                        submitted.set(key)
                        true
                    },
                )
            }
        }
        return submitted
    }
}
