package dev.shehran.rawcull.data.xmp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.AssetIds
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** A documents provider over one folder, with the quirks real providers have switchable. */
class FakeDocuments : ContentProvider() {
    lateinit var dir: File
    var appendOnCreate: String = ""
    var refuseRename = false
    var rejectTruncateMode = false
    var ignoreTruncation = false
    var documentFlags = Document.FLAG_SUPPORTS_WRITE
    var dirFlags = Document.FLAG_DIR_SUPPORTS_CREATE

    override fun onCreate() = true

    private fun fileOf(uri: Uri) = File(dir, DocumentsContract.getDocumentId(uri))

    private fun uriOf(name: String) = DocumentsContract.buildDocumentUri(AUTHORITY, name)

    /** De-duplicates like ExternalStorageProvider: a clash changes the name, it doesn't fail. */
    private fun uniqueFile(name: String): File {
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.')
        return generateSequence(0) { it + 1 }
            .map { File(dir, if (it == 0) name else "$stem ($it).$extension") }
            .first { !it.exists() }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uri = extras!!.getParcelable<Uri>("uri")!!
        val out = Bundle()
        when (method) {
            "android:createDocument" -> {
                val file = uniqueFile(extras.getString(Document.COLUMN_DISPLAY_NAME)!! + appendOnCreate)
                file.createNewFile()
                out.putParcelable("uri", uriOf(file.name))
            }
            "android:renameDocument" -> {
                if (refuseRename) throw IllegalStateException("Rename refused")
                val target = uniqueFile(extras.getString(Document.COLUMN_DISPLAY_NAME)!!)
                fileOf(uri).renameTo(target)
                out.putParcelable("uri", uriOf(target.name))
            }
            "android:deleteDocument" -> fileOf(uri).delete()
            else -> error("Unexpected $method")
        }
        return out
    }

    override fun query(uri: Uri, projection: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor {
        val cursor = MatrixCursor(projection)
        val isDir = DocumentsContract.getDocumentId(uri) == DIR_ID
        val file = if (isDir) dir else fileOf(uri)
        if (!file.exists()) throw IllegalArgumentException("Missing file")
        val values = mapOf(
            Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_SIZE to file.length(),
            Document.COLUMN_FLAGS to if (isDir) dirFlags else documentFlags,
        )
        cursor.addRow(projection!!.map { values[it] })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode == "wt" && rejectTruncateMode) throw IllegalArgumentException("Bad mode")
        val effective = if (ignoreTruncation) mode.replace("t", "") else mode
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.parseMode(effective))
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "dev.shehran.rawcull.test.documents"
        const val DIR_ID = "<dir>"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafSidecarStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var provider: FakeDocuments
    private lateinit var store: SafSidecarStore

    private val parentUri = DocumentsContract.buildDocumentUri(FakeDocuments.AUTHORITY, FakeDocuments.DIR_ID)

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(FakeDocuments::class.java)
            .create(FakeDocuments.AUTHORITY)
            .get()
        provider.dir = folder.root
        store = SafSidecarStore(ApplicationProvider.getApplicationContext<Context>().contentResolver)
    }

    private fun asset() = PhotoAsset(
        id = AssetIds.of("root", "", "DSC001"),
        rootUri = "root",
        parentDocUri = parentUri.toString(),
        fileName = "DSC001.ARW",
        fileUri = "content://x",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 0L,
    )

    private fun uriOf(name: String) = DocumentsContract.buildDocumentUri(FakeDocuments.AUTHORITY, name).toString()

    private fun names() = folder.root.list()!!.sorted()

    @Test
    fun createsTheSidecarUnderItsOwnName() {
        store.create(asset())
        assertEquals(listOf("DSC001.xmp"), names())
    }

    @Test
    fun anAppendedExtensionIsRenamedAway() {
        provider.appendOnCreate = ".bin"
        val uri = store.create(asset())
        assertEquals(listOf("DSC001.xmp"), names())
        assertEquals(uriOf("DSC001.xmp"), uri)
    }

    @Test
    fun aFailedRenameLeavesNoStrayFile() {
        provider.appendOnCreate = ".bin"
        provider.refuseRename = true
        assertThrows(IOException::class.java) { store.create(asset()) }
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun aSidecarThatAppearedSinceTheScanIsNotTouched() {
        val existing = folder.newFile("DSC001.xmp").apply { writeText("from lightroom") }
        assertThrows(IOException::class.java) { store.create(asset()) }
        assertEquals(listOf("DSC001.xmp"), names())
        assertEquals("from lightroom", existing.readText())
    }

    @Test
    fun aFolderThatCantCreateIsReadOnly() {
        provider.dirFlags = 0
        assertThrows(SidecarReadOnlyException::class.java) { store.create(asset()) }
    }

    @Test
    fun readReturnsBytesAndModificationTime() {
        val file = folder.newFile("a.xmp").apply { writeText("hello") }
        val content = store.read(uriOf("a.xmp"))!!
        assertEquals("hello", content.bytes.toString(Charsets.UTF_8))
        assertEquals(file.lastModified(), content.lastModified)
    }

    @Test
    fun aMissingDocumentReadsAsNull() {
        assertNull(store.read(uriOf("gone.xmp")))
    }

    @Test
    fun writeTruncatesAndReturnsTheNewModificationTime() {
        val file = folder.newFile("a.xmp").apply { writeText("a much longer old content") }
        val mtime = store.write(uriOf("a.xmp"), "new".toByteArray())
        assertEquals("new", file.readText())
        assertEquals(file.lastModified(), mtime)
    }

    @Test
    fun withoutTruncationTheOldTailIsOverwritten() {
        val file = folder.newFile("a.xmp").apply { writeText("<old>longer old content</old>") }
        provider.rejectTruncateMode = true
        provider.ignoreTruncation = true
        store.write(uriOf("a.xmp"), "<new/>".toByteArray())

        assertEquals("<new/>", file.readText().trimEnd())
        assertEquals("<old>longer old content</old>".length.toLong(), file.length())
    }

    @Test
    fun aDocumentThatCantBeWrittenIsReadOnlyAndUntouched() {
        val file = folder.newFile("a.xmp").apply { writeText("kept") }
        provider.documentFlags = 0
        assertThrows(SidecarReadOnlyException::class.java) { store.write(uriOf("a.xmp"), "new".toByteArray()) }
        assertEquals("kept", file.readText())
    }

    @Test
    fun backupRenamesAndKeepsTheContent() {
        folder.newFile("a.xmp").apply { writeText("broken") }
        store.backup(uriOf("a.xmp"))
        assertFalse(File(folder.root, "a.xmp").exists())
        assertTrue(File(folder.root, "a.xmp.bak").readText() == "broken")
    }
}
