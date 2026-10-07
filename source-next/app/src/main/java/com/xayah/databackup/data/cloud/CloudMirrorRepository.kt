package com.xayah.databackup.data.cloud

import android.os.ParcelFileDescriptor
import com.xayah.databackup.entity.BackupConfig
import com.xayah.databackup.rootservice.RemoteRootService

data class CloudMirrorStats(
    val uploadedFiles: Int = 0,
    val downloadedFiles: Int = 0,
    val directories: Int = 0,
    val deletedRemoteItems: Int = 0,
) {
    operator fun plus(other: CloudMirrorStats) = CloudMirrorStats(
        uploadedFiles = uploadedFiles + other.uploadedFiles,
        downloadedFiles = downloadedFiles + other.downloadedFiles,
        directories = directories + other.directories,
        deletedRemoteItems = deletedRemoteItems + other.deletedRemoteItems,
    )
}

class CloudMirrorRepository(
    private val mTransfers: DirectCloudTransferRepository,
    private val mGoogleDrive: GoogleDriveDirectUploadClient,
    private val mOneDrive: OneDriveDirectUploadClient,
) {
    companion object {
        const val CLOUD_ROOT = "AndroidDataBackup"
        private const val DOWNLOAD_CHUNK = 4 * 1024 * 1024
    }

    suspend fun uploadBackup(
        provider: CloudProvider,
        accessToken: String,
        backup: BackupConfig,
    ): CloudMirrorStats {
        require(accessToken.isNotBlank()) { "Missing cloud access token." }
        require(backup.path.isNotBlank()) { "Backup path is empty." }
        check(RemoteRootService.exists(backup.path)) { "Local backup does not exist." }

        return when (provider) {
            CloudProvider.GOOGLE_DRIVE -> {
                val appRoot = ensureGoogleFolder(accessToken, "root", CLOUD_ROOT)
                val backupRoot = ensureGoogleFolder(accessToken, appRoot.id, backup.uuidString)
                syncGoogleDirectory(accessToken, backup.path, backupRoot.id)
            }
            CloudProvider.ONEDRIVE -> {
                ensureOneDriveFolder(accessToken, "", CLOUD_ROOT)
                ensureOneDriveFolder(accessToken, CLOUD_ROOT, backup.uuidString)
                syncOneDriveDirectory(
                    accessToken = accessToken,
                    localDirectory = backup.path,
                    remoteDirectory = "$CLOUD_ROOT/${backup.uuidString}",
                )
            }
        }
    }

    suspend fun listRemoteBackupIds(provider: CloudProvider, accessToken: String): List<String> =
        when (provider) {
            CloudProvider.GOOGLE_DRIVE -> {
                val appRoot = mGoogleDrive.listChildren(accessToken, "root")
                    .singleOrNull { it.name == CLOUD_ROOT && it.isDirectory }
                    ?: return emptyList()
                mGoogleDrive.listChildren(accessToken, appRoot.id)
                    .filter(CloudRemoteItem::isDirectory)
                    .map(CloudRemoteItem::name)
                    .distinct()
                    .sorted()
            }
            CloudProvider.ONEDRIVE -> {
                val appRoot = mOneDrive.listChildren(accessToken)
                    .singleOrNull { it.name == CLOUD_ROOT && it.isDirectory }
                    ?: return emptyList()
                mOneDrive.listChildren(accessToken, CLOUD_ROOT)
                    .filter(CloudRemoteItem::isDirectory)
                    .map(CloudRemoteItem::name)
                    .distinct()
                    .sorted()
            }
        }

    suspend fun downloadBackup(
        provider: CloudProvider,
        accessToken: String,
        backupUuid: String,
        destinationPath: String,
    ): CloudMirrorStats {
        require(accessToken.isNotBlank())
        require(backupUuid.isNotBlank())
        require(destinationPath.isNotBlank())

        if (RemoteRootService.exists(destinationPath)) {
            check(RemoteRootService.deleteRecursively(destinationPath)) {
                "Failed to clear cloud restore destination."
            }
        }
        check(RemoteRootService.mkdirs(destinationPath)) {
            "Failed to create cloud restore destination."
        }

        return when (provider) {
            CloudProvider.GOOGLE_DRIVE -> {
                val appRoot = requireGoogleFolder(accessToken, "root", CLOUD_ROOT)
                val backupRoot = requireGoogleFolder(accessToken, appRoot.id, backupUuid)
                downloadGoogleDirectory(accessToken, backupRoot.id, destinationPath)
            }
            CloudProvider.ONEDRIVE -> {
                requireOneDriveFolder(accessToken, "", CLOUD_ROOT)
                requireOneDriveFolder(accessToken, CLOUD_ROOT, backupUuid)
                downloadOneDriveDirectory(
                    accessToken,
                    "$CLOUD_ROOT/$backupUuid",
                    destinationPath,
                )
            }
        }
    }

    private suspend fun syncGoogleDirectory(
        accessToken: String,
        localDirectory: String,
        remoteParentId: String,
    ): CloudMirrorStats {
        val localItems = RemoteRootService.listFilePaths(localDirectory, true, true)
        val remoteItems = mGoogleDrive.listChildren(accessToken, remoteParentId)
        val localNames = localItems.map { nameOf(it.path) }.toSet()
        var stats = CloudMirrorStats(directories = 1)

        for (local in localItems) {
            val name = nameOf(local.path)
            val sameName = remoteItems.filter { it.name == name }
            val compatible = sameName.firstOrNull { it.isDirectory == local.isDirectory }
            for (duplicate in sameName.filter { it.id != compatible?.id }) {
                mGoogleDrive.deleteItem(accessToken, duplicate.id)
                stats += CloudMirrorStats(deletedRemoteItems = 1)
            }

            if (local.isDirectory) {
                val folder = compatible ?: mGoogleDrive.createFolder(accessToken, name, remoteParentId)
                stats += syncGoogleDirectory(accessToken, local.path, folder.id)
            } else {
                mTransfers.startGoogleDriveUpload(
                    accessToken = accessToken,
                    localPath = local.path,
                    parentId = remoteParentId,
                    fileName = name,
                    existingFileId = compatible?.id,
                )
                stats += CloudMirrorStats(uploadedFiles = 1)
            }
        }

        for (stale in remoteItems.filter { it.name !in localNames }) {
            mGoogleDrive.deleteItem(accessToken, stale.id)
            stats += CloudMirrorStats(deletedRemoteItems = 1)
        }
        return stats
    }

    private suspend fun syncOneDriveDirectory(
        accessToken: String,
        localDirectory: String,
        remoteDirectory: String,
    ): CloudMirrorStats {
        val localItems = RemoteRootService.listFilePaths(localDirectory, true, true)
        val remoteItems = mOneDrive.listChildren(accessToken, remoteDirectory)
        val localNames = localItems.map { nameOf(it.path) }.toSet()
        var stats = CloudMirrorStats(directories = 1)

        for (local in localItems) {
            val name = nameOf(local.path)
            val sameName = remoteItems.filter { it.name == name }
            val compatible = sameName.firstOrNull { it.isDirectory == local.isDirectory }
            for (duplicate in sameName.filter { it.id != compatible?.id }) {
                mOneDrive.deleteItem(accessToken, duplicate.id)
                stats += CloudMirrorStats(deletedRemoteItems = 1)
            }

            if (local.isDirectory) {
                if (compatible == null) {
                    mOneDrive.createFolder(accessToken, remoteDirectory, name)
                }
                stats += syncOneDriveDirectory(
                    accessToken,
                    local.path,
                    remoteDirectory.trimEnd('/') + "/" + name,
                )
            } else {
                mTransfers.startOneDriveUpload(
                    accessToken = accessToken,
                    localPath = local.path,
                    remoteDirectory = remoteDirectory,
                    fileName = name,
                )
                stats += CloudMirrorStats(uploadedFiles = 1)
            }
        }

        for (stale in remoteItems.filter { it.name !in localNames }) {
            mOneDrive.deleteItem(accessToken, stale.id)
            stats += CloudMirrorStats(deletedRemoteItems = 1)
        }
        return stats
    }

    private suspend fun downloadGoogleDirectory(
        accessToken: String,
        remoteParentId: String,
        localDirectory: String,
    ): CloudMirrorStats {
        val remoteItems = mGoogleDrive.listChildren(accessToken, remoteParentId)
        requireUniqueNames(remoteItems)
        var stats = CloudMirrorStats(directories = 1)
        for (item in remoteItems) {
            val localPath = localDirectory.trimEnd('/') + "/" + item.name
            if (item.isDirectory) {
                check(RemoteRootService.mkdirs(localPath))
                stats += downloadGoogleDirectory(accessToken, item.id, localPath)
            } else {
                downloadFile(localPath, requireNotNull(item.size)) { offset, length ->
                    mGoogleDrive.downloadRange(accessToken, item.id, offset, length)
                }
                stats += CloudMirrorStats(downloadedFiles = 1)
            }
        }
        return stats
    }

    private suspend fun downloadOneDriveDirectory(
        accessToken: String,
        remoteDirectory: String,
        localDirectory: String,
    ): CloudMirrorStats {
        val remoteItems = mOneDrive.listChildren(accessToken, remoteDirectory)
        requireUniqueNames(remoteItems)
        var stats = CloudMirrorStats(directories = 1)
        for (item in remoteItems) {
            val localPath = localDirectory.trimEnd('/') + "/" + item.name
            if (item.isDirectory) {
                check(RemoteRootService.mkdirs(localPath))
                stats += downloadOneDriveDirectory(
                    accessToken,
                    remoteDirectory.trimEnd('/') + "/" + item.name,
                    localPath,
                )
            } else {
                downloadFile(localPath, requireNotNull(item.size)) { offset, length ->
                    mOneDrive.downloadRange(accessToken, item.id, offset, length)
                }
                stats += CloudMirrorStats(downloadedFiles = 1)
            }
        }
        return stats
    }

    private suspend fun downloadFile(
        localPath: String,
        size: Long,
        read: suspend (Long, Int) -> ByteArray,
    ) {
        require(size >= 0)
        val pfd = requireNotNull(RemoteRootService.openWriteTruncate(localPath))
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
            var offset = 0L
            while (offset < size) {
                val length = minOf(DOWNLOAD_CHUNK.toLong(), size - offset).toInt()
                val bytes = read(offset, length)
                check(bytes.isNotEmpty() && bytes.size <= length)
                output.write(bytes)
                offset += bytes.size
            }
            check(offset == size)
        }
    }

    private suspend fun ensureGoogleFolder(accessToken: String, parentId: String, name: String): CloudRemoteItem {
        val matching = mGoogleDrive.listChildren(accessToken, parentId).filter { it.name == name }
        val folder = matching.firstOrNull(CloudRemoteItem::isDirectory)
        matching.filter { it.id != folder?.id }.forEach { mGoogleDrive.deleteItem(accessToken, it.id) }
        return folder ?: mGoogleDrive.createFolder(accessToken, name, parentId)
    }

    private suspend fun requireGoogleFolder(accessToken: String, parentId: String, name: String): CloudRemoteItem =
        mGoogleDrive.listChildren(accessToken, parentId)
            .singleOrNull { it.name == name && it.isDirectory }
            ?: error("Google Drive backup folder is missing or ambiguous: $name")

    private suspend fun ensureOneDriveFolder(accessToken: String, parentDirectory: String, name: String): CloudRemoteItem {
        val matching = mOneDrive.listChildren(accessToken, parentDirectory).filter { it.name == name }
        val folder = matching.firstOrNull(CloudRemoteItem::isDirectory)
        matching.filter { it.id != folder?.id }.forEach { mOneDrive.deleteItem(accessToken, it.id) }
        return folder ?: mOneDrive.createFolder(accessToken, parentDirectory, name)
    }

    private suspend fun requireOneDriveFolder(accessToken: String, parentDirectory: String, name: String): CloudRemoteItem =
        mOneDrive.listChildren(accessToken, parentDirectory)
            .singleOrNull { it.name == name && it.isDirectory }
            ?: error("OneDrive backup folder is missing or ambiguous: $name")

    private fun requireUniqueNames(items: List<CloudRemoteItem>) {
        val duplicate = items.groupingBy(CloudRemoteItem::name).eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) { "Cloud backup contains duplicate name: ${duplicate?.key}" }
    }

    private fun nameOf(path: String): String =
        path.trimEnd('/').substringAfterLast('/').also { require(it.isNotBlank()) }
}
