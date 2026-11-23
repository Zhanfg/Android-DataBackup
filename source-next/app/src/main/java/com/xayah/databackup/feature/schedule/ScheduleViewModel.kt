package com.xayah.databackup.feature.schedule

import androidx.lifecycle.viewModelScope
import com.xayah.databackup.data.BackupConfigRepository
import com.xayah.databackup.data.BackupScheduleRepository
import com.xayah.databackup.entity.BackupConfig
import com.xayah.databackup.util.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class ScheduleUiState(
    val loading: Boolean = true,
    val configs: List<BackupConfig> = emptyList(),
    val scheduledUuids: Set<String> = emptySet(),
)

class ScheduleViewModel(
    private val backupConfigRepository: BackupConfigRepository,
    private val scheduleRepository: BackupScheduleRepository,
) : BaseViewModel() {
    private val loading = MutableStateFlow(true)

    val uiState: StateFlow<ScheduleUiState> = combine(
        loading,
        backupConfigRepository.configs,
        scheduleRepository.scheduledUuids,
    ) { isLoading, configs, scheduled ->
        ScheduleUiState(isLoading, configs, scheduled)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScheduleUiState(),
    )

    fun initialize() {
        withLock(Dispatchers.IO) {
            loading.value = true
            backupConfigRepository.loadBackupConfigsFromLocal()
            scheduleRepository.reconcile(backupConfigRepository.configs.value.map { it.uuidString }.toSet())
            loading.value = false
        }
    }

    fun setScheduled(configUuid: String, enabled: Boolean) {
        withLock(Dispatchers.IO) {
            scheduleRepository.setEnabled(configUuid, enabled)
        }
    }
}
