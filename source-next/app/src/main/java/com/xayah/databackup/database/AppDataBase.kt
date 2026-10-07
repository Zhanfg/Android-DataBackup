package com.xayah.databackup.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.xayah.databackup.database.dao.AppDao
import com.xayah.databackup.database.dao.CallLogDao
import com.xayah.databackup.database.dao.ContactDao
import com.xayah.databackup.database.dao.MessageDao
import com.xayah.databackup.database.dao.NetworkDao
import com.xayah.databackup.database.entity.App
import com.xayah.databackup.database.entity.CallLog
import com.xayah.databackup.database.entity.Contact
import com.xayah.databackup.database.entity.Mms
import com.xayah.databackup.database.entity.Network
import com.xayah.databackup.database.entity.Sms

@Database(
    entities = [
        App::class,
        Network::class,
        Contact::class,
        CallLog::class,
        Sms::class,
        Mms::class,
    ],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = Migration(1, 2) { database ->
            database.execSQL(
                "ALTER TABLE apps ADD COLUMN info_isSystemTrusted INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    abstract fun appDao(): AppDao
    abstract fun networkDao(): NetworkDao
    abstract fun contactDao(): ContactDao
    abstract fun callLogDao(): CallLogDao
    abstract fun messageDao(): MessageDao
}
