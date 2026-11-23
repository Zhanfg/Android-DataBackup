package com.xayah.databackup.feature.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.databackup.R
import com.xayah.databackup.entity.BackupBackend
import com.xayah.databackup.ui.component.LocalFloatingNavigationBarBottomPadding
import com.xayah.databackup.ui.component.Preference
import com.xayah.databackup.ui.component.PreferenceGroup
import com.xayah.databackup.ui.component.SectionHeader
import com.xayah.databackup.ui.component.SwitchablePreference
import com.xayah.databackup.ui.component.surfaceTopAppBarColors
import com.xayah.databackup.util.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import org.koin.androidx.compose.koinViewModel

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val bottomPadding = LocalFloatingNavigationBarBottomPadding.current

    LaunchedEffect(context = Dispatchers.IO, null) {
        viewModel.initialize()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.schedule)) },
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
            SectionHeader(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                title = stringResource(R.string.automatic_backups),
                color = MaterialTheme.colorScheme.primary,
            )

            if (uiState.configs.isEmpty() && !uiState.loading) {
                PreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Preference(
                        icon = ImageVector.vectorResource(R.drawable.ic_calendar_check),
                        title = stringResource(R.string.no_backups),
                        subtitle = stringResource(R.string.schedule_requires_backup),
                    )
                }
            } else {
                uiState.configs.forEach { config ->
                    val backend = stringResource(
                        if (config.backupBackend is BackupBackend.Rustic) R.string.rustic else R.string.archive
                    )
                    SwitchablePreference(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        enabled = !uiState.loading,
                        checked = config.uuidString in uiState.scheduledUuids,
                        icon = ImageVector.vectorResource(R.drawable.ic_calendar_check),
                        title = config.displayName,
                        subtitle = stringResource(R.string.schedule_daily_summary, backend),
                        onCheckedChange = { enabled ->
                            viewModel.setScheduled(config.uuidString, enabled)
                        },
                    )
                }
            }

            PreferenceGroup(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Preference(
                    icon = ImageVector.vectorResource(R.drawable.ic_clock),
                    title = stringResource(R.string.schedule_execution),
                    subtitle = stringResource(R.string.schedule_execution_desc),
                )
            }

            Spacer(modifier = Modifier.size(innerPadding.calculateBottomPadding() + bottomPadding))
        }
    }
}
