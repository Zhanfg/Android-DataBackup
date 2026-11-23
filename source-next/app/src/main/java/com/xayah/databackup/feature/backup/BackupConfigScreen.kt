package com.xayah.databackup.feature.backup

import android.app.Activity
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.xayah.databackup.App
import com.xayah.databackup.R
import com.xayah.databackup.data.cloud.CloudProvider
import com.xayah.databackup.data.cloud.CloudTokenResult
import com.xayah.databackup.data.cloud.GoogleDriveAuthorizationClient
import com.xayah.databackup.data.cloud.GoogleDriveAuthorizationResult
import com.xayah.databackup.data.cloud.MicrosoftOneDriveAuthorizationClient
import com.xayah.databackup.entity.BackupBackend
import com.xayah.databackup.entity.BackupConfig
import com.xayah.databackup.entity.rustic.RusticSnapshot
import com.xayah.databackup.feature.BackupSetupRoute
import com.xayah.databackup.feature.RestoreRoute
import com.xayah.databackup.ui.component.DataBackupDialog
import com.xayah.databackup.ui.component.DialogActionButton
import com.xayah.databackup.ui.component.DialogDestructiveButton
import com.xayah.databackup.ui.component.DialogDismissButton
import com.xayah.databackup.ui.component.DialogIcon
import com.xayah.databackup.ui.component.Preference
import com.xayah.databackup.ui.component.PreferenceGroup
import com.xayah.databackup.ui.component.SectionHeader
import com.xayah.databackup.ui.component.rememberFadingEdgeState
import com.xayah.databackup.ui.component.surfaceTopAppBarColors
import com.xayah.databackup.ui.component.verticalFadingEdges
import com.xayah.databackup.util.KeyMicrosoftOneDriveClientId
import com.xayah.databackup.util.MicrosoftOneDriveClientId
import com.xayah.databackup.util.Navigator
import com.xayah.databackup.util.readString
import com.xayah.databackup.util.saveString
import com.xayah.databackup.util.TimeHelper
import com.xayah.databackup.util.navigateSafely
import com.xayah.databackup.util.popBackStackSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

internal const val HIDDEN_PASSWORD = "••••••••"
private val BackupConfigContainerShape = RoundedCornerShape(28.dp)

