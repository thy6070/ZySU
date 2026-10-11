package ui.screen.feature

/** Status 2 reports a missing hook, not an enabled Boolean feature. */
internal enum class UnicodePathGuardRuntime(val configurable: Boolean) {
    Loading(false), Disabled(true), Active(true), Unsupported(false), Unknown(false);

    companion object {
        fun fromKernel(featureSupported: Boolean, status: Long): UnicodePathGuardRuntime = when {
            !featureSupported || status == -1L -> Unsupported
            status == 0L -> Disabled
            status == 1L -> Active
            status == 2L -> Unsupported
            else -> Unknown
        }
    }
}
