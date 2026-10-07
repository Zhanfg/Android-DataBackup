package com.xayah.databackup.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_transfer_journal")
data class CloudTransferJournal(
    @PrimaryKey val id: String,
    val provider: String,
    val localPath: String,
    val remotePath: String,
    val totalBytes: Long,
    val nextOffset: Long = 0,
    val sessionExpiresAt: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
