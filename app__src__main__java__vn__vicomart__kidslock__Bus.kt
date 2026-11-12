package vn.vicomart.kidslock

import kotlinx.coroutines.flow.MutableStateFlow

/** Simple in-process channel from the service to the screens. */
object Bus {
    /** Latest AI test / calibration message (already localized). */
    val testResult = MutableStateFlow<String?>(null)

    /** Bumps whenever the service state changes so screens can refresh. */
    val serviceTick = MutableStateFlow(0L)

    fun tick() {
        serviceTick.value = System.currentTimeMillis()
    }
}
