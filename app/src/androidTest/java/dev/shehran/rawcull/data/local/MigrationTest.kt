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

    /** Every round must install over the last one, so everything a tester has built up has to survive. */
    @Test
    fun migrate5To6KeepsTheLibraryAndStartsActiveTimeAtZero() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.insert(
                "library_locations",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", 7L)
                    put("treeUri", "content://tree/library")
                    put("displayName", "Archive")
                    put("addedAt", 1L)
                    put("lastOpenedAt", 2L)
                },
            )
            db.insert(
                "import_presets",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("name", "Weddings")
                    put("locationId", 7L)
                    put("structureMode", "CUSTOM")
                    put("datePattern", "{year}/{year}-{month}-{day}")
                    put("customPattern", "{year}/{project}")
                    put("projectName", "Smith")
                },
            )
            db.insert(
                "import_jobs",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", 3L)
                    put("createdAt", 10L)
                    put("startedAt", 11L)
                    put("finishedAt", 5011L)
                    put("status", "COMPLETED")
                    put("sourceTreeUri", "content://tree/card")
                    put("sourceLabel", "SD card")
                    put("locationId", 7L)
                    put("template", "{year}")
                    put("verifyMode", "FULL")
                    put("deleteSourceAfter", 0)
                    put("totalFiles", 1)
                    put("totalBytes", 100L)
                    put("doneFiles", 1)
                    put("doneBytes", 100L)
                    put("skippedFiles", 0)
                    put("failedFiles", 0)
                },
            )
            db.insert(
                "import_job_items",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("jobId", 3L)
                    put("groupKey", "DSC00001")
                    put("sourceUri", "content://tree/card/file")
                    put("sourceName", "DSC00001.ARW")
                    put("sizeBytes", 100L)
                    put("targetRelativeDir", "2026")
                    put("targetName", "DSC00001.ARW")
                    put("state", "DONE")
                    put("sourceDeleted", 0)
                    put("role", "PRIMARY")
                    put("captureDate", 5L)
                },
            )
            db.insert(
                "imported_files",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("headHash", "abc123")
                    put("sizeBytes", 100L)
                    put("originalName", "DSC00001.ARW")
                    put("targetUri", "content://tree/library/file")
                    put("importedAt", 20L)
                    put("jobId", 3L)
                },
            )
            db.insert(
                "photo_assets",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "asset-1")
                    put("rootUri", "content://tree/library")
                    put("relativeDir", "2026")
                    put("parentDocUri", "content://tree/library/dir")
                    put("fileName", "DSC00001.ARW")
                    put("fileUri", "content://tree/library/file")
                    put("format", "ARW")
                    put("fileSizeBytes", 100L)
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
                    put("lastImportJobId", 3L)
                },
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 6, true).use { db ->
            db.query("SELECT displayName FROM library_locations WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Archive", cursor.getString(0))
            }
            db.query("SELECT customPattern, projectName FROM import_presets WHERE name = 'Weddings'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{year}/{project}", cursor.getString(0))
                assertEquals("Smith", cursor.getString(1))
            }
            db.query("SELECT status, doneBytes, finishedAt, activeMillis FROM import_jobs WHERE id = 3").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("COMPLETED", cursor.getString(0))
                assertEquals(100L, cursor.getLong(1))
                assertEquals(5011L, cursor.getLong(2))
                assertEquals(0L, cursor.getLong(3))
            }
            db.query("SELECT state FROM import_job_items WHERE jobId = 3").use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("DONE", cursor.getString(0))
            }
            db.query("SELECT headHash, jobId FROM imported_files").use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("abc123", cursor.getString(0))
                assertEquals(3L, cursor.getLong(1))
            }
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
                assertEquals(3L, cursor.getLong(5))
            }
        }
    }

    /**
     * R8.7 (D16): a catalog from R4's schema (v5) opened by today's migration chain (5 → 6 → 7 → 8)
     * on the device. Rows, edits and pending XMP writes survive; only the GPS-unread marker moves.
     */
    @Test
    fun migrate5To8KeepsTheCatalogAndMarksUnreadGps() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.insert("library_locations", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 7L)
                put("treeUri", "content://tree/library")
                put("displayName", "Archive")
                put("addedAt", 1L)
                put("lastOpenedAt", 2L)
            })
            db.insert("photo_assets", SQLiteDatabase.CONFLICT_ABORT, photoV5("edited", pending = 3, rating = 4))
            db.insert("photo_assets", SQLiteDatabase.CONFLICT_ABORT, photoV5("clean", pending = 0, rating = 2))
        }

        helper.runMigrationsAndValidate(DB_NAME, 8, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT displayName FROM library_locations WHERE id = 7").use {
                assertTrue(it.moveToFirst())
                assertEquals("Archive", it.getString(0))
            }
            db.query(
                "SELECT id, cullStatus, rating, tags, xmpPendingFields, metadataLoaded, gpsInFile FROM photo_assets ORDER BY id",
            ).use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst()) // "clean"
                assertEquals("clean", cursor.getString(0))
                assertEquals(2, cursor.getInt(2))
                assertEquals(0, cursor.getInt(4))
                assertEquals("never had its GPS read, so it's read again", 0, cursor.getInt(5))
                assertTrue(cursor.moveToNext()) // "edited"
                assertEquals("edited", cursor.getString(0))
                assertEquals("PICK", cursor.getString(1))
                assertEquals(4, cursor.getInt(2))
                assertEquals("[\"Wedding\"]", cursor.getString(3))
                assertEquals("pending writes are untouched", 3, cursor.getInt(4))
                assertEquals(0, cursor.getInt(5))
                assertEquals(0, cursor.getInt(6))
            }
        }
    }

    private fun photoV5(id: String, pending: Int, rating: Int) = ContentValues().apply {
        put("id", id)
        put("rootUri", "content://tree/library")
        put("relativeDir", "2026/2026-11-08")
        put("parentDocUri", "content://tree/library/dir")
        put("fileName", "$id.ARW")
        put("fileUri", "content://tree/library/$id")
        put("format", "ARW")
        put("fileSizeBytes", 25_000_000L)
        put("fileLastModified", 5L)
        put("dateTaken", 7L)
        put("metadataLoaded", 1)
        put("cullStatus", "PICK")
        put("rating", rating)
        put("colorLabel", "GREEN")
        put("tags", "[\"Wedding\"]")
        put("width", 0)
        put("height", 0)
        put("orientation", 0)
        put("lastModified", 9L)
        put("pairMode", "RAW_ONLY")
        put("xmpSyncStatus", if (pending == 0) "SYNCED" else "PENDING")
        put("xmpPendingFields", pending)
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
