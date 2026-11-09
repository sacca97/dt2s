package com.sacca.dt2s

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiStateTest {

    private val newer = ReleaseCheck("0.3.0", "https://example.com/r", "https://example.com/a.apk", true)
    private val same = newer.copy(updateAvailable = false)

    @Test
    fun startsIdleAndUnchecked() {
        val state = UpdateUiState()
        assertEquals(UpdatePhase.Idle, state.phase)
        assertEquals(UpdateNotice.None, state.notice)
        assertFalse(state.hasChecked)
        assertFalse(state.isBusy)
    }

    @Test
    fun successfulCheckStoresReleaseAndReturnsToIdle() {
        val state = UpdateUiState().startCheck().finishCheck(newer)
        assertEquals(UpdatePhase.Idle, state.phase)
        assertTrue(state.hasChecked)
        assertEquals(newer, state.availableRelease)
    }

    @Test
    fun upToDateReleaseIsNotOffered() {
        assertNull(UpdateUiState().finishCheck(same).availableRelease)
    }

    @Test
    fun checkingIsBusyAndFailedCheckKeepsPreviousRelease() {
        val checking = UpdateUiState().finishCheck(newer).startCheck()
        assertTrue(checking.isBusy)
        val failed = checking.failCheck()
        assertEquals(UpdateNotice.CheckFailed, failed.notice)
        assertEquals(newer, failed.release)
        assertFalse(failed.isBusy)
    }

    @Test
    fun startingANewPhaseClearsTheNotice() {
        val failed = UpdateUiState().failCheck()
        assertEquals(UpdateNotice.None, failed.startCheck().notice)
        assertEquals(UpdateNotice.None, failed.startDownload().notice)
    }

    @Test
    fun downloadProgressOnlyAppliesWhileDownloading() {
        val downloading = UpdateUiState().startDownload().downloadProgress(0.5f)
        assertEquals(UpdatePhase.Downloading(0.5f), downloading.phase)

        val finished = downloading.finishDownload()
        assertSame(finished, finished.downloadProgress(0.9f))
        assertEquals(UpdatePhase.Idle, finished.phase)
    }

    @Test
    fun failedInstallReturnsToIdleWithNotice() {
        val state = UpdateUiState().startDownload().failInstall()
        assertEquals(UpdatePhase.Idle, state.phase)
        assertEquals(UpdateNotice.InstallFailed, state.notice)
    }

    @Test
    fun installPermissionNoticeCanBeCleared() {
        val state = UpdateUiState().needInstallPermission()
        assertEquals(UpdateNotice.InstallPermissionNeeded, state.notice)
        assertEquals(UpdateNotice.None, state.clearNotice().notice)
    }
}