@Composable
fun BackupConfigScreen(
    navigator: Navigator,
    viewModel: BackupConfigViewModel,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backupConfig by viewModel.backupConfig.collectAsStateWithLifecycle(null)
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val deletingSnapshot by viewModel.deletingSnapshot.collectAsStateWithLifecycle()
    val snapshotDeleteFailed by viewModel.snapshotDeleteFailed.collectAsStateWithLifecycle()
    val cloudSync by viewModel.cloudSync.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val googleAuthorization = remember(context.applicationContext) {
        GoogleDriveAuthorizationClient(context.applicationContext)
    }
    var microsoftClientId by rememberSaveable { mutableStateOf("") }
    var showMicrosoftClientIdDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        microsoftClientId = App.application.readString(MicrosoftOneDriveClientId).first()
    }

    val googleAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            runCatching { googleAuthorization.finishResolution(data) }
                .onSuccess { authorized ->
                    viewModel.syncBackupToCloud(CloudProvider.GOOGLE_DRIVE, authorized.accessToken)
                }
                .onFailure { error ->
                    viewModel.reportCloudAuthError(CloudProvider.GOOGLE_DRIVE, error)
                }
        } else {
            viewModel.reportCloudAuthError(CloudProvider.GOOGLE_DRIVE, null)
        }
    }

    val startGoogleSync: () -> Unit = {
        coroutineScope.launch {
            runCatching { googleAuthorization.authorize() }
                .onSuccess { authorization ->
                    when (authorization) {
                        is GoogleDriveAuthorizationResult.Authorized ->
                            viewModel.syncBackupToCloud(CloudProvider.GOOGLE_DRIVE, authorization.accessToken)
                        is GoogleDriveAuthorizationResult.ResolutionRequired ->
                            googleAuthorizationLauncher.launch(
                                IntentSenderRequest.Builder(authorization.pendingIntent.intentSender).build()
                            )
                    }
                }
                .onFailure { error ->
                    viewModel.reportCloudAuthError(CloudProvider.GOOGLE_DRIVE, error)
                }
        }
    }

    val startOneDriveSync: (String) -> Unit = oneDriveSync@{ clientId ->
        val hostActivity = activity ?: run {
            viewModel.reportCloudAuthError(
                CloudProvider.ONEDRIVE,
                IllegalStateException("No foreground activity for Microsoft authorization."),
            )
            return@oneDriveSync
        }
        coroutineScope.launch {
            val cleanClientId = clientId.trim()
            if (cleanClientId.isBlank()) {
                viewModel.reportCloudAuthError(
                    CloudProvider.ONEDRIVE,
                    IllegalArgumentException("Microsoft client id is required."),
                )
                return@launch
            }
            App.application.saveString(KeyMicrosoftOneDriveClientId, cleanClientId)
            microsoftClientId = cleanClientId
            runCatching {
                MicrosoftOneDriveAuthorizationClient.create(context.applicationContext, cleanClientId)
                    .acquireToken(hostActivity)
            }.onSuccess { authorization ->
                when (authorization) {
                    is CloudTokenResult.Authorized ->
                        viewModel.syncBackupToCloud(CloudProvider.ONEDRIVE, authorization.accessToken)
                    CloudTokenResult.Cancelled ->
                        viewModel.reportCloudAuthError(CloudProvider.ONEDRIVE, null)
                }
            }.onFailure { error ->
                viewModel.reportCloudAuthError(CloudProvider.ONEDRIVE, error)
            }
        }
    }

    if (showMicrosoftClientIdDialog) {
        MicrosoftClientIdDialog(
            initialClientId = microsoftClientId,
            onDismissRequest = { showMicrosoftClientIdDialog = false },
            onConfirm = { clientId ->
                showMicrosoftClientIdDialog = false
                startOneDriveSync(clientId)
            },
        )
    }

    var selectedSnapshot by remember(backupConfig?.uuid, backupConfig?.path, backupConfig?.backupBackend) {
        mutableStateOf<RusticSnapshot?>(null)
    }
    selectedSnapshot?.let { snapshot ->
        DeleteSnapshotDialog(
            isDeleting = deletingSnapshot,
            hasError = snapshotDeleteFailed,
            onDismissRequest = { if (!deletingSnapshot) selectedSnapshot = null },
            onConfirm = {
                backupConfig?.let { config ->
                    viewModel.deleteSnapshot(config, snapshot.id) { selectedSnapshot = null }
                }
            },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(backupConfig?.uuid, backupConfig?.path, backupConfig?.backupBackend, lifecycleOwner) {
        backupConfig?.let { config ->
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refreshSnapshots(config)
            }
        }
    }
    var openEditNameDialog by remember { mutableStateOf(false) }
    var openDeleteDialog by remember { mutableStateOf(false) }

    if (backupConfig != null && openEditNameDialog) {
        EditNameDialog(
            name = backupConfig?.name ?: "",
            onDismissRequest = {
                openEditNameDialog = false
            },
            onConfirm = {
                viewModel.changeName(it)
                openEditNameDialog = false
            }
        )
    }

    if (openDeleteDialog) {
        DeleteDialog(
            onDismissRequest = {
                openDeleteDialog = false
            },
            onConfirm = {
                viewModel.deleteConfig {
                    withContext(Dispatchers.Main) {
                        openDeleteDialog = false
                        navigator.popBackStackSafely()
                    }
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    val name = remember(backupConfig?.name) { backupConfig?.displayName }
                    Text(
                        text = name ?: stringResource(R.string.backup),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStackSafely() }) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (backupConfig != null) {
                        val editNameDesc = stringResource(R.string.edit_name)
                        TooltipBox(
                            positionProvider =
                                TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                            tooltip = { PlainTooltip { Text(editNameDesc) } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = { openEditNameDialog = true }) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_square_pen),
                                    contentDescription = editNameDesc
                                )
                            }
                        }

                        val deleteDesc = stringResource(R.string.delete)
                        TooltipBox(
                            positionProvider =
                                TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                            tooltip = { PlainTooltip { Text(deleteDesc) } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = { openDeleteDialog = true }) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_trash),
                                    contentDescription = deleteDesc
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.surfaceTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier) {
            Spacer(modifier = Modifier.size(innerPadding.calculateTopPadding()))

            val listState = rememberLazyListState()
            val fadingEdgeState = rememberFadingEdgeState(listState, label = "backupConfig")
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .verticalFadingEdges(fadingEdgeState),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                backupConfig?.let { config ->
                    item(key = "config") {
                        BackupConfigContent(
                            backupConfig = config,
                            cloudSync = cloudSync,
                            onBackUpNow = {
                                viewModel.selectBackup {
                                    navigator.navigateSafely(BackupSetupRoute)
                                }
                            },
                            onSyncGoogleDrive = startGoogleSync,
                            onSyncOneDrive = { showMicrosoftClientIdDialog = true },
                            onDismissCloudStatus = viewModel::clearCloudSyncState,
                        )
                    }

                    if (config.backupBackend is BackupBackend.Rustic) {
                        backupSnapshotsItems(
                            state = snapshots,
                            isDeleting = deletingSnapshot,
                            onRestore = { navigator.navigateSafely(RestoreRoute(config.uuidString, it.id)) },
                        ) {
                            viewModel.clearSnapshotDeleteError()
                            selectedSnapshot = it
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.size(innerPadding.calculateBottomPadding()))
        }
    }
}

@Composable
private fun BackupConfigContent(
    backupConfig: BackupConfig,
    cloudSync: CloudSyncUiState,
    onBackUpNow: () -> Unit,
    onSyncGoogleDrive: () -> Unit,
    onSyncOneDrive: () -> Unit,
    onDismissCloudStatus: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackupMetadataCard(backupConfig)

        Button(
            modifier = Modifier.fillMaxWidth(),
            shape = BackupConfigContainerShape,
            onClick = onBackUpNow,
            enabled = !cloudSync.isRunning,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_archive),
                contentDescription = null,
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.back_up_now))
        }

        Text(
            text = stringResource(R.string.cloud_backup),
            style = MaterialTheme.typography.titleMedium,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            shape = BackupConfigContainerShape,
            enabled = !cloudSync.isRunning,
            onClick = onSyncGoogleDrive,
        ) {
            Icon(ImageVector.vectorResource(R.drawable.ic_cloud_upload), contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.sync_google_drive))
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            shape = BackupConfigContainerShape,
            enabled = !cloudSync.isRunning,
            onClick = onSyncOneDrive,
        ) {
            Icon(ImageVector.vectorResource(R.drawable.ic_cloud_upload), contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.sync_onedrive))
        }

        when {
            cloudSync.isRunning -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.cloud_sync_in_progress))
            }
            cloudSync.stats != null -> {
                Text(
                    stringResource(
                        R.string.cloud_sync_complete,
                        cloudSync.stats.uploadedFiles,
                        cloudSync.stats.directories,
                        cloudSync.stats.deletedRemoteItems,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(onClick = onDismissCloudStatus) {
                    Text(stringResource(R.string.dismiss))
                }
            }
            cloudSync.error != null -> {
                Text(
                    text = stringResource(R.string.cloud_sync_failed, cloudSync.error),
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onDismissCloudStatus) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    }
}

@Composable
private fun MicrosoftClientIdDialog(
    initialClientId: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var clientId by rememberSaveable(initialClientId) { mutableStateOf(initialClientId) }
    DataBackupDialog(
        title = stringResource(R.string.onedrive_setup),
        onDismissRequest = onDismissRequest,
        icon = { DialogIcon(imageVector = ImageVector.vectorResource(R.drawable.ic_cloud_upload)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.onedrive_client_id_desc))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = clientId,
                    onValueChange = { clientId = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.microsoft_client_id)) },
                )
                Text(
                    text = stringResource(
                        R.string.onedrive_redirect_uri,
                        MicrosoftOneDriveAuthorizationClient.REDIRECT_URI,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            DialogActionButton(
                text = stringResource(R.string.continue_label),
                enabled = clientId.isNotBlank(),
                onClick = { onConfirm(clientId) },
            )
        },
        dismissButton = {
            DialogDismissButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
            )
        },
    )
}

