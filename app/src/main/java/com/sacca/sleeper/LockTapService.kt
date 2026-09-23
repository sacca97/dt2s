package com.sacca.sleeper

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

@SuppressLint("AccessibilityPolicy")
class LockTapService : AccessibilityService() {

    enum class InteractionMode {
        OFF,
        LOCKSCREEN,
        LAUNCHER
    }

    companion object {
        private const val TAG = "LockTap"
        const val PREFS_NAME = "sleeper_settings"
        const val PREF_LOCK_SCREEN = "lock_screen_dt2s"
        const val PREF_HOME_SCREEN = "home_screen_dt2s"
        const val PREF_LAUNCHER_PACKAGE = "launcher_package"
        const val PREF_LAUNCHER_CHECKED_AT = "launcher_checked_at"
        const val LAUNCHER_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val MIN_TAP_INTERVAL_MS = 40L
        private const val LONG_PRESS_CANCEL_GRACE_MS = 200L
        private const val POST_UNLOCK_REFRESH_DELAY_MS = 350L

        fun resolveLauncherPackage(context: Context): String? {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val packageManager = context.packageManager
            val resolvedPackage = packageManager.resolveActivity(homeIntent, 0)
                ?.activityInfo
                ?.packageName

            if (isLauncherPackage(context, resolvedPackage)) return resolvedPackage

            return packageManager.queryIntentActivities(homeIntent, 0)
                .firstNotNullOfOrNull { info ->
                    info.activityInfo.packageName.takeIf {
                        isLauncherPackage(context, it)
                    }
                }
        }

        private fun isLauncherPackage(context: Context, packageName: String?): Boolean =
            !packageName.isNullOrBlank() &&
                    packageName != context.packageName &&
                    packageName != "android" &&
                    packageName != "com.android.settings" &&
                    !packageName.contains("resolver", ignoreCase = true)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var preferences: SharedPreferences

    private var mode = InteractionMode.OFF
    private var foregroundPackage: String? = null
    private var launcherPackage: String? = null
    private var watcher: View? = null
    private var lastTapTime = 0L
    private var candidateStartedAt = 0L
    private var receiverRegistered = false
    private var lockScreenEnabled = true
    private var homeScreenEnabled = true

    private val handler = Handler(Looper.getMainLooper())
    private val windowEventTypes =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
    private val activeEventTypes = windowEventTypes or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SELECTED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
    private val debugLogging by lazy {
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private val postUnlockRefreshRunnable = Runnable {
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) return@Runnable

        refreshForegroundPackage()
        updateMode(refreshForeground = false)
        logState("post-unlock")
    }

    private val pendingSleepRunnable = Runnable {
        candidateStartedAt = 0L

        if (mode == InteractionMode.LAUNCHER &&
            powerManager.isInteractive &&
            !keyguardManager.isKeyguardLocked
        ) {
            refreshForegroundPackage()
        }

        if (!isModeStillValid()) {
            updateMode(refreshForeground = false)
            return@Runnable
        }

        log { "SLEEP mode=$mode" }
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    private val preferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_LOCK_SCREEN || key == PREF_HOME_SCREEN) {
                if (key == PREF_LOCK_SCREEN) {
                    lockScreenEnabled = preferences.getBoolean(PREF_LOCK_SCREEN, true)
                } else {
                    homeScreenEnabled = preferences.getBoolean(PREF_HOME_SCREEN, true)
                }
                log {
                    "SETTING $key=${if (key == PREF_LOCK_SCREEN) lockScreenEnabled else homeScreenEnabled}"
                }
                updateMode()
                logState("settings")
            } else if (key == PREF_LAUNCHER_PACKAGE) {
                launcherPackage = preferences.getString(PREF_LAUNCHER_PACKAGE, null)
                log { "LAUNCHER package=$launcherPackage" }
                updateMode()
            }
        }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    log { "SCREEN_ON" }
                    refreshLauncherPackageIfNeeded()
                    updateMode()
                }

                Intent.ACTION_SCREEN_OFF -> {
                    log { "SCREEN_OFF" }
                    handler.removeCallbacks(postUnlockRefreshRunnable)
                    foregroundPackage = null
                    updateMode()
                }

                Intent.ACTION_USER_PRESENT -> {
                    log { "USER_PRESENT" }
                    refreshLauncherPackageIfNeeded(force = true)
                    handler.removeCallbacks(postUnlockRefreshRunnable)
                    foregroundPackage = null
                    updateMode()
                    if (homeScreenEnabled && mode != InteractionMode.LAUNCHER) {
                        handler.postDelayed(
                            postUnlockRefreshRunnable,
                            POST_UNLOCK_REFRESH_DELAY_MS
                        )
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lockScreenEnabled = preferences.getBoolean(PREF_LOCK_SCREEN, true)
        homeScreenEnabled = preferences.getBoolean(PREF_HOME_SCREEN, true)
        launcherPackage = preferences.getString(PREF_LAUNCHER_PACKAGE, null)
        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        refreshLauncherPackageIfNeeded()

        registerScreenStateReceiver()
        updateMode()
        updateAccessibilityEventTypes()
        log { "Accessibility service connected launcher=$launcherPackage" }
    }

    private fun registerScreenStateReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        registerReceiver(screenStateReceiver, filter)
        receiverRegistered = true
    }

    private fun calculateMode(): InteractionMode = when {
        !powerManager.isInteractive -> InteractionMode.OFF
        keyguardManager.isKeyguardLocked ->
            if (lockScreenEnabled) InteractionMode.LOCKSCREEN
            else InteractionMode.OFF
        launcherPackage != null &&
                foregroundPackage == launcherPackage &&
                homeScreenEnabled ->
            InteractionMode.LAUNCHER
        else -> InteractionMode.OFF
    }

    private fun updateMode(refreshForeground: Boolean = true) {
        if (refreshForeground && homeScreenEnabled &&
            powerManager.isInteractive && !keyguardManager.isKeyguardLocked
        ) {
            refreshForegroundPackage()
        }

        setMode(calculateMode())
    }

    private fun refreshLauncherPackageIfNeeded(force: Boolean = false) {
        val lastCheckedAt = preferences.getLong(PREF_LAUNCHER_CHECKED_AT, 0L)
        if (!force && System.currentTimeMillis() - lastCheckedAt < LAUNCHER_REFRESH_INTERVAL_MS) return

        val resolvedPackage = resolveLauncherPackage(this)
        if (resolvedPackage == null) return

        launcherPackage = resolvedPackage
        preferences.edit().apply {
            putString(PREF_LAUNCHER_PACKAGE, resolvedPackage)
            putLong(PREF_LAUNCHER_CHECKED_AT, System.currentTimeMillis())
        }.apply()
    }

    private fun refreshForegroundPackage() {
        val availableWindows = windows
        val activeApplication = availableWindows.firstOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused
        } ?: availableWindows.firstOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive
        }

        val packageName = activeApplication?.let { window ->
            packageName(window.root)
        } ?: packageName(rootInActiveWindow)

        foregroundPackage = packageName?.takeIf { it.isNotBlank() }
    }

    private fun packageName(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null

        return try {
            root.packageName?.toString()
        } finally {
            recycle(root)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    private fun setMode(newMode: InteractionMode) {
        if (mode != newMode) {
            log { "MODE $mode -> $newMode" }
            mode = newMode
            cancelGestureCandidate("mode-changed")
            updateAccessibilityEventTypes()
        }

        if (newMode == InteractionMode.OFF) disableWatcher()
        else enableWatcher()
    }

    private fun enableWatcher() {
        if (watcher != null) return

        watcher = object : View(this) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    handleTouch(event.eventTime)
                }
                return true
            }

        }

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
        }

        try {
            windowManager.addView(watcher, params)
        } catch (error: RuntimeException) {
            watcher = null
            log { "WATCHER failed=${error::class.simpleName}" }
            return
        }
        log { "WATCHER enabled mode=$mode" }
    }

    private fun disableWatcher() {
        cancelGestureCandidate("watcher-disabled")

        val view = watcher ?: return
        runCatching { windowManager.removeView(view) }
        watcher = null
        log { "WATCHER disabled" }
    }

    private fun handleTouch(time: Long) {
        if (mode == InteractionMode.OFF) return

        if (candidateStartedAt != 0L) {
            cancelGestureCandidate("touch-during-candidate")
            return
        }

        if (lastTapTime == 0L) {
            startFirstTap(time)
            return
        }

        val delta = time - lastTapTime
        log { "TOUCH #2 delta=$delta mode=$mode" }

        if (delta !in MIN_TAP_INTERVAL_MS..DOUBLE_TAP_TIMEOUT_MS) {
            startFirstTap(time)
            return
        }

        if (!isModeStillValid()) {
            lastTapTime = 0L
            updateMode()
            return
        }

        lastTapTime = 0L
        candidateStartedAt = time
        log { "CANDIDATE mode=$mode" }
        handler.postDelayed(
            pendingSleepRunnable,
            ViewConfiguration.getLongPressTimeout().toLong() + LONG_PRESS_CANCEL_GRACE_MS
        )
    }

    private fun startFirstTap(time: Long) {
        lastTapTime = time
        log { "TOUCH #1 mode=$mode" }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                handleWindowEvent(event, event.packageName?.toString())
            }

            // TYPE_WINDOW_CONTENT_CHANGED is intentionally disabled for now:
            // System UI emits it frequently, and it can interfere with taps.
            // Restore it if launcher/widget filtering needs the signal later.

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                cancelForEvent(event) { "event-${event.eventType}" }
            }

            else -> Unit
        }
    }

    private fun handleWindowEvent(
        event: AccessibilityEvent,
        packageName: String?
    ) {
        if (!isEventCurrent(event)) return

        if (homeScreenEnabled && powerManager.isInteractive &&
            !keyguardManager.isKeyguardLocked
        ) {
            refreshForegroundPackage()
            if (foregroundPackage == null && !packageName.isNullOrBlank()) {
                foregroundPackage = packageName
            }
        }

        updateMode(refreshForeground = false)
        cancelForEvent(event) { "window-event" }
    }

    private fun updateAccessibilityEventTypes() {
        val info = serviceInfo
        val wanted = if (mode == InteractionMode.OFF) windowEventTypes else activeEventTypes
        if (info.eventTypes == wanted) return

        info.eventTypes = wanted
        serviceInfo = info
    }

    private fun isEventCurrent(event: AccessibilityEvent) =
        candidateStartedAt == 0L || event.eventTime >= candidateStartedAt

    private fun cancelForEvent(event: AccessibilityEvent, reason: () -> String) {
        val afterCandidate = candidateStartedAt != 0L && event.eventTime >= candidateStartedAt
        val afterFirstTap = lastTapTime != 0L && event.eventTime >= lastTapTime

        if (afterCandidate || afterFirstTap) {
            cancelGestureCandidate(reason())
        }
    }

    private fun cancelGestureCandidate(reason: String) {
        if (candidateStartedAt != 0L || lastTapTime != 0L) {
            log { "CANCEL reason=$reason" }
        }
        lastTapTime = 0L
        candidateStartedAt = 0L
        handler.removeCallbacks(pendingSleepRunnable)
    }

    private fun isModeStillValid(): Boolean =
        mode != InteractionMode.OFF && mode == calculateMode()

    private inline fun log(message: () -> String) {
        if (debugLogging) {
            Log.d(TAG, message())
        }
    }

    private fun logState(reason: String) {
        log {
            "STATE reason=$reason mode=$mode " +
                    "interactive=${powerManager.isInteractive} " +
                    "keyguard=${keyguardManager.isKeyguardLocked} " +
                    "foreground=$foregroundPackage " +
                    "launcher=$launcherPackage " +
                    "lock=$lockScreenEnabled home=$homeScreenEnabled"
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(postUnlockRefreshRunnable)
        handler.removeCallbacks(pendingSleepRunnable)
        if (::preferences.isInitialized) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        }
        disableWatcher()

        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            receiverRegistered = false
        }

        super.onDestroy()
    }
}
