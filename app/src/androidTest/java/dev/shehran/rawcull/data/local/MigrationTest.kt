package dev.shehran.rawcull.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The catalog holds pending XMP writes, so an upgrade must carry them over untouched. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate4To5KeepsRowsAndSeedsTheStandardPreset() {
        helper.createDatabase(DB_NAME, 4).use { db ->
            val row = ContentValues().apply {
                put("id", "asset-1")
                put("rootUri", "content://tree/a")
                put("relativeDir", "DCIM/100MSDCF")
                put("parentDocUri", "content://tree/a/dir")
                put("fileName", "DSC00001.ARW")
                put("fileUri", "content://tree/a/file")
                put("format", "ARW")
                put("fileSizeBytes", 35_000_000L)
                put("fileLastModified", 5L)
                put("dateTaken", 7L)
                put("metadataLoaded", 1)
                put("cullStatus", "PICK")
                put("rating", 4)
                put("colorLabel", "GREEN")
                put("tags", "[\"Wedding\"]")
                put("width", 0)
                put("height", 0)
                put("orientation", 0)
                put("lastModified", 9L)
                put("pairMode", "RAW_ONLY")
                put("xmpSyncStatus", "PENDING")
                put("xmpPendingFields", 3)
            }
            db.insert("photo_assets", SQLiteDatabase.CONFLICT_ABORT, row)
        }

        helper.runMigrationsAndValidate(DB_NAME, 5, true).use { db ->
            db.query(
                "SELECT cullStatus, rating, tags, xmpSyncStatus, xmpPendingFields, lastImportJobId " +
                    "FROM photo_assets WHERE id = 'asset-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PICK", cursor.getString(0))
                assertEquals(4, cursor.getInt(1))
                assertEquals("[\"Wedding\"]", cursor.getString(2))
                assertEquals("PENDING", cursor.getString(3))
                assertEquals(3, cursor.getInt(4))
                assertTrue(cursor.isNull(5))
            }
            db.query("SELECT name, structureMode, datePattern FROM import_presets").use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("Standard", cursor.getString(0))
                assertEquals("DATE", cursor.getString(1))
                assertEquals("{year}/{year}-{month}-{day}", cursor.getString(2))
            }
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
