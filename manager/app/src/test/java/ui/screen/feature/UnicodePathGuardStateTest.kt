package ui.screen.feature

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnicodePathGuardStateTest {
    @Test
    fun unavailableHookMustNotBeShownAsEnabled() {
        val state = UnicodePathGuardRuntime.fromKernel(featureSupported = true, status = 2)
        assertEquals(UnicodePathGuardRuntime.Unsupported, state)
        assertFalse(state.configurable)
    }

    @Test
    fun olderOrUnexpectedKernelStateCannotEnableTheSwitch() {
        assertEquals(UnicodePathGuardRuntime.Unsupported, UnicodePathGuardRuntime.fromKernel(false, 1))
        assertEquals(UnicodePathGuardRuntime.Unsupported, UnicodePathGuardRuntime.fromKernel(true, -1))
        for (status in listOf(-2L, 3L, Long.MAX_VALUE)) {
            val state = UnicodePathGuardRuntime.fromKernel(true, status)
            assertEquals(UnicodePathGuardRuntime.Unknown, state)
            assertFalse(state.configurable)
        }
        assertFalse(UnicodePathGuardRuntime.Loading.configurable)
    }

    @Test
    fun supportedEnabledAndDisabledStatesCanBeChanged() {
        assertEquals(UnicodePathGuardRuntime.Disabled, UnicodePathGuardRuntime.fromKernel(true, 0))
        assertEquals(UnicodePathGuardRuntime.Active, UnicodePathGuardRuntime.fromKernel(true, 1))
        assertTrue(UnicodePathGuardRuntime.Disabled.configurable)
        assertTrue(UnicodePathGuardRuntime.Active.configurable)
    }
}
