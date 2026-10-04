package com.sacca.dt2s

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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

class MainActivity : ComponentActivity() {

    private var accessibilityServiceEnabled by mutableStateOf(false)
    private var detectedLauncherPackage by mutableStateOf<String?>(null)
    private var launcherIdentificationFailed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Dt2sTheme {
                Dt2sScreen(
                    accessibilityServiceEnabled = accessibilityServiceEnabled,
                    detectedLauncherPackage = detectedLauncherPackage,
                    launcherIdentificationFailed = launcherIdentificationFailed,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRefreshLauncher = ::refreshLauncher
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityServiceEnabled = isAccessibilityServiceEnabled()
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
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshLauncher: () -> Unit
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
    LaunchedEffect(launcherIdentificationFailed) {
        if (launcherIdentificationFailed) homeScreenEnabled = false
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
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.accessibility_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(
                            if (accessibilityServiceEnabled) {
                                R.string.accessibility_enabled
                            } else {
                                R.string.accessibility_disabled
                            }
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (accessibilityServiceEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        stringResource(R.string.accessibility_disclosure),
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.double_tap_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ToggleRow(
                        label = stringResource(R.string.lock_screen_double_tap),
                        checked = lockScreenEnabled,
                        onCheckedChange = {
                            lockScreenEnabled = it
                            preferences.edit {
                                putBoolean(LockTapService.PREF_LOCK_SCREEN, it)
                            }
                        }
                    )
                    HorizontalDivider()
                    ToggleRow(
                        label = stringResource(R.string.home_screen_double_tap),
                        checked = homeScreenEnabled && detectedLauncherPackage != null,
                        enabled = detectedLauncherPackage != null,
                        onCheckedChange = {
                            homeScreenEnabled = it
                            preferences.edit {
                                putBoolean(LockTapService.PREF_HOME_SCREEN, it)
                            }
                        }
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.detected_launcher),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (launcherIdentificationFailed) {
                            stringResource(R.string.launcher_detection_failed)
                        } else {
                            launcherDisplayName(context, detectedLauncherPackage)
                        },
                        style = if (launcherIdentificationFailed) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        }
                    )
                    detectedLauncherPackage?.let { packageName ->
                        Text(
                            packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onRefreshLauncher,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.refresh_launcher))
                    }
                }
            }
        }
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
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
