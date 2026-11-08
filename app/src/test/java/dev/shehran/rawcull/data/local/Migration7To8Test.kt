package dev.shehran.rawcull.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.util.xmp.XmpField
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R8 D5: the data-only 7 → 8 migration marks rows that never had their GPS read as unenriched,
 * and nothing else. Built from the exported 7.json, as in [Migration6To7Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration7To8Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile get() = context.getDatabasePath(DB_NAME)

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    private fun createVersion7(fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File(SCHEMA_DIR, "7.json").readText()).getJSONObject("database")
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
            db.version = 7
            fill(db)
        }
    }

    private fun photo(id: String, latitude: Double? = null, gpsInFile: Boolean = false, pending: Int = 0, root: String = "content://tree/library") =
        ContentValues().apply {
            put("id", id)
            put("rootUri", root)
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
            put("rating", 4)
            put("colorLabel", "GREEN")
            put("tags", "[\"Trip\"]")
            put("width", 6000)
            put("height", 4000)
            put("orientation", 1)
            put("lastModified", 9L)
            put("pairMode", "RAW_ONLY")
            put("xmpSyncStatus", if (pending != 0) "PENDING" else "SYNCED")
            put("xmpPendingFields", pending)
            if (latitude != null) {
                put("gpsLatitude", latitude)
                put("gpsLongitude", 20.0)
            }
            put("gpsInFile", if (gpsInFile) 1 else 0)
        }

    @Test
    fun marksOnlyRowsWhoseLocationWasNeverRead() {
        createVersion7 { db ->
            listOf(
                photo("never-read"),
                photo("has-location", latitude = 10.0),
                photo("camera-cleared", gpsInFile = true),
                photo("location-cleared-by-user", pending = XmpField.GPS.bit),
                photo("rating-pending", pending = XmpField.RATING.bit),
                photo("demo", root = DEMO_ROOT_URI),
            ).forEach { db.insertOrThrow("photo_assets", null, it) }
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(8, db.version)
            val rows = db.query("SELECT id, metadataLoaded, rating, cullStatus, xmpPendingFields, xmpSyncStatus FROM photo_assets ORDER BY id")
                .use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) {
                            put(cursor.getString(0), listOf(cursor.getInt(1), cursor.getInt(2), cursor.getString(3), cursor.getInt(4), cursor.getString(5)))
                        }
                    }
                }
            assertEquals(
                mapOf(
                    "camera-cleared" to listOf(1, 4, "PICK", 0, "SYNCED"),
                    "demo" to listOf(1, 4, "PICK", 0, "SYNCED"),
                    "has-location" to listOf(1, 4, "PICK", 0, "SYNCED"),
                    "location-cleared-by-user" to listOf(1, 4, "PICK", XmpField.GPS.bit, "PENDING"),
                    "never-read" to listOf(0, 4, "PICK", 0, "SYNCED"),
                    "rating-pending" to listOf(0, 4, "PICK", XmpField.RATING.bit, "PENDING"),
                ),
                rows,
            )
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-7-8-test.db"
        val SCHEMA_DIR = File("schemas/dev.shehran.rawcull.data.local.AppDatabase")
    }
}
