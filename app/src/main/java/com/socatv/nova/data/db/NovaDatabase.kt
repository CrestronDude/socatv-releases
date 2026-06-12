package com.socatv.nova.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.Series
import com.socatv.nova.data.model.VodStream
import com.socatv.nova.data.model.WatchHistory

@Database(
    entities = [Channel::class, VodStream::class, Series::class, WatchHistory::class],
    version = 2,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun vodDao(): VodDao
    abstract fun seriesDao(): SeriesDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: NovaDatabase? = null

        fun getInstance(context: Context): NovaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NovaDatabase::class.java,
                    "nova_db"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
