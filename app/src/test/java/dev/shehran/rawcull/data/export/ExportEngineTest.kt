package dev.shehran.rawcull.data.export

import dev.shehran.rawcull.data.importer.FakeDocumentIo
import dev.shehran.rawcull.data.model.AssetIds
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.importer.DocumentIo
import dev.shehran.rawcull.library.ExportContent
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpProps
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class ExportEngineTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val io = FakeDocumentIo()
    private val library = io.root("library")
    private val destination = "content://export"
    private val folder = io.root(destination)
    private val utc = TimeZone.getTimeZone("UTC")
    private val start = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC

    /** Appends the time, so the test can see which file it went into. */
    private val jpegTimes = JpegTimeWriter { file, time -> file.appendText("|$time") }

    private fun engine(documents: DocumentIo = io) =
        ExportEngine(documents, jpegTimes, temp.newFolder(), "RawCull test", zone = { utc }, now = { start + 1_000_000 })

    private val sidecarXml = (
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" " +
            "xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" " +
            "xmp:Rating=\"3\" crs:Exposure2012=\"+0.50\" exif:DateTimeOriginal=\"2020-01-01T10:00:00+02:00\"/>" +
            "</rdf:RDF></x:xmpmeta>"
        ).toByteArray()

    private fun raw(stem: String, date: Long = start, jpeg: Boolean = false, sidecar: Boolean = false): PhotoAsset {
        val rawUri = io.addFile(library, "$stem.ARW", "raw-$stem".toByteArray())
        val jpegUri = if (jpeg) io.addFile(library, "$stem.JPG", "jpeg-$stem".toByteArray()) else null
        val xmpUri = if (sidecar) io.addFile(library, "$stem.xmp", sidecarXml) else null
        return PhotoAsset(
            id = AssetIds.of("library", "", stem),
            rootUri = "library",
            fileName = "$stem.ARW",
            fileUri = rawUri,
            format = PhotoFormat.ARW,
            fileSizeBytes = 0,
            dateTaken = date,
            companionFileName = if (jpeg) "$stem.JPG" else null,
            companionFileUri = jpegUri,
            companionFormat = if (jpeg) PhotoFormat.JPEG else null,
            xmpFileUri = xmpUri,
        )
    }

    private fun run(photos: List<PhotoAsset>, content: ExportContent = ExportContent.ORIGINALS, keepOrder: Boolean = true) =
        runBlocking { engine().run(destination, photos, ExportOptions(content, keepOrder)) }

    private fun exported() = io.files(folder)

    private fun libraryBytes() = io.files(library).mapValues { it.value.toList() }

    @Test
    fun `files land in order with shared numbers`() {
        val summary = run(listOf(raw("B", jpeg = true), raw("A")), keepOrder = false)

        assertEquals(ExportSummary(2, emptyList(), emptyList(), cancelled = false), summary)
        assertEquals(setOf("001_B.ARW", "001_B.JPG", "002_A.ARW"), exported().keys)
        assertArrayEquals("raw-B".toByteArray(), exported().getValue("001_B.ARW"))
    }

    @Test
    fun `nothing in the folder is overwritten`() {
        io.addFile(folder, "001_A.ARW", "someone else's".toByteArray())
        io.addFile(folder, "002_b.xmp", "theirs".toByteArray()) // Names compare without case

        run(listOf(raw("A", sidecar = true), raw("B")))

        val files = exported()
        assertArrayEquals("someone else's".toByteArray(), files.getValue("001_A.ARW"))
        assertArrayEquals("theirs".toByteArray(), files.getValue("002_b.xmp"))
        assertTrue(files.keys.containsAll(listOf("001_A-1.ARW", "001_A-1.xmp", "002_B-1.ARW", "002_B-1.xmp")))
    }

    @Test
    fun `the sidecar copy gets the new times and keeps everything else`() {
        run(listOf(raw("A"), raw("B", sidecar = true)))

        val copy = XmpDocument.parse(exported().getValue("002_B.xmp")).getOrThrow()
        assertEquals("2023-11-14T22:13:30+02:00", copy.valueOf(XmpProps.EXIF_DATE_TIME_ORIGINAL)) // Offset kept
        assertEquals("2023-11-14T22:13:30", copy.valueOf(XmpProps.XMP_CREATE_DATE))
        assertEquals("2023-11-14T22:13:30", copy.valueOf(XmpProps.PHOTOSHOP_DATE_CREATED))
        assertEquals(3, copy.rating)
        assertTrue(String(exported().getValue("002_B.xmp")).contains("Exposure2012=\"+0.50\""))
        // The RAW without a sidecar got a new one with its time.
        val created = XmpDocument.parse(exported().getValue("001_A.xmp")).getOrThrow()
        assertEquals("2023-11-14T22:13:20", created.valueOf(XmpProps.EXIF_DATE_TIME_ORIGINAL))
        // The library's sidecar is as it was.
        assertArrayEquals(sidecarXml, io.files(library).getValue("B.xmp"))
    }

    @Test
    fun `the JPEG copy gets the new times and the original's bytes don't change`() {
        val photos = listOf(raw("A", jpeg = true), raw("B", jpeg = true))
        val before = libraryBytes()

        run(photos, ExportContent.JPEGS_ONLY)

        assertEquals("jpeg-A|2023:11:14 22:13:20", String(exported().getValue("001_A.JPG")))
        assertEquals("jpeg-B|2023:11:14 22:13:30", String(exported().getValue("002_B.JPG")))
        assertEquals(before, libraryBytes())
    }

    @Test
    fun `without keeping the order every copy is byte for byte`() {
        run(listOf(raw("A", jpeg = true, sidecar = true)), keepOrder = false)

        assertEquals("jpeg-A", String(exported().getValue("001_A.JPG")))
        assertArrayEquals(sidecarXml, exported().getValue("001_A.xmp"))
    }

    @Test
    fun `JPEGs only skips photos without one`() {
        val summary = run(listOf(raw("A"), raw("B", jpeg = true)), ExportContent.JPEGS_ONLY)

        assertEquals(listOf("A.ARW"), summary.skipped)
        assertEquals(1, summary.exported)
        assertEquals(setOf("001_B.JPG"), exported().keys)
    }

    @Test
    fun `a copy that comes out the wrong size is removed and reported`() {
        val photos = listOf(raw("A"), raw("B"))
        val short = object : DocumentIo by io {
            override fun info(uri: String) = io.info(uri)?.let { if (it.name == "001_A.ARW") it.copy(sizeBytes = it.sizeBytes - 1) else it }
        }

        val summary = runBlocking { engine(short).run(destination, photos, ExportOptions(ExportContent.ORIGINALS, keepOrder = false)) }

        assertEquals(listOf("001_A.ARW"), summary.failed)
        assertEquals(1, summary.exported)
        assertEquals(setOf("002_B.ARW"), exported().keys)
    }

    @Test
    fun `a name the provider changes is removed and reported`() {
        io.forceRename += "001_A.ARW"

        val summary = run(listOf(raw("A")), keepOrder = false)

        assertEquals(listOf("001_A.ARW"), summary.failed)
        assertTrue(exported().isEmpty())
    }

    @Test
    fun `cancelling stops between photos and keeps what was written`() {
        val photos = (1..5).map { raw("P$it") }
        var summary: ExportSummary? = null
        runBlocking {
            lateinit var job: Job
            job = launch(start = CoroutineStart.LAZY) {
                summary = engine().run(destination, photos, ExportOptions(ExportContent.ORIGINALS, keepOrder = false)) { done, _, _ ->
                    if (done == 2) job.cancel() // The third photo is starting
                }
            }
            job.join()
        }

        val result = checkNotNull(summary)
        assertTrue(result.cancelled)
        assertEquals(3, result.exported)
        assertEquals(setOf("001_P1.ARW", "002_P2.ARW", "003_P3.ARW"), exported().keys)
    }

    @Test
    fun `the library's files are only read`() {
        val photos = listOf(raw("A", jpeg = true, sidecar = true), raw("B"))
        val before = libraryBytes()

        run(photos)
        run(photos, ExportContent.JPEGS_ONLY)

        assertEquals(before, libraryBytes())
        assertFalse(io.deleted.any { uri -> uri in io.files(library).keys })
        assertEquals(before.keys, io.files(library).keys)
    }
}
