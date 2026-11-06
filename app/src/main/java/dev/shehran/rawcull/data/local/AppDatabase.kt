package dev.shehran.rawcull.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.shehran.rawcull.data.model.PhotoAsset

@Database(entities = [PhotoAsset::class], version = 4, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawPhotoDao(): RawPhotoDao

    companion object {
        /** Room is a disposable cache until v1.0 (XMP is the source of truth), so migrations stay destructive. */
        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "rawcull_catalog.db",
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
}
