package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ColorLabel
import com.example.data.model.CullStatus
import com.example.data.model.PairMode
import com.example.data.model.RawFormat
import com.example.data.model.RawPhotoItem
import com.example.util.XmpSidecarGenerator
import com.example.util.XmpSidecarManager
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
        assertEquals(RawFormat.ARW, RawFormat.fromFileName("DSC04891.ARW"))
        assertEquals(RawFormat.ARW, RawFormat.fromFileName("photo.arw"))
        assertEquals(RawFormat.DNG, RawFormat.fromFileName("L1002341.DNG"))
        assertEquals(RawFormat.DNG, RawFormat.fromFileName("drone.dng"))
    }

    @Test
    fun `test xmp sidecar generator generates valid ratings and tags`() {
        val photo = RawPhotoItem(
            id = "test_1",
            fileName = "DSC04891.ARW",
            fileUri = "file:///dummy.arw",
            folderUri = "file:///dummy",
            format = RawFormat.ARW,
            fileSizeBytes = 35_000_000L,
            dateTaken = System.currentTimeMillis(),
            cullStatus = CullStatus.PICK,
            rating = 5,
            colorLabel = ColorLabel.GREEN,
            tags = listOf("Landscape", "Golden Hour")
        )

        val xmp = XmpSidecarGenerator.generateXmp(photo)
        assertTrue(xmp.contains("xmp:Rating=\"5\""))
        assertTrue(xmp.contains("xmp:Label=\"Green\""))
        assertTrue(xmp.contains("crs:Pick=\"1\""))
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

        val parsed = XmpSidecarManager.parseXmpString(sampleXmp)
        assertEquals(4, parsed.rating)
        assertEquals(CullStatus.PICK, parsed.cullStatus)
        assertEquals(ColorLabel.RED, parsed.colorLabel)
        assertEquals(listOf("Portrait", "Studio"), parsed.tags)
    }

    @Test
    fun `test raw and jpeg pair mode properties`() {
        val pairedItem = RawPhotoItem(
            id = "pair_1",
            fileName = "DSC04891.ARW",
            fileUri = "file:///dummy.arw",
            folderUri = "file:///dummy",
            format = RawFormat.ARW,
            fileSizeBytes = 35_000_000L,
            dateTaken = System.currentTimeMillis(),
            jpegFileName = "DSC04891.JPG",
            jpegFileUri = "file:///dummy.jpg",
            jpegFileSizeBytes = 8_000_000L,
            pairMode = PairMode.RAW_AND_JPEG
        )

        assertTrue(pairedItem.hasCompanionJpeg)
        assertEquals("DSC04891", pairedItem.baseStem)
        assertEquals(PairMode.RAW_AND_JPEG, pairedItem.pairMode)
    }
}
