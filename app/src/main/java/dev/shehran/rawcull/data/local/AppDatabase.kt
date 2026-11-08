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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.shehran.rawcull.data.model.CollectionItem
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobItem
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.ImportedFile
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.util.xmp.XmpField

@Database(
    entities = [
        PhotoAsset::class,
        LibraryLocation::class,
        ImportPreset::class,
        ImportedFile::class,
        ImportJob::class,
        ImportJobItem::class,
        PhotoCollection::class,
        CollectionItem::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 4, to = 5, spec = AppDatabase.SeedStandardPreset::class),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawPhotoDao(): RawPhotoDao
    abstract fun locationDao(): LocationDao
    abstract fun importPresetDao(): ImportPresetDao
    abstract fun importedFileDao(): ImportedFileDao
    abstract fun importJobDao(): ImportJobDao
    abstract fun collectionDao(): CollectionDao

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
        ).addCallback(SeedOnCreate).addMigrations(*MIGRATIONS).build()

        /**
         * Data only (R8 D5). Rows imported before R8, and rows enriched before v7, have no location
         * yet count as enriched, so phase B never read their GPS. Marking them unread lets the next
         * scan of their Location read the headers again. A row whose location the user set or
         * cleared (pending GPS) is left alone; re-enrichment keeps every other pending edit.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE photo_assets SET metadataLoaded = 0 " +
                        "WHERE gpsLatitude IS NULL AND gpsInFile = 0 AND (xmpPendingFields & ${XmpField.GPS.bit}) = 0 " +
                        "AND rootUri != '$DEMO_ROOT_URI'", // Synthetic rows, never scanned
                )
            }
        }

        /** The manual migrations, beside the AutoMigrations above; tests open the catalog with these. */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_7_8)

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
