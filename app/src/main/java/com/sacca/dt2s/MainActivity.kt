package com.sacca.dt2s

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.sacca.dt2s.ui.theme.Dt2sTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var accessibilityServiceEnabled by mutableStateOf(false)
    private var detectedLauncherPackage by mutableStateOf<String?>(null)
    private var launcherIdentificationFailed by mutableStateOf(false)
    private var isCheckingUpdates by mutableStateOf(false)
    private var updateCheckFailed by mutableStateOf(false)
    private var hasCheckedUpdates by mutableStateOf(false)
    private var updateAvailable by mutableStateOf(false)
    private var latestVersion by mutableStateOf<String?>(null)
    private var releaseUrl by mutableStateOf<String?>(null)
    private var apkUrl by mutableStateOf<String?>(null)
    private var isDownloadingUpdate by mutableStateOf(false)
    private var downloadProgress by mutableStateOf<Float?>(null)
    private var updateInstallFailed by mutableStateOf(false)
    private var installPermissionRequested by mutableStateOf(false)
    private var deviceAdminActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpdateCheckWorker.schedule(this)
        refreshUpdateStatus()
        setContent {
            Dt2sTheme {
                Dt2sScreen(
                    accessibilityServiceEnabled = accessibilityServiceEnabled,
                    detectedLauncherPackage = detectedLauncherPackage,
                    launcherIdentificationFailed = launcherIdentificationFailed,
                    isCheckingUpdates = isCheckingUpdates,
                    updateCheckFailed = updateCheckFailed,
                    hasCheckedUpdates = hasCheckedUpdates,
                    updateAvailable = updateAvailable,
                    latestVersion = latestVersion,
                    releaseUrl = releaseUrl,
                    apkUrl = apkUrl,
                    isDownloadingUpdate = isDownloadingUpdate,
                    downloadProgress = downloadProgress,
                    updateInstallFailed = updateInstallFailed,
                    installPermissionRequested = installPermissionRequested,
                    deviceAdminActive = deviceAdminActive,
                    onRequestDeviceAdmin = ::requestDeviceAdmin,
                    onRemoveDeviceAdmin = ::removeDeviceAdmin,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRefreshLauncher = ::refreshLauncher,
                    onCheckForUpdates = ::checkForUpdates,
                    onDownloadAndInstall = ::downloadAndInstall,
                    onOpenReleasePage = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (installPermissionRequested) {
            installPermissionRequested = false
            if (UpdateInstaller.canRequestInstalls(this)) downloadAndInstall()
        }
        refreshUpdateStatus()
        accessibilityServiceEnabled = isAccessibilityServiceEnabled()
        refreshDeviceAdmin()
        val preferences = getSharedPreferences(LockTapService.PREFS_NAME, MODE_PRIVATE)
        detectedLauncherPackage = preferences.getString(LockTapService.PREF_LAUNCHER_PACKAGE, null)
        val checkedAt = preferences.getLong(LockTapService.PREF_LAUNCHER_CHECKED_AT, 0L)
        launcherIdentificationFailed = detectedLauncherPackage == null && checkedAt > 0L
        if (detectedLauncherPackage == null && preferences.getBoolean(
                LockTapService.PREF_HOME_SCREEN,
                true
            )
        ) {
            preferences.edit { putBoolean(LockTapService.PREF_HOME_SCREEN, false) }
        }
        if (System.currentTimeMillis() - checkedAt >= LockTapService.LAUNCHER_REFRESH_INTERVAL_MS) {
            refreshLauncher()
        }
    }

    private fun devicePolicyManager() =
        getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun refreshDeviceAdmin() {
        deviceAdminActive =
            devicePolicyManager().isAdminActive(LockdownAdminReceiver.component(this))
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, LockdownAdminReceiver.component(this))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation)
            )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.device_admin_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun removeDeviceAdmin() {
        devicePolicyManager().removeActiveAdmin(LockdownAdminReceiver.component(this))
        refreshDeviceAdmin()
    }

    private fun refreshUpdateStatus() {
        val preferences = getSharedPreferences(UpdateChecker.PREFS_NAME, MODE_PRIVATE)
        hasCheckedUpdates = preferences.getLong(UpdateChecker.PREF_CHECKED_AT, 0L) > 0L
        updateAvailable = preferences.getBoolean(UpdateChecker.PREF_UPDATE_AVAILABLE, false)
        latestVersion = preferences.getString(UpdateChecker.PREF_LATEST_VERSION, null)
        releaseUrl = preferences.getString(UpdateChecker.PREF_RELEASE_URL, null)
        apkUrl = preferences.getString(UpdateChecker.PREF_APK_URL, null)
    }

    private fun checkForUpdates() {
        if (isCheckingUpdates) return
        isCheckingUpdates = true
        updateCheckFailed = false
        lifecycleScope.launch {
            try {
                val check = withContext(Dispatchers.IO) { UpdateChecker.check(this@MainActivity) }
                UpdateChecker.save(this@MainActivity, check)
                hasCheckedUpdates = true
                updateAvailable = check.updateAvailable
                latestVersion = check.latestVersion
                releaseUrl = check.releaseUrl
                apkUrl = check.apkUrl
            } catch (_: Exception) {
                updateCheckFailed = true
            } finally {
                isCheckingUpdates = false
            }
        }
    }

    private fun downloadAndInstall() {
        if (isDownloadingUpdate) return
        val url = apkUrl ?: return
        val version = latestVersion ?: return
        if (!UpdateInstaller.canRequestInstalls(this)) {
            installPermissionRequested = true
            startActivity(UpdateInstaller.manageUnknownSourcesIntent(this))
            return
        }
        isDownloadingUpdate = true
        updateInstallFailed = false
        downloadProgress = null
        lifecycleScope.launch {
            try {
                var lastPercent = -1
                val apk = withContext(Dispatchers.IO) {
                    UpdateInstaller.download(this@MainActivity, url, version) { downloaded, total ->
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            lifecycleScope.launch {
                                downloadProgress = if (percent >= 0) percent / 100f else null
                            }
                        }
                    }
                }
                UpdateInstaller.install(this@MainActivity, apk)
            } catch (_: Exception) {
                updateInstallFailed = true
            } finally {
                isDownloadingUpdate = false
                downloadProgress = null
            }
        }
    }

    private fun refreshLauncher() {
        val packageName = LockTapService.resolveLauncherPackage(this)
        val preferences = getSharedPreferences(LockTapService.PREFS_NAME, MODE_PRIVATE)
        preferences.edit {
            if (packageName == null) {
                remove(LockTapService.PREF_LAUNCHER_PACKAGE)
                putBoolean(LockTapService.PREF_HOME_SCREEN, false)
            } else {
                putString(LockTapService.PREF_LAUNCHER_PACKAGE, packageName)
            }
            putLong(LockTapService.PREF_LAUNCHER_CHECKED_AT, System.currentTimeMillis())
        }
        detectedLauncherPackage = packageName
        launcherIdentificationFailed = packageName == null
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val component = ComponentName(this, LockTapService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(':').any { value ->
            ComponentName.unflattenFromString(value)?.let { enabled ->
                enabled.packageName == component.packageName &&
                        enabled.className == component.className
            } == true
        }
    }
}

