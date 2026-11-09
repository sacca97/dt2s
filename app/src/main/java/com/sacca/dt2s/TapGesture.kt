package com.sacca.dt2s

/**
 * Pure state machine for the double-tap lock and the triple-tap lockdown upgrade.
 * No Android dependencies, so it is covered by JVM unit tests (TapGestureTest).
 *
 * ```
 * Idle ──tap──▶ FirstTap ──tap 40..300 ms later──▶ Armed(taps=2) ──tap (lockdown on)──▶ Armed(taps=3)
 *   ▲               │ slow/too-fast tap: restarts as FirstTap        │ any other tap: Idle (cancelled)
 *   └───────────────┴──── cancel() / confirm() / rejected ◀─────────┘
 * ```
 *
 * The caller owns the clock and the confirm timer: on [Outcome.Armed] it (re)starts a timer
 * for `confirmDelayMs` and calls [confirm] when it fires. An interfering accessibility event
 * (click, scroll, window change) is checked with [isVetoedBy] and ends the gesture via [cancel].
 */
class TapGesture {

    sealed interface State {
        data object Idle : State
        data class FirstTap(val at: Long) : State
        data class Armed(val firstTapAt: Long, val armedAt: Long, val taps: Int) : State
    }

    sealed interface Outcome {
        /** First tap recorded (or a too slow/fast second tap restarted the gesture). */
        data object FirstTap : Outcome

        /** Lock is pending: (re)start the confirm timer. [taps] is 2, or 3 for lockdown. */
        data class Armed(val taps: Int, val confirmDelayMs: Long) : Outcome

        /** An extra tap ended a pending gesture; stop the confirm timer. */
        data object Cancelled : Outcome

        /** Second tap arrived but the screen/mode no longer allows locking. */
        data object Rejected : Outcome
    }

    var state: State = State.Idle
        private set

    /**
     * Feeds one tap at [time] (ms). [canArm] is asked only when a second tap would arm the
     * gesture; [lockdownEnabled] only when it matters (to size the window or take a third tap).
     */
    fun onTap(time: Long, canArm: () -> Boolean, lockdownEnabled: () -> Boolean): Outcome =
        when (val current = state) {
            State.Idle -> startFirstTap(time)

            is State.FirstTap -> when {
                time - current.at !in MIN_TAP_INTERVAL_MS..DOUBLE_TAP_TIMEOUT_MS ->
                    startFirstTap(time)

                !canArm() -> {
                    state = State.Idle
                    Outcome.Rejected
                }

                else -> {
                    state = State.Armed(current.at, time, taps = 2)
                    Outcome.Armed(
                        taps = 2,
                        confirmDelayMs = if (lockdownEnabled()) LOCKDOWN_WINDOW_MS
                        else CONFIRM_DELAY_MS
                    )
                }
            }

            is State.Armed ->
                if (current.taps < LOCKDOWN_TAP_COUNT &&
                    time - current.armedAt >= MIN_TAP_INTERVAL_MS &&
                    lockdownEnabled()
                ) {
                    state = current.copy(taps = LOCKDOWN_TAP_COUNT)
                    Outcome.Armed(LOCKDOWN_TAP_COUNT, CONFIRM_DELAY_MS)
                } else {
                    state = State.Idle
                    Outcome.Cancelled
                }
        }

    /** The confirm timer fired. Resets to idle; returns true if the gesture was a lockdown. */
    fun confirm(): Boolean {
        val taps = (state as? State.Armed)?.taps ?: 0
        state = State.Idle
        return taps >= LOCKDOWN_TAP_COUNT
    }

    /** Abandons the gesture. Returns true if there was one in progress. */
    fun cancel(): Boolean {
        val wasActive = state != State.Idle
        state = State.Idle
        return wasActive
    }

    /** Whether an accessibility event at [eventTime] (ms) belongs to the gesture and vetoes it. */
    fun isVetoedBy(eventTime: Long): Boolean = when (val current = state) {
        State.Idle -> false
        is State.FirstTap ->
            eventTime >= current.at && eventTime - current.at <= DOUBLE_TAP_TIMEOUT_MS

        is State.Armed -> eventTime >= current.firstTapAt
    }

    private fun startFirstTap(time: Long): Outcome {
        state = State.FirstTap(time)
        return Outcome.FirstTap
    }

    companion object {
        const val DOUBLE_TAP_TIMEOUT_MS = 300L
        const val MIN_TAP_INTERVAL_MS = 40L
        const val LOCKDOWN_TAP_COUNT = 3

        // Time to wait after the second tap for a click/scroll event that would veto the lock.
        // Click events fire on finger-up (~100 ms), so this is much shorter than a long press.
        const val CONFIRM_DELAY_MS = 150L

        // With triple-tap lockdown on, the double-tap lock waits this long for a third tap.
        const val LOCKDOWN_WINDOW_MS = 300L
    }
}
