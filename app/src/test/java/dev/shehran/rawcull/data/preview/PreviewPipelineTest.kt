package dev.shehran.rawcull.data.preview

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.preview.ArrayByteSource
import dev.shehran.rawcull.preview.JpegBuilder
import dev.shehran.rawcull.preview.SourceRect
import dev.shehran.rawcull.preview.TiffBuilder
import dev.shehran.rawcull.preview.TiffBuilder.Companion.long
import dev.shehran.rawcull.preview.TiffBuilder.Companion.short
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val RAW = "content://raw"
private const val JPEG = "content://jpeg"

/** Marks a decoded bitmap with where it came from: its width is the sample size used. */
private class FakeCodec : PreviewCodec {
    val decodes = AtomicInteger()
    val hardwareRequests = AtomicInteger()
    var failFirst = 0

    override fun decode(jpeg: ByteArray, sampleSize: Int, hardware: Boolean): Bitmap? {
        decodes.incrementAndGet()
        if (hardware) hardwareRequests.incrementAndGet()
        if (failFirst > 0) {
            failFirst--
            return null
        }
        return Bitmap.createBitmap(512 / sampleSize, 341 / sampleSize, Bitmap.Config.ARGB_8888)
    }

    var lastOrientation = 0

    override fun upright(bitmap: Bitmap, orientation: Int, longEdgePx: Int): Bitmap {
        lastOrientation = orientation
        return bitmap
    }

    override fun encode(bitmap: Bitmap): ByteArray = ByteArray(100) { 1 }
}

private class FakeFiles : PreviewFiles {
    val contents = HashMap<String, ByteArray>()
    val opens = HashMap<String, Int>()
    var platformDecodes = 0
    var platformImage: DecodedImage? = null
    var regionFromBytes = 0
    var regionFromUri = 0
    var failing = false

    override fun open(uri: String): OpenPreviewFile {
        if (failing) throw IOException("card removed")
        opens[uri] = (opens[uri] ?: 0) + 1
        return OpenPreviewFile(ArrayByteSource(contents.getValue(uri)), {}, Closeable { })
    }

    override fun decodeWithPlatform(uri: String, maxEdgePx: Int): DecodedImage? {
        platformDecodes++
        return platformImage
    }

    private val region = object : RegionDecoder {
        override fun decode(rect: SourceRect, sampleSize: Int): Bitmap? = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        override fun close() = Unit
    }

