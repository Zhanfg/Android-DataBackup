package com.xayah.databackup.data.cloud

import com.xayah.databackup.database.entity.CloudTransferJournal
import com.xayah.databackup.util.CredentialStore
import com.xayah.databackup.util.DatabaseHelper

data class ResumableCloudTransfer(
    val journal: CloudTransferJournal,
    val session: DirectUploadSession,
)

class CloudTransferJournalRepository {
    companion object {
        private const val SESSION_PREFIX = "cloud-upload-session."
    }

    private val mDao = DatabaseHelper.cloudTransferDao

    private fun sessionKey(id: String) = SESSION_PREFIX + id

    suspend fun start(
        id: String,
        provider: CloudProvider,
        localPath: String,
        remotePath: String,
        totalBytes: Long,
        session: DirectUploadSession,
    ): CloudTransferJournal {
        require(id.isNotBlank())
        require(localPath.isNotBlank())
        require(remotePath.isNotBlank())
        require(totalBytes > 0)
        CredentialStore.putSecret(sessionKey(id), session.uploadUrl)
        return CloudTransferJournal(
            id = id,
            provider = provider.name,
            localPath = localPath,
            remotePath = remotePath,
            totalBytes = totalBytes,
            sessionExpiresAt = session.expiresAt,
        ).also { mDao.upsert(it) }
    }

    suspend fun load(id: String): ResumableCloudTransfer? {
        val entry = mDao.get(id) ?: return null
        val uploadUrl = CredentialStore.getSecret(sessionKey(id)) ?: run {
            mDao.delete(id)
            return null
        }
        return ResumableCloudTransfer(entry, DirectUploadSession(uploadUrl, entry.sessionExpiresAt))
    }

    suspend fun loadPending(): List<CloudTransferJournal> = mDao.loadPending()

    suspend fun updateProgress(id: String, progress: DirectUploadProgress, session: DirectUploadSession) {
        if (progress.completed) {
            complete(id)
            return
        }
        CredentialStore.putSecret(sessionKey(id), session.uploadUrl)
        mDao.updateProgress(
            id = id,
            nextOffset = progress.nextOffset,
            sessionExpiresAt = progress.expiresAt ?: session.expiresAt,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun replaceSession(id: String, session: DirectUploadSession, nextOffset: Long) {
        require(nextOffset >= 0)
        CredentialStore.putSecret(sessionKey(id), session.uploadUrl)
        mDao.updateProgress(id, nextOffset, session.expiresAt, System.currentTimeMillis())
    }

    suspend fun complete(id: String) {
        mDao.delete(id)
        CredentialStore.removeSecret(sessionKey(id))
    }
}
