package com.xayah.databackup.util

import androidx.room.Room
import com.xayah.databackup.App
import com.xayah.databackup.database.AppDatabase

object DatabaseHelper {
    private val mDatabase = Room.databaseBuilder(
        App.application,
        AppDatabase::class.java,
        "database-databackup"
    )
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build()

    val appDao = mDatabase.appDao()
    val networkDao = mDatabase.networkDao()
    val contactDao = mDatabase.contactDao()
    val cloudTransferDao = mDatabase.cloudTransferDao()
    val callLogDao = mDatabase.callLogDao()
    val messageDao = mDatabase.messageDao()
}
