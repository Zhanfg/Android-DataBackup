package com.xayah.databackup.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.databackup.R
import com.xayah.databackup.feature.UpdatesRoute
import com.xayah.databackup.ui.component.AutoScreenOffSwitch
import com.xayah.databackup.ui.component.CustomSUFileDialog
import com.xayah.databackup.ui.component.Preference
import com.xayah.databackup.ui.component.PreferenceGroup
import com.xayah.databackup.ui.component.ResetBackupListSwitch
import com.xayah.databackup.ui.component.SwitchablePreference
import com.xayah.databackup.ui.component.surfaceTopAppBarColors
import com.xayah.databackup.util.AppThemeMode
import com.xayah.databackup.util.AppThemeModeSetting
import com.xayah.databackup.util.DynamicColor
import com.xayah.databackup.util.Navigator
import com.xayah.databackup.util.ProjectLinks
import com.xayah.databackup.util.readEnum
import com.xayah.databackup.util.saveEnum
import com.xayah.databackup.util.navigateSafely
import com.xayah.databackup.util.openUrl
import com.xayah.databackup.util.popBackStackSafely
import kotlinx.coroutines.launch

@Composable
private fun SettingsDetailScaffold(
    navigator: Navigator,
    title: String,
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStackSafely() }) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.surfaceTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState()),
        ) {
            content()
            Spacer(modifier = Modifier.size(innerPadding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
fun AppearanceSettingsScreen(navigator: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeMode by context.readEnum<AppThemeMode>(AppThemeModeSetting)
        .collectAsStateWithLifecycle(initialValue = AppThemeModeSetting.second)

    SettingsDetailScaffold(navigator, stringResource(R.string.appearance)) {
        PreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
            SwitchablePreference(
                icon = ImageVector.vectorResource(R.drawable.ic_palette),
                title = stringResource(R.string.monet),
                subtitle = stringResource(R.string.monet_desc),
                dataStorePair = DynamicColor,
            )
            AppThemeMode.entries.forEach { mode ->
                val title = stringResource(
                    when (mode) {
                        AppThemeMode.SYSTEM -> R.string.theme_auto
                        AppThemeMode.LIGHT -> R.string.theme_light
                        AppThemeMode.DARK -> R.string.theme_dark
                    }
                )
                val subtitle = stringResource(
                    when (mode) {
                        AppThemeMode.SYSTEM -> R.string.theme_auto_desc
                        AppThemeMode.LIGHT -> R.string.theme_light_desc
                        AppThemeMode.DARK -> R.string.theme_dark_desc
                    }
                )
                Preference(
                    icon = ImageVector.vectorResource(R.drawable.ic_palette),
                    title = title,
                    subtitle = subtitle,
                    slot = {
                        if (themeMode == mode) {
                            Icon(
                                modifier = Modifier.size(20.dp),
                                imageVector = ImageVector.vectorResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        scope.launch { context.saveEnum(AppThemeModeSetting.first, mode) }
                    },
                )
            }
        }
    }
}

@Composable
fun BackupSettingsScreen(navigator: Navigator) {
    SettingsDetailScaffold(navigator, stringResource(R.string.backup_settings)) {
        PreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
            AutoScreenOffSwitch()
            ResetBackupListSwitch()
        }
    }
}

@Composable
fun RestoreSettingsScreen(navigator: Navigator) {
    SettingsDetailScaffold(navigator, stringResource(R.string.restore_settings)) {
        PreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
            AutoScreenOffSwitch()
            Preference(
                icon = ImageVector.vectorResource(R.drawable.ic_archive_restore),
                title = stringResource(R.string.restore),
                subtitle = stringResource(R.string.restore_safety_desc),
            )
        }
    }
}

@Composable
fun AdvancedSettingsScreen(navigator: Navigator) {
    val context = LocalContext.current
    var openSuDialog = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (openSuDialog.value) {
        CustomSUFileDialog { openSuDialog.value = false }
    }

    SettingsDetailScaffold(navigator, stringResource(R.string.advanced)) {
        PreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
            Preference(
                icon = ImageVector.vectorResource(R.drawable.ic_wrench),
                title = stringResource(R.string.custom_su_file),
                subtitle = stringResource(R.string.restart_to_take_effect),
                onClick = { openSuDialog.value = true },
            )
            Preference(
                icon = ImageVector.vectorResource(R.drawable.ic_clock_arrow_up),
                title = stringResource(R.string.update),
                subtitle = ProjectLinks.RELEASES_URL,
                onClick = { navigator.navigateSafely(UpdatesRoute) },
            )
            Preference(
                icon = ImageVector.vectorResource(R.drawable.ic_code),
                title = stringResource(R.string.github),
                subtitle = ProjectLinks.REPOSITORY_URL,
                onClick = { context.openUrl(ProjectLinks.REPOSITORY_URL) },
            )
        }
    }
}