private fun LazyListScope.backupSnapshotsItems(
    state: BackupSnapshotsState,
    isDeleting: Boolean,
    onRestore: (RusticSnapshot) -> Unit,
    onDelete: (RusticSnapshot) -> Unit,
) {
    val snapshots = state.snapshots.orEmpty()
    if (snapshots.isEmpty()) return

    item(key = "snapshots_header") {
        Row(
            modifier = Modifier
                .animateItem()
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.snapshots),
            )
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (state.isLoading) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    itemsIndexed(snapshots, key = { _, snapshot -> snapshot.id }) { index, snapshot ->
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val timeZone = TimeZone.getDefault()
        val unknown = stringResource(R.string.unknown)
        val timestamp = remember(snapshot.createdAt, configuration, timeZone, unknown) {
            if (snapshot.createdAt > 0) {
                TimeHelper.formatTimestampInShort(snapshot.createdAt)
            } else {
                unknown
            }
        }
        val addedBytes = snapshot.summary?.dataAddedPacked
        val formattedSize = remember(addedBytes, context, configuration) {
            addedBytes?.let { Formatter.formatFileSize(context, it) }
        }
        val topRadius = if (index == 0) 28.dp else 0.dp
        val bottomRadius = if (index == snapshots.lastIndex) 28.dp else 0.dp
        Preference(
            modifier = Modifier
                .animateItem()
                .clip(
                    RoundedCornerShape(
                        topStart = topRadius,
                        topEnd = topRadius,
                        bottomStart = bottomRadius,
                        bottomEnd = bottomRadius,
                    )
                ),
            leadingContent = {
                Text(
                    modifier = Modifier.widthIn(min = 20.dp),
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            },
            slot = {
                val restoreDescription = stringResource(R.string.restore_snapshot)
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                    tooltip = { PlainTooltip { Text(restoreDescription) } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = { onRestore(snapshot) }, enabled = !isDeleting && !state.isLoading) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_archive_restore),
                            contentDescription = stringResource(R.string.restore_snapshot_description, timestamp, snapshot.id.take(8)),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val description = stringResource(R.string.delete_snapshot)
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                    tooltip = { PlainTooltip { Text(description) } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = { onDelete(snapshot) }, enabled = !isDeleting && !state.isLoading) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_trash),
                            contentDescription = stringResource(R.string.delete_snapshot_description, timestamp, snapshot.id.take(8)),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            title = timestamp,
            subtitle = if (formattedSize != null) {
                stringResource(
                    R.string.snapshot_summary,
                    snapshot.id.take(8),
                    formattedSize,
                )
            } else {
                snapshot.id.take(8)
            },
        )
    }
}

