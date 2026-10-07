package com.xayah.databackup.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.xayah.databackup.database.entity.CloudTransferJournal

@Dao
interface CloudTransferDao {
    @Upsert
    suspend fun upsert(transfer: CloudTransferJournal)

    @Query("SELECT * FROM cloud_transfer_journal WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CloudTransferJournal?

    @Query("SELECT * FROM cloud_transfer_journal ORDER BY updatedAt DESC")
    suspend fun loadPending(): List<CloudTransferJournal>

    @Query("UPDATE cloud_transfer_journal SET nextOffset = :nextOffset, sessionExpiresAt = :sessionExpiresAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: String, nextOffset: Long, sessionExpiresAt: String?, updatedAt: Long)

    @Query("DELETE FROM cloud_transfer_journal WHERE id = :id")
    suspend fun delete(id: String)
}
