package dev.shehran.rawcull.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.ImportedFile
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.StructureMode

@Database(
    entities = [
        PhotoAsset::class,
        LibraryLocation::class,
        ImportPreset::class,
        ImportedFile::class,
        ImportJob::class,
        ImportJobItem::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 4, to = 5, spec = AppDatabase.SeedStandardPreset::class),
        AutoMigration(from = 5, to = 6),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawPhotoDao(): RawPhotoDao
    abstract fun locationDao(): LocationDao
    abstract fun importPresetDao(): ImportPresetDao
    abstract fun importedFileDao(): ImportedFileDao
    abstract fun importJobDao(): ImportJobDao

    /** Upgrades get the preset here; fresh installs get it from [SeedOnCreate]. */
    class SeedStandardPreset : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) = seedStandardPreset(db)
    }

    object SeedOnCreate : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = seedStandardPreset(db)
    }

    companion object {
        /**
         * The catalog holds the duplicate index and pending XMP writes, which can't be rebuilt, so
         * every schema change needs a real migration from v5 on. Never add a destructive fallback.
         */
        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "rawcull_catalog.db",
        ).addCallback(SeedOnCreate).build()

        internal fun seedStandardPreset(db: SupportSQLiteDatabase) {
            val values = ContentValues().apply {
                put("name", ImportPreset.STANDARD_NAME)
                putNull("locationId")
                put("structureMode", StructureMode.DATE.name)
                put("datePattern", ImportPreset.DEFAULT_DATE_PATTERN)
                put("customPattern", ImportPreset.DEFAULT_CUSTOM_PATTERN)
                put("projectName", "")
            }
            db.insert("import_presets", SQLiteDatabase.CONFLICT_ABORT, values)
        }
    }
}