@Composable
private fun BackupMetadataCard(backupConfig: BackupConfig) {
    PreferenceGroup {
        Preference(
            icon = ImageVector.vectorResource(R.drawable.ic_clock_plus),
            title = stringResource(R.string.created_at),
            subtitle = backupConfig.displayCreatedAt,
        )
        Preference(
            icon = ImageVector.vectorResource(R.drawable.ic_clock_arrow_up),
            title = stringResource(R.string.updated_at),
            subtitle = backupConfig.displayUpdatedAt,
        )
        Preference(
            icon = ImageVector.vectorResource(R.drawable.ic_id_card),
            title = stringResource(R.string.id),
            subtitle = backupConfig.uuidString,
        )
        BackupBackendItems(backupConfig.backupBackend)
    }
}

@Composable
private fun BackupBackendItems(
    backupBackend: BackupBackend,
) {
    val rusticBackend = backupBackend as? BackupBackend.Rustic
    val isRustic = rusticBackend != null
    Preference(
        icon = ImageVector.vectorResource(
            if (isRustic) R.drawable.ic_database_backup else R.drawable.ic_archive
        ),
        title = stringResource(if (isRustic) R.string.rustic else R.string.archive),
        subtitle = stringResource(
            if (isRustic) R.string.rustic_backup_backend_desc else R.string.archive_backup_backend_desc
        ),
    )
    rusticBackend?.let {
        PasswordPreference(
            password = it.password,
        )
    }
}

