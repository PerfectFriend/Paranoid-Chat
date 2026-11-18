package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Contact::class, Message::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    fun securePurgeAndCheckpoint() {
        try {
            val writable = openHelper.writableDatabase
            writable.query("PRAGMA secure_delete = ON").use { }
            writable.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
        } catch (_: Exception) {
            // Safe fallback if invoked on closed/in-memory DB
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val securityCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                try {
                    db.query("PRAGMA secure_delete = ON").use { }
                } catch (_: Exception) {
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "simplex_secure_db"
                )
                    .addCallback(securityCallback)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
