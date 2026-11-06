package dev.shehran.rawcull

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.util.xmp.CullState
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpMapper
import dev.shehran.rawcull.util.xmp.XmpPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RawCull", appName)
    }

    @Test
    fun `test raw format detection`() {
        assertEquals(PhotoFormat.ARW, PhotoFormat.fromFileName("DSC04891.ARW"))
        assertEquals(PhotoFormat.ARW, PhotoFormat.fromFileName("photo.arw"))
        assertEquals(PhotoFormat.DNG, PhotoFormat.fromFileName("L1002341.DNG"))
        assertEquals(PhotoFormat.DNG, PhotoFormat.fromFileName("drone.dng"))
        assertEquals(PhotoFormat.HEIF, PhotoFormat.fromFileName("DSC00001.HIF"))
    }

    @Test
    fun `test xmp sidecar generator generates valid ratings and tags`() {
        val photo = PhotoAsset(
            id = "test_1",
            fileName = "DSC04891.ARW",
            fileUri = "file:///dummy.arw",
            rootUri = "file:///dummy",
            format = PhotoFormat.ARW,
            fileSizeBytes = 35_000_000L,
            dateTaken = System.currentTimeMillis(),
            cullStatus = CullStatus.PICK,
            rating = 5,
            colorLabel = ColorLabel.GREEN,
            tags = listOf("Landscape", "Golden Hour")
        )

        val doc = XmpDocument.newEmpty("RawCull test")
        XmpMapper.apply(doc, CullState(photo.rating, photo.cullStatus, photo.colorLabel, photo.tags), XmpPrefs())
        val xmp = doc.toBytes().toString(Charsets.UTF_8)
        assertTrue(xmp.contains("xmp:Rating=\"5\""))
        assertTrue(xmp.contains("xmp:Label=\"Green\""))
        assertTrue(xmp.contains("rawcull:Pick=\"1\""))
        assertTrue(xmp.contains("<rdf:li>Landscape</rdf:li>"))
        assertTrue(xmp.contains("<rdf:li>Golden Hour</rdf:li>"))
    }

    @Test
    fun `test xmp sidecar parser reads ratings picks and tags`() {
        val sampleXmp = """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmp:Rating="4"
                xmp:Label="Red"
                crs:Pick="1">
               <dc:subject>
                <rdf:Bag>
                 <rdf:li>Portrait</rdf:li>
                 <rdf:li>Studio</rdf:li>
                </rdf:Bag>
               </dc:subject>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()

        val parsed = XmpMapper.read(XmpDocument.parse(sampleXmp.toByteArray()).getOrThrow())
        assertEquals(4, parsed.rating)
        assertEquals(CullStatus.PICK, parsed.cullStatus)
        assertEquals(ColorLabel.RED, parsed.colorLabel)
        assertEquals(listOf("Portrait", "Studio"), parsed.tags)
    }

    @Test
    fun `test raw and jpeg pair mode properties`() {
        val pairedItem = PhotoAsset(
            id = "pair_1",
            fileName = "DSC04891.ARW",
            fileUri = "file:///dummy.arw",
            rootUri = "file:///dummy",
            format = PhotoFormat.ARW,
            fileSizeBytes = 35_000_000L,
            dateTaken = System.currentTimeMillis(),
            companionFileName = "DSC04891.JPG",
            companionFileUri = "file:///dummy.jpg",
            companionFileSizeBytes = 8_000_000L,
            companionFormat = PhotoFormat.JPEG,
            pairMode = PairMode.RAW_AND_RASTER
        )

        assertTrue(pairedItem.hasCompanion)
        assertEquals("DSC04891", pairedItem.baseStem)
        assertEquals(PairMode.RAW_AND_RASTER, pairedItem.pairMode)
    }
}
