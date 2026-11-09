package com.sacca.dt2s

import com.sacca.dt2s.TapGesture.Outcome
import com.sacca.dt2s.TapGesture.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapGestureTest {

    private val gesture = TapGesture()

    private fun tap(time: Long, canArm: Boolean = true, lockdown: Boolean = false) =
        gesture.onTap(time, canArm = { canArm }, lockdownEnabled = { lockdown })

    @Test
    fun firstTapStartsGesture() {
        assertEquals(Outcome.FirstTap, tap(1000))
        assertEquals(State.FirstTap(1000), gesture.state)
    }

    @Test
    fun doubleTapArmsWithNormalConfirmDelay() {
        tap(1000)
        assertEquals(Outcome.Armed(2, TapGesture.CONFIRM_DELAY_MS), tap(1200))
        assertEquals(State.Armed(1000, 1200, 2), gesture.state)
    }

    @Test
    fun doubleTapWaitsLongerWhenLockdownEnabled() {
        tap(1000)
        assertEquals(Outcome.Armed(2, TapGesture.LOCKDOWN_WINDOW_MS), tap(1200, lockdown = true))
    }

    @Test
    fun slowSecondTapRestartsGesture() {
        tap(1000)
        assertEquals(Outcome.FirstTap, tap(1000 + TapGesture.DOUBLE_TAP_TIMEOUT_MS + 1))
        assertEquals(State.FirstTap(1301), gesture.state)
    }

    @Test
    fun tooFastSecondTapRestartsGesture() {
        tap(1000)
        assertEquals(Outcome.FirstTap, tap(1000 + TapGesture.MIN_TAP_INTERVAL_MS - 1))
    }

    @Test
    fun intervalBoundsAreInclusive() {
        tap(1000)
        assertTrue(tap(1000 + TapGesture.MIN_TAP_INTERVAL_MS) is Outcome.Armed)
        gesture.cancel()
        tap(5000)
        assertTrue(tap(5000 + TapGesture.DOUBLE_TAP_TIMEOUT_MS) is Outcome.Armed)
    }

    @Test
    fun secondTapIsRejectedWhenModeNoLongerValid() {
        tap(1000)
        assertEquals(Outcome.Rejected, tap(1200, canArm = false))
        assertEquals(State.Idle, gesture.state)
    }

    @Test
    fun canArmIsOnlyAskedForASecondTap() {
        var asked = 0
        gesture.onTap(1000, canArm = { asked++; true }, lockdownEnabled = { false })
        assertEquals(0, asked)
        gesture.onTap(1200, canArm = { asked++; true }, lockdownEnabled = { false })
        assertEquals(1, asked)
    }

    @Test
    fun thirdTapUpgradesToLockdownWhenEnabled() {
        tap(1000, lockdown = true)
        tap(1150, lockdown = true)
        assertEquals(Outcome.Armed(3, TapGesture.CONFIRM_DELAY_MS), tap(1300, lockdown = true))
        assertTrue(gesture.confirm())
        assertEquals(State.Idle, gesture.state)
    }

    @Test
    fun thirdTapCancelsWhenLockdownDisabled() {
        tap(1000)
        tap(1100)
        assertEquals(Outcome.Cancelled, tap(1200))
        assertEquals(State.Idle, gesture.state)
    }

    @Test
    fun thirdTapTooSoonCancels() {
        tap(1000, lockdown = true)
        tap(1100, lockdown = true)
        assertEquals(Outcome.Cancelled, tap(1100 + TapGesture.MIN_TAP_INTERVAL_MS - 1, lockdown = true))
    }

    @Test
    fun fourthTapCancelsLockdown() {
        tap(1000, lockdown = true)
        tap(1100, lockdown = true)
        tap(1200, lockdown = true)
        assertEquals(Outcome.Cancelled, tap(1300, lockdown = true))
    }

    @Test
    fun confirmAfterDoubleTapIsNotLockdown() {
        tap(1000)
        tap(1100)
        assertFalse(gesture.confirm())
        assertEquals(State.Idle, gesture.state)
    }

    @Test
    fun confirmWhenIdleIsHarmless() {
        assertFalse(gesture.confirm())
        assertEquals(State.Idle, gesture.state)
    }

    @Test
    fun cancelReportsWhetherAGestureWasInProgress() {
        assertFalse(gesture.cancel())
        tap(1000)
        assertTrue(gesture.cancel())
        assertEquals(State.Idle, gesture.state)
    }

    @Test
    fun idleGestureIsNeverVetoed() {
        assertFalse(gesture.isVetoedBy(1000))
    }

    @Test
    fun eventsBeforeOrLongAfterFirstTapDoNotVeto() {
        tap(1000)
        assertFalse(gesture.isVetoedBy(999))
        assertTrue(gesture.isVetoedBy(1000))
        assertTrue(gesture.isVetoedBy(1000 + TapGesture.DOUBLE_TAP_TIMEOUT_MS))
        assertFalse(gesture.isVetoedBy(1000 + TapGesture.DOUBLE_TAP_TIMEOUT_MS + 1))
    }

    @Test
    fun armedGestureIsVetoedByAnyEventAfterTheFirstTap() {
        tap(1000)
        tap(1100)
        assertFalse(gesture.isVetoedBy(999))
        assertTrue(gesture.isVetoedBy(1050))
        assertTrue(gesture.isVetoedBy(5000))
    }
}