    override fun regionDecoder(uri: String): RegionDecoder? = region.also { regionFromUri++ }
    override fun regionDecoder(jpeg: ByteArray): RegionDecoder? = region.also { regionFromBytes++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreviewPipelineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: PreviewCacheDatabase
    private lateinit var store: ThumbnailStore
    private lateinit var repository: DefaultPreviewRepository
    private val files = FakeFiles()
    private val codec = FakeCodec()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val raw = PreviewSource(RAW, PhotoFormat.ARW, null, key("raw"))
    private val pair = PreviewSource(RAW, PhotoFormat.ARW, JPEG, key("pair"), PhotoFormat.JPEG)

    private fun key(name: String) = name.padStart(64, 'a')

    /** A TIFF with one 1600-px-class preview and a tiny thumbnail. */
    private fun arw(orientation: Int = 6): ByteArray {
        val tiff = TiffBuilder()
        val preview = JpegBuilder.jpeg(1616, 1080)
        val small = JpegBuilder.jpeg(160, 120)
        val previewAt = tiff.blob(preview)
        val smallAt = tiff.blob(small)
        val ifd1 = tiff.ifd(listOf(long(0x201, smallAt), long(0x202, small.size.toLong())))
        tiff.first(
            tiff.ifd(
                listOf(short(0x112, orientation), long(0x201, previewAt), long(0x202, preview.size.toLong())),
                next = ifd1,
            ),
        )
        return tiff.bytes()
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PreviewCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = ThumbnailStore(database.dao(), folder.newFolder("previews"), { 1_000_000L })
        repository = newRepository()
        files.contents[RAW] = arw()
    }

    private fun newRepository() = DefaultPreviewRepository(
        files = files,
        codec = codec,
        store = store,
        dao = database.dao(),
        memory = PreviewMemory(1_000_000, 1_000_000, 4),
        scope = scope,
        io = Dispatchers.IO,
        decoding = Dispatchers.Default,
    )

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun PreviewResult.image(): PreviewImage = (this as PreviewResult.Ready).image

    @Test
    fun `a cold thumbnail opens the file once, then RAM, then disk`() = runBlocking {
        val cold = repository.thumbnail(raw, 512).image()
        assertEquals(HitLevel.FILE, cold.timing.hit)
        assertEquals(1, files.opens[RAW])
        assertTrue(store.contains(raw.key))

        assertEquals(HitLevel.RAM, repository.thumbnail(raw, 512).image().timing.hit)
        assertNotNull(repository.peekThumbnail(raw, 512))

        repository = newRepository() // A restart
        assertEquals(HitLevel.DISK, repository.thumbnail(raw, 512).image().timing.hit)
        assertEquals(1, files.opens[RAW])
    }

    @Test
    fun `thumbnails are decoded with the sample size and turned upright once`() = runBlocking {
        val result = repository.thumbnail(raw, 512).image()
        assertEquals(6, codec.lastOrientation)
        assertEquals(1, result.orientation)
        assertEquals(PreviewKind.EMBEDDED_JPEG, result.info?.kind)
    }

    @Test
    fun `smaller buckets come out of the one 512 px thumbnail`() = runBlocking {
        repository.thumbnail(raw, 512)
        val small = repository.thumbnail(raw, 100).image()
        assertEquals(HitLevel.DISK, small.timing.hit)
        assertEquals(128, small.bitmap.width)
        assertEquals(1, files.opens[RAW])
    }

    @Test
    fun `a second look at a file skips the parse and opens it once`() = runBlocking {
        repository.thumbnail(raw, 512)
        val fit = repository.fitPreview(raw, 1600, 1000).image()
        assertEquals(0.0, fit.timing.scanMs, 0.0)
        assertEquals(2, files.opens[RAW]) // One per request, never one per stage
    }

    @Test
    fun `fit previews ask for hardware bitmaps, keep their orientation and know the full source`() = runBlocking {
        val fit = repository.fitPreview(raw, 800, 500).image()
        assertEquals(1, codec.hardwareRequests.get())
        assertEquals(6, fit.orientation)
        assertEquals(PreviewSourceInfo(PreviewKind.EMBEDDED_JPEG, 1616, 1080), fit.full?.info)
        assertNotNull(repository.peekFit(raw, 800, 500))
    }

    @Test
    fun `a pair's thumbnail stays in the RAW, its 100 percent goes to the companion`() = runBlocking {
        val companion = JpegBuilder.withMpf(JpegBuilder.jpeg(1800, 1200), listOf(JpegBuilder.jpeg(1616, 1080)))
        files.contents[JPEG] = companion

        val fit = repository.fitPreview(pair, 1700, 1000).image()
        assertEquals(PreviewKind.COMPANION_JPEG, fit.info?.kind)
        assertEquals(1800, fit.full?.info?.width)

        val thumb = repository.thumbnail(pair, 512).image()
        assertEquals(1616, thumb.info?.width)
    }

    @Test
    fun `an undecodable candidate falls through to the next`() = runBlocking {
        codec.failFirst = 1
        val result = repository.thumbnail(raw, 512).image()
        assertEquals(160, result.info?.width)
    }

    @Test
    fun `an undecodable fit retries in software first`() = runBlocking {
        codec.failFirst = 1
        assertEquals(1616, repository.fitPreview(raw, 800, 500).image().info?.width)
    }

    @Test
    fun `a DNG with no JPEG goes to the platform decoder, labelled`() = runBlocking {
        val source = PreviewSource("content://dng", PhotoFormat.DNG, null, key("dng"))
        files.contents["content://dng"] = TiffBuilder().apply { first(ifd(listOf(short(0x112, 1)))) }.bytes()
        files.platformImage = DecodedImage(Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888), 4000, 3000)

        val fit = repository.fitPreview(source, 800, 500).image()

        assertEquals(PreviewKind.DECODED_RAW, fit.info?.kind)
        assertEquals(4000, fit.info?.width)
        assertEquals(40, fit.full?.info?.width) // Nothing finer exists to zoom into
    }

