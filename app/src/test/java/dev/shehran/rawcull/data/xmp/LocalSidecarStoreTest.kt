package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.AssetIds
import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalSidecarStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store = LocalSidecarStore()

    private fun asset() = PhotoAsset(
        id = AssetIds.of(DEMO_ROOT_URI, "", "DSC001"),
        rootUri = DEMO_ROOT_URI,
        parentDocUri = folder.root.toURI().toString(),
        fileName = "DSC001.ARW",
        fileUri = "file:///DSC001.ARW",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 0L,
    )

    @Test
    fun createsNextToThePhotoThenReadsWhatWasWritten() {
        val uri = store.create(asset())
        val mtime = store.write(uri, "<x/>".toByteArray())

        assertTrue(File(folder.root, "DSC001.xmp").exists())
        val content = store.read(uri)!!
        assertArrayEquals("<x/>".toByteArray(), content.bytes)
        assertEquals(mtime, content.lastModified)
    }

    @Test
    fun aMissingFileReadsAsNull() {
        assertNull(store.read(File(folder.root, "gone.xmp").toURI().toString()))
    }

    @Test
    fun backupsNeverReplaceEachOther() {
        val file = folder.newFile("DSC001.xmp").apply { writeText("first") }
        store.backup(file.toURI().toString())
        file.writeText("second")
        store.backup(file.toURI().toString())

        assertEquals("first", File(folder.root, "DSC001.xmp.bak").readText())
        assertEquals("second", File(folder.root, "DSC001.xmp.1.bak").readText())
        assertTrue(!file.exists())
    }

    @Test
    fun storesAreChosenByRootScheme() {
        val saf = LocalSidecarStore()
        val stores = SidecarStores(saf = saf, local = store)
        assertSame(store, stores.forRoot(DEMO_ROOT_URI))
        assertSame(saf, stores.forRoot("content://com.android.externalstorage.documents/tree/1234%3ADCIM"))
    }
}