@Composable
private fun PasswordPreference(
    password: String,
) {
    var showPassword by rememberSaveable(password) { mutableStateOf(false) }
    val togglePasswordDescription = stringResource(
        if (showPassword) R.string.hide_password else R.string.show_password
    )

    Preference(
        icon = ImageVector.vectorResource(R.drawable.ic_key_round),
        title = stringResource(R.string.password),
        subtitle = password.takeIf { showPassword } ?: HIDDEN_PASSWORD,
        slot = {
            IconButton(
                onClick = { showPassword = showPassword.not() },
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(
                        if (showPassword) R.drawable.ic_eye_off else R.drawable.ic_eye
                    ),
                    contentDescription = togglePasswordDescription,
                )
            }
        },
    )
}

@Composable
private fun EditNameDialog(
    name: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text = name))
    }
    var isError by rememberSaveable { mutableStateOf(name.isBlank()) }
    DataBackupDialog(
        title = stringResource(R.string.edit_name),
        onDismissRequest = onDismissRequest,
        icon = { DialogIcon(imageVector = ImageVector.vectorResource(R.drawable.ic_square_pen)) },
        content = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = {
                    isError = it.text.isBlank()
                    text = it
                },
                isError = isError,
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                label = { Text(text = stringResource(R.string.name)) },
                supportingText = if (isError) {
                    { Text(text = stringResource(R.string.required)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            DialogActionButton(
                text = stringResource(R.string.save),
                enabled = isError.not() && text.text.isNotBlank(),
                icon = ImageVector.vectorResource(R.drawable.ic_check),
                onClick = { onConfirm.invoke(text.text) },
            )
        },
        dismissButton = {
            DialogDismissButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
            )
        },
    )
}

@Composable
private fun DeleteDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    var isDeleting by remember { mutableStateOf(false) }
    DataBackupDialog(
        title = stringResource(R.string.delete),
        onDismissRequest = { if (!isDeleting) onDismissRequest() },
        icon = { DialogIcon(imageVector = ImageVector.vectorResource(R.drawable.ic_trash)) },
        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
        iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
        content = { Text(text = stringResource(R.string.confirm_delete)) },
        confirmButton = {
            DialogDestructiveButton(
                text = stringResource(R.string.delete),
                isLoading = isDeleting,
                enabled = isDeleting.not(),
                icon = ImageVector.vectorResource(R.drawable.ic_trash),
                onClick = {
                    isDeleting = true
                    onConfirm.invoke()
                },
            )
        },
        dismissButton = {
            DialogDismissButton(
                text = stringResource(R.string.cancel),
                enabled = isDeleting.not(),
                onClick = onDismissRequest,
            )
        },
    )
}

@Composable
private fun DeleteSnapshotDialog(
    isDeleting: Boolean,
    hasError: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    DataBackupDialog(
        title = stringResource(R.string.delete_snapshot),
        onDismissRequest = { if (!isDeleting) onDismissRequest() },
        icon = { DialogIcon(imageVector = ImageVector.vectorResource(R.drawable.ic_trash)) },
        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
        iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.confirm_delete_snapshot))
                if (hasError) {
                    Text(stringResource(R.string.delete_snapshot_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            DialogDestructiveButton(
                text = stringResource(R.string.delete),
                isLoading = isDeleting,
                enabled = !isDeleting,
                icon = ImageVector.vectorResource(R.drawable.ic_trash),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            DialogDismissButton(text = stringResource(R.string.cancel), enabled = !isDeleting, onClick = onDismissRequest)
        },
    )
}