    @Test
    fun `HEIF is read by the platform decoder and needs no scan candidates`() = runBlocking {
        val source = PreviewSource("content://heif", PhotoFormat.HEIF, null, key("heif"))
        files.contents["content://heif"] = ByteArray(100)
        files.platformImage = DecodedImage(Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888), 4000, 3000)
        assertEquals(PreviewKind.HEIF, repository.thumbnail(source, 512).image().info?.kind)
    }

    @Test
    fun `no candidates and no decoder gives a reason, not a crash`() = runBlocking {
        val source = PreviewSource("content://bad", PhotoFormat.ARW, null, key("bad"))
        files.contents["content://bad"] = ByteArray(100)
        assertEquals(PreviewResult.Unavailable("No preview"), repository.thumbnail(source, 512))
        assertEquals(0, files.platformDecodes)
    }

    @Test
    fun `a file that can't be read isn't remembered as empty`() = runBlocking {
        files.failing = true
        assertEquals(PreviewResult.Unavailable("Can't read this file"), repository.thumbnail(raw, 512))
        assertNull(database.dao().scan(raw.key))

        files.failing = false
        assertEquals(HitLevel.FILE, repository.thumbnail(raw, 512).image().timing.hit)
    }

    @Test
    fun `candidates whose bytes have gone fall through`() = runBlocking {
        repository.thumbnail(raw, 512)
        repository.clearCacheKeepingScan()
        files.contents[RAW] = ByteArray(10) // The scan is stale; the file isn't what it was
        assertEquals(PreviewResult.Unavailable("No preview"), repository.fitPreview(raw, 800, 500))
    }

    private suspend fun DefaultPreviewRepository.clearCacheKeepingScan() {
        val row = database.dao().scan(raw.key)!!
        clearCache()
        database.dao().putScan(row)
    }

    @Test
    fun `simultaneous requests share one decode`() = runBlocking {
        val results = (1..8).map { async(Dispatchers.Default) { repository.fitPreview(raw, 800, 500) } }.awaitAll()
        assertTrue(results.all { it is PreviewResult.Ready })
        assertEquals(1, codec.decodes.get())
    }

    @Test
    fun `an embedded 100 percent source reads its bytes once, a whole-file JPEG stays on the descriptor`() = runBlocking {
        val embedded = repository.regionSource(raw)!!
        assertEquals(1616, embedded.width)
        assertEquals(6, embedded.orientation)
        assertEquals(1, files.regionFromBytes)
        assertNotNull(embedded.decode(SourceRect(0, 0, 10, 10), 1))
        embedded.close()

        files.contents[JPEG] = JpegBuilder.jpeg(1800, 1200)
        repository.regionSource(pair)!!.close()
        assertEquals(1, files.regionFromUri)
        assertEquals(1, files.regionFromBytes)
    }

    @Test
    fun `prefetch warms RAM and cancels what it no longer wants`() = runBlocking {
        repository.prefetch(listOf(PrefetchRequest.Fit(raw, 800, 500)))
        repeat(200) {
            if (repository.peekFit(raw, 800, 500) != null) return@repeat
            kotlinx.coroutines.delay(10)
        }
        assertNotNull(repository.peekFit(raw, 800, 500))
    }

    @Test
    fun `forget makes the next request cold`() = runBlocking {
        repository.thumbnail(raw, 512)
        repository.forget(listOf(raw))
        assertFalse(store.contains(raw.key))
        assertNull(repository.peekThumbnail(raw, 512))
        assertEquals(HitLevel.FILE, repository.thumbnail(raw, 512).image().timing.hit)
    }

    @Test
    fun `the bucket rounds up to 128, 256 or 512`() {
        assertEquals(128, DefaultPreviewRepository.bucket(90))
        assertEquals(256, DefaultPreviewRepository.bucket(129))
        assertEquals(512, DefaultPreviewRepository.bucket(320))
        assertEquals(512, DefaultPreviewRepository.bucket(2000))
    }

    @Test
    fun `scan rows survive the codec, and junk is a miss`() {
        assertNull(ScanCodec.decode("not json"))
        assertNull(ScanCodec.decode("""[{"o":1,"l":2,"w":3,"h":4,"g":"NOT_AN_ORIGIN","f":"PRIMARY","r":1}]"""))
    }
}
