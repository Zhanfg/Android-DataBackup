package com.xayah.databackup.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.xayah.databackup.R
import com.xayah.databackup.data.ArchiveBackupProcessRepository
import com.xayah.databackup.data.BackupConfigRepository
import com.xayah.databackup.data.RusticBackupProcessRepository
import com.xayah.databackup.entity.BackupBackend
import com.xayah.databackup.rootservice.RemoteRootService
import com.xayah.databackup.util.LogHelper
import com.xayah.databackup.util.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext

class ScheduledBackupWorker(
    private val appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val KEY_CONFIG_UUID = "config_uuid"
        private const val TAG = "ScheduledBackupWorker"
        private val sBackupMutex = Mutex()
    }

    private val notificationBuilder = NotificationHelper.getNotificationBuilder(appContext)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notificationBuilder
            .setContentTitle(appContext.getString(R.string.scheduled_backup_running))
            .setContentText(appContext.getString(R.string.backing_up))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.NOTIFICATION_ID_SCHEDULED_BACKUP_WORKER,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NotificationHelper.NOTIFICATION_ID_SCHEDULED_BACKUP_WORKER, notification)
        }
    }

    override suspend fun doWork(): Result = sBackupMutex.withLock {
        val configUuid = inputData.getString(KEY_CONFIG_UUID).orEmpty()
        if (configUuid.isBlank()) return@withLock Result.failure()

        setForeground(getForegroundInfo())

        if (!RemoteRootService.checkService()) {
            LogHelper.w(TAG, "doWork", "Root service unavailable for scheduled backup.")
            return@withLock Result.retry()
        }

        val koin = GlobalContext.get()
        val configRepo = koin.get<BackupConfigRepository>()
        val archiveRepo = koin.get<ArchiveBackupProcessRepository>()
        val rusticRepo = koin.get<RusticBackupProcessRepository>()

        return@withLock runCatching {
            configRepo.loadBackupConfigsFromLocal()
            val index = configRepo.configs.value.indexOfFirst { it.uuidString == configUuid }
            check(index >= 0) { "Scheduled backup config no longer exists." }
            configRepo.selectBackup(index)
            val config = configRepo.getCurrentConfig()

            when (config.backupBackend) {
                is BackupBackend.Archive -> {
                    archiveRepo.reset()
                    archiveRepo.onStart()
                }
                is BackupBackend.Rustic -> {
                    rusticRepo.start { }
                }
            }
            Result.success()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            LogHelper.e(TAG, "doWork", "Scheduled backup failed.", error)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
