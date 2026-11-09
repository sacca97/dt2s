package com.sacca.dt2s

import android.content.Context

/** What the update card is busy doing. At most one thing at a time. */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase

    /** [progress] is 0..1, or null while the total size is unknown. */
    data class Downloading(val progress: Float? = null) : UpdatePhase
}

/** The one message shown after a phase ends. Starting any new phase clears it. */
enum class UpdateNotice { None, CheckFailed, InstallFailed, InstallPermissionNeeded }

/**
 * Whole state of the update card, with pure transitions (see UpdateUiStateTest).
 *
 * ```
 * Idle ──startCheck──▶ Checking ──finishCheck/failCheck──▶ Idle (+notice)
 * Idle ──startDownload──▶ Downloading ──finishDownload/failInstall──▶ Idle (+notice)
 * Idle ──needInstallPermission──▶ Idle + InstallPermissionNeeded
 * ```
 * [release] and [hasChecked] come from the last check (live or stored) and survive phases.
 */
data class UpdateUiState(
    val release: ReleaseCheck? = null,
    val hasChecked: Boolean = false,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val notice: UpdateNotice = UpdateNotice.None
) {
    val isBusy: Boolean get() = phase != UpdatePhase.Idle

    /** The newer release to offer, or null when up to date or unknown. */
    val availableRelease: ReleaseCheck? get() = release?.takeIf { it.updateAvailable }

    fun startCheck() = copy(phase = UpdatePhase.Checking, notice = UpdateNotice.None)

    fun finishCheck(check: ReleaseCheck) =
        copy(release = check, hasChecked = true, phase = UpdatePhase.Idle)

    fun failCheck() = copy(phase = UpdatePhase.Idle, notice = UpdateNotice.CheckFailed)

    fun startDownload() = copy(phase = UpdatePhase.Downloading(), notice = UpdateNotice.None)

    /** Ignored unless a download is running, so a late callback cannot revive the progress bar. */
    fun downloadProgress(progress: Float?) =
        if (phase is UpdatePhase.Downloading) copy(phase = UpdatePhase.Downloading(progress))
        else this

    fun finishDownload() = copy(phase = UpdatePhase.Idle)

    fun failInstall() = copy(phase = UpdatePhase.Idle, notice = UpdateNotice.InstallFailed)

    fun needInstallPermission() = copy(notice = UpdateNotice.InstallPermissionNeeded)

    fun clearNotice() = copy(notice = UpdateNotice.None)

    /** Adopts the last check saved by UpdateChecker (e.g. by the daily worker). */
    fun withStored(context: Context): UpdateUiState {
        val prefs = context.getSharedPreferences(UpdateChecker.PREFS_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(UpdateChecker.PREF_LATEST_VERSION, null)
        val url = prefs.getString(UpdateChecker.PREF_RELEASE_URL, null)
        return copy(
            hasChecked = prefs.getLong(UpdateChecker.PREF_CHECKED_AT, 0L) > 0L,
            release = if (version != null && url != null) {
                ReleaseCheck(
                    latestVersion = version,
                    releaseUrl = url,
                    apkUrl = prefs.getString(UpdateChecker.PREF_APK_URL, null),
                    updateAvailable = prefs.getBoolean(UpdateChecker.PREF_UPDATE_AVAILABLE, false)
                )
            } else null
        )
    }
}
