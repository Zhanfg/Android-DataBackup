package com.xayah.databackup.data

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.xayah.databackup.App
import com.xayah.databackup.util.ScheduledBackupUuids
import com.xayah.databackup.util.readString
import com.xayah.databackup.util.saveString
import com.xayah.databackup.workers.ScheduledBackupWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.concurrent.TimeUnit

class BackupScheduleRepository {
    companion object {
        private const val WORK_PREFIX = "scheduled_backup_"
        private const val BACKUP_HOUR = 3
    }

    val scheduledUuids: Flow<Set<String>> =
        App.application.readString(ScheduledBackupUuids).map(::decode)

    suspend fun setEnabled(configUuid: String, enabled: Boolean) {
        require(configUuid.isNotBlank()) { "Backup config UUID is blank." }
        val current = scheduledUuidsValue().toMutableSet()
        if (enabled) {
            schedule(configUuid)
            current += configUuid
        } else {
            WorkManager.getInstance(App.application).cancelUniqueWork(workName(configUuid))
            current -= configUuid
        }
        App.application.saveString(
            ScheduledBackupUuids.first,
            current.sorted().joinToString(","),
        )
    }

    suspend fun reconcile(validConfigUuids: Set<String>) {
        val current = scheduledUuidsValue()
        val stale = current - validConfigUuids
        if (stale.isEmpty()) return
        val workManager = WorkManager.getInstance(App.application)
        stale.forEach { workManager.cancelUniqueWork(workName(it)) }
        App.application.saveString(
            ScheduledBackupUuids.first,
            (current - stale).sorted().joinToString(","),
        )
    }

    private suspend fun scheduledUuidsValue(): Set<String> =
        scheduledUuids.firstValue()

    private fun schedule(configUuid: String) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(24, TimeUnit.HOURS)
            .setInputData(workDataOf(ScheduledBackupWorker.KEY_CONFIG_UUID to configUuid))
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK_PREFIX + configUuid)
            .build()
        WorkManager.getInstance(App.application).enqueueUniquePeriodicWork(
            workName(configUuid),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun initialDelayMillis(): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, BACKUP_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
    }

    private fun workName(configUuid: String) = WORK_PREFIX + configUuid

    private fun decode(raw: String): Set<String> =
        raw.split(',').map(String::trim).filter(String::isNotBlank).toSet()

    private suspend fun <T> Flow<T>.firstValue(): T = kotlinx.coroutines.flow.first(this)
}
