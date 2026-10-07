package com.xayah.databackup.data.cloud

import android.os.ParcelFileDescriptor
import com.xayah.databackup.rootservice.RemoteRootService
import java.util.UUID

class DirectCloudTransferRepository(
    private val mJournal: CloudTransferJournalRepository,
    private val mGoogleDrive: GoogleDriveDirectUploadClient,
    private val mOneDrive: OneDriveDirectUploadClient,
) {
    suspend fun startGoogleDriveUpload(
        accessToken: String,
        localPath: String,
        parentId: String?,
        fileName: String,
        transferId: String = UUID.randomUUID().toString(),
    ): String {
        val totalBytes = fileSize(localPath)
        val session = mGoogleDrive.createSession(
            accessToken = accessToken,
            fileName = fileName,
            totalBytes = totalBytes,
            parentId = parentId,
        )
        mJournal.start(
            id = transferId,
            provider = CloudProvider.GOOGLE_DRIVE,
            localPath = localPath,
            remotePath = listOfNotNull(parentId, fileName).joinToString("/"),
            totalBytes = totalBytes,
            session = session,
        )
        resume(transferId)
        return transferId
    }

    suspend fun startOneDriveUpload(
        accessToken: String,
        localPath: String,
        remoteDirectory: String,
        fileName: String,
        transferId: String = UUID.randomUUID().toString(),
    ): String {
        val totalBytes = fileSize(localPath)
        val session = mOneDrive.createSession(
            accessToken = accessToken,
            remoteDirectory = remoteDirectory,
            fileName = fileName,
        )
        mJournal.start(
            id = transferId,
            provider = CloudProvider.ONEDRIVE,
            localPath = localPath,
            remotePath = remoteDirectory.trimEnd('/') + "/" + fileName,
            totalBytes = totalBytes,
            session = session,
        )
        resume(transferId)
        return transferId
    }

    suspend fun resume(transferId: String) {
        val transfer = requireNotNull(mJournal.load(transferId)) { "Transfer journal not found: $transferId" }
        val provider = CloudProvider.valueOf(transfer.journal.provider)
        val remoteProgress = when (provider) {
            CloudProvider.GOOGLE_DRIVE -> mGoogleDrive.queryProgress(
                session = transfer.session,
                totalBytes = transfer.journal.totalBytes,
            )
            CloudProvider.ONEDRIVE -> mOneDrive.queryProgress(transfer.session)
        }
        mJournal.updateProgress(transferId, remoteProgress, transfer.session)
        if (remoteProgress.completed) return
        check(remoteProgress.nextOffset in 0 until transfer.journal.totalBytes) {
            "Cloud provider returned an invalid resume offset."
        }

        val pfd = requireNotNull(RemoteRootService.openReadOnly(transfer.journal.localPath)) {
            "Root service could not open upload source."
        }
        check(pfd.statSize == transfer.journal.totalBytes) {
            pfd.close()
            "Upload source changed size after the transfer was created."
        }
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            var offset = remoteProgress.nextOffset
            stream.channel.position(offset)
            while (offset < transfer.journal.totalBytes) {
                val remaining = transfer.journal.totalBytes - offset
                val chunkSize = when (provider) {
                    CloudProvider.GOOGLE_DRIVE -> GoogleDriveDirectUploadClient.DEFAULT_CHUNK_SIZE
                    CloudProvider.ONEDRIVE -> OneDriveDirectUploadClient.DEFAULT_CHUNK_SIZE
                }
                val targetSize = minOf(remaining, chunkSize.toLong()).toInt()
                val bytes = readChunk(stream, targetSize)
                check(bytes.isNotEmpty()) { "Upload source ended before declared size." }

                val progress = when (provider) {
                    CloudProvider.GOOGLE_DRIVE -> mGoogleDrive.uploadChunk(
                        session = transfer.session,
                        offset = offset,
                        totalBytes = transfer.journal.totalBytes,
                        bytes = bytes,
                    )
                    CloudProvider.ONEDRIVE -> mOneDrive.uploadChunk(
                        session = transfer.session,
                        offset = offset,
                        totalBytes = transfer.journal.totalBytes,
                        bytes = bytes,
                    )
                }
                mJournal.updateProgress(transferId, progress, transfer.session)
                if (progress.completed) return

                check(progress.nextOffset > offset && progress.nextOffset < transfer.journal.totalBytes) {
                    "Cloud provider did not return a valid forward upload offset."
                }
                offset = progress.nextOffset
                stream.channel.position(offset)
            }
        }
    }

    suspend fun pendingTransfers() = mJournal.loadPending()

    suspend fun cancel(transferId: String) {
        mJournal.complete(transferId)
    }

    private suspend fun fileSize(path: String): Long {
        val pfd = requireNotNull(RemoteRootService.openReadOnly(path)) {
            "Root service could not open upload source."
        }
        return pfd.use {
            val size = it.statSize
            check(size > 0) { "Upload source must be a non-empty regular file." }
            size
        }
    }

    private fun readChunk(stream: ParcelFileDescriptor.AutoCloseInputStream, targetSize: Int): ByteArray {
        val buffer = ByteArray(targetSize)
        var read = 0
        while (read < targetSize) {
            val count = stream.read(buffer, read, targetSize - read)
            if (count < 0) break
            read += count
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }
}