@Composable
private fun Dt2sScreen(
    accessibilityServiceEnabled: Boolean,
    detectedLauncherPackage: String?,
    launcherIdentificationFailed: Boolean,
    isCheckingUpdates: Boolean,
    updateCheckFailed: Boolean,
    hasCheckedUpdates: Boolean,
    updateAvailable: Boolean,
    latestVersion: String?,
    releaseUrl: String?,
    apkUrl: String?,
    isDownloadingUpdate: Boolean,
    downloadProgress: Float?,
    updateInstallFailed: Boolean,
    installPermissionRequested: Boolean,
    deviceAdminActive: Boolean,
    onRequestDeviceAdmin: () -> Unit,
    onRemoveDeviceAdmin: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshLauncher: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onOpenReleasePage: (String) -> Unit
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences(LockTapService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    var lockScreenEnabled by remember {
        mutableStateOf(preferences.getBoolean(LockTapService.PREF_LOCK_SCREEN, true))
    }
    var homeScreenEnabled by remember {
        mutableStateOf(preferences.getBoolean(LockTapService.PREF_HOME_SCREEN, true))
    }
    // Re-read when admin changes: LockdownAdminReceiver clears the pref on revoke.
    var lockdownTapsEnabled by remember(deviceAdminActive) {
        mutableStateOf(preferences.getBoolean(LockTapService.PREF_LOCKDOWN_TAPS, false))
    }
    LaunchedEffect(launcherIdentificationFailed) {
        if (launcherIdentificationFailed) homeScreenEnabled = false
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!accessibilityServiceEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.setup_needed_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.setup_needed_body),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.open_accessibility_settings))
                        }
                    }
                }
            }

            SectionCard(stringResource(R.string.gestures_section)) {
                ToggleRow(
                    label = stringResource(R.string.lock_screen_double_tap),
                    summary = stringResource(R.string.lock_screen_summary),
                    checked = lockScreenEnabled,
                    onCheckedChange = {
                        lockScreenEnabled = it
                        preferences.edit { putBoolean(LockTapService.PREF_LOCK_SCREEN, it) }
                    }
                )
                HorizontalDivider()
                ToggleRow(
                    label = stringResource(R.string.home_screen_double_tap),
                    summary = if (launcherIdentificationFailed) {
                        stringResource(R.string.launcher_detection_failed)
                    } else {
                        stringResource(
                            R.string.home_screen_summary,
                            launcherDisplayName(context, detectedLauncherPackage)
                        )
                    },
                    checked = homeScreenEnabled && detectedLauncherPackage != null,
                    enabled = detectedLauncherPackage != null,
                    onCheckedChange = {
                        homeScreenEnabled = it
                        preferences.edit { putBoolean(LockTapService.PREF_HOME_SCREEN, it) }
                    }
                )
                TextButton(onClick = onRefreshLauncher) {
                    Text(stringResource(R.string.refresh_launcher))
                }
                HorizontalDivider()
                ToggleRow(
                    label = stringResource(R.string.four_tap_lockdown),
                    summary = stringResource(
                        if (deviceAdminActive) R.string.four_tap_lockdown_hint
                        else R.string.four_tap_lockdown_needs_admin
                    ),
                    checked = lockdownTapsEnabled && deviceAdminActive,
                    enabled = deviceAdminActive,
                    onCheckedChange = {
                        lockdownTapsEnabled = it
                        preferences.edit { putBoolean(LockTapService.PREF_LOCKDOWN_TAPS, it) }
                    }
                )
            }

            SectionCard(stringResource(R.string.permissions_section)) {
                PermissionRow(
                    title = stringResource(R.string.accessibility_title),
                    description = stringResource(R.string.accessibility_disclosure),
                    granted = accessibilityServiceEnabled,
                    actionLabel = stringResource(R.string.open_accessibility_settings),
                    onAction = onOpenAccessibilitySettings
                )
                HorizontalDivider()
                PermissionRow(
                    title = stringResource(R.string.device_admin_title),
                    description = stringResource(R.string.device_admin_description),
                    granted = deviceAdminActive,
                    actionLabel = stringResource(
                        if (deviceAdminActive) R.string.remove_device_admin
                        else R.string.grant_device_admin
                    ),
                    onAction = if (deviceAdminActive) onRemoveDeviceAdmin else onRequestDeviceAdmin,
                    outlined = deviceAdminActive
                )
            }

            SectionCard(stringResource(R.string.updates_section)) {
                Text(
                    when {
                        isCheckingUpdates -> stringResource(R.string.checking_for_updates)
                        isDownloadingUpdate -> downloadProgress?.let {
                            stringResource(R.string.downloading_update_percent, (it * 100).toInt())
                        } ?: stringResource(R.string.downloading_update)
                        updateInstallFailed -> stringResource(R.string.update_download_failed)
                        installPermissionRequested -> stringResource(R.string.install_permission_hint)
                        updateCheckFailed -> stringResource(R.string.update_check_failed)
                        updateAvailable && latestVersion != null ->
                            stringResource(R.string.update_available, latestVersion)
                        hasCheckedUpdates -> stringResource(R.string.update_check_up_to_date)
                        else -> stringResource(R.string.update_check_initial)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (updateAvailable && apkUrl != null) {
                    Button(
                        onClick = onDownloadAndInstall,
                        enabled = !isDownloadingUpdate && !isCheckingUpdates,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (isDownloadingUpdate) R.string.downloading_update
                                else R.string.download_and_install
                            )
                        )
                    }
                }
                if (isDownloadingUpdate) {
                    val progress = downloadProgress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                ActionButton(
                    label = stringResource(
                        if (isCheckingUpdates) R.string.checking_for_updates
                        else R.string.check_for_updates
                    ),
                    onClick = onCheckForUpdates,
                    enabled = !isCheckingUpdates && !isDownloadingUpdate,
                    outlined = updateAvailable
                )
                if (updateAvailable && releaseUrl != null) {
                    TextButton(onClick = { onOpenReleasePage(releaseUrl) }) {
                        Text(stringResource(R.string.open_release_page))
                    }
                }
            }

            versionName?.let {
                Text(
                    stringResource(R.string.version_label, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    outlined: Boolean = false
) {
    val modifier = Modifier.fillMaxWidth()
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    outlined: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            StatusPill(granted)
        }
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ActionButton(actionLabel, onAction, outlined = outlined)
    }
}

@Composable
private fun StatusPill(granted: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (granted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (granted) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            stringResource(if (granted) R.string.status_on else R.string.status_off),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun launcherDisplayName(context: Context, packageName: String?): String {
    if (packageName == null) return context.getString(R.string.launcher_not_detected)

    return runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}

@Composable
private fun ToggleRow(
    label: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
