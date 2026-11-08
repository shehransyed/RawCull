package dev.shehran.rawcull.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R7 must install over R6 with the tester's library intact (D16). Runs in check.sh, unlike the
 * device-only MigrationTest: Robolectric can't see test assets, so the v6 database is built from
 * the exported 6.json here, and Room's own AutoMigration and schema check open it as v7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration6To7Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile get() = context.getDatabasePath(DB_NAME)

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    /** Creates the database exactly as Room v6 left it, then lets [fill] add rows. */
    private fun createVersion6(fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File(SCHEMA_DIR, "6.json").readText()).getJSONObject("database")
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 6
            fill(db)
        }
    }

    /** Opens with the app's real migrations; Room throws if a migrated table doesn't match v7. */
    private fun openVersion7(check: (SupportSQLiteDatabase) -> Unit) {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(7, db.version)
            check(db)
        } finally {
            database.close()
        }
    }

    /** Every column of a v6 row, with nothing left at its default. */
    private val photo = ContentValues().apply {
        put("id", "asset-1")
        put("rootUri", "content://tree/library")
        put("relativeDir", "2026/2026-05-01")
        put("parentDocUri", "content://tree/library/dir")
        put("fileName", "DSC00001.ARW")
        put("fileUri", "content://tree/library/file")
        put("format", "ARW")
        put("fileSizeBytes", 35_000_000L)
        put("fileLastModified", 5L)
        put("dateTaken", 7L)
        put("metadataLoaded", 1)
        put("cullStatus", "PICK")
        put("rating", 4)
        put("colorLabel", "GREEN")
        put("tags", "[\"Wedding\",\"Smith\"]")
        put("width", 6000)
        put("height", 4000)
        put("cameraMake", "SONY")
        put("cameraModel", "ILCE-7M4")
        put("lensModel", "FE 35mm F1.8")
        put("shutterSpeed", "1/250")
        put("aperture", "f/2.8")
        put("iso", "400")
        put("focalLength", "35mm")
        put("exposureBias", "-0.3")
        put("orientation", 6)
        put("cachedThumbnailPath", "/cache/t.jpg")
        put("lastModified", 9L)
        put("companionFileName", "DSC00001.JPG")
        put("companionFileUri", "content://tree/library/jpg")
        put("companionFileSizeBytes", 9_000_000L)
        put("companionFormat", "JPEG")
        put("xmpFileUri", "content://tree/library/xmp")
        put("pairMode", "RAW_AND_RASTER")
        put("xmpSyncStatus", "PENDING")
        put("xmpLastSyncedMtime", 11L)
        put("xmpError", "provider said no")
        put("darktableXmpUri", "content://tree/library/dtxmp")
        put("xmpPendingFields", 3)
        put("lastImportJobId", 3L)
    }

    @Test
    fun migrate6To7KeepsEveryPhotoFieldAndAddsEmptyCollections() {
        createVersion6 { db ->
            db.insertOrThrow("photo_assets", null, photo)
            db.insertOrThrow(
                "library_locations",
                null,
                ContentValues().apply {
                    put("id", 7L)
                    put("treeUri", "content://tree/library")
                    put("displayName", "Archive")
                    put("addedAt", 1L)
                    put("lastOpenedAt", 2L)
                },
            )
            db.insertOrThrow(
                "import_jobs",
                null,
                ContentValues().apply {
                    put("id", 3L)
                    put("createdAt", 10L)
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
                    put("activeMillis", 4000L)
                },
            )
        }

        openVersion7 { db ->
            db.query("SELECT * FROM photo_assets WHERE id = 'asset-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                for (key in photo.keySet()) {
                    val column = cursor.getColumnIndexOrThrow(key)
                    assertEquals(key, photo.getAsString(key), cursor.getString(column))
                }
                for (gps in listOf("gpsLatitude", "gpsLongitude", "gpsAltitude")) {
                    assertTrue(gps, cursor.isNull(cursor.getColumnIndexOrThrow(gps)))
                }
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("gpsInFile")))
            }
            db.query("SELECT status, activeMillis, collectionId FROM import_jobs WHERE id = 3").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("COMPLETED", cursor.getString(0))
                assertEquals(4000L, cursor.getLong(1))
                assertTrue(cursor.isNull(2))
            }
            db.query("SELECT displayName FROM library_locations WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Archive", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM collections").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM collection_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("PRAGMA foreign_key_list(collection_items)").use { assertEquals(1, it.count) }
        }
    }

    private companion object {
        const val DB_NAME = "migration-6-7-test.db"
        val SCHEMA_DIR = File("schemas/dev.shehran.rawcull.data.local.AppDatabase")
    }
}
