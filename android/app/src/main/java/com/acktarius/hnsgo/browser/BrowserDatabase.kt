package com.acktarius.hnsgo.browser

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteEntity::class, HistoryEntity::class],
    version = 4,
    exportSchema = false
)
abstract class BrowserDatabase : RoomDatabase() {
    
    abstract fun browserDao(): BrowserDao
    
    companion object {
        @Volatile
        private var INSTANCE: BrowserDatabase? = null
        
        fun getInstance(context: Context): BrowserDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BrowserDatabase::class.java,
                    "browser_database"
                )
                .fallbackToDestructiveMigration() // For development - will recreate DB on schema change
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
