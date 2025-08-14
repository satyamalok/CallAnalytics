package com.example.callanalytics.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.example.callanalytics.models.CallData
import com.example.callanalytics.models.FailedWebhook

@Database(
    entities = [CallData::class, FailedWebhook::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "call_analytics_database"
                )
                    .fallbackToDestructiveMigration() // ADD this line
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}