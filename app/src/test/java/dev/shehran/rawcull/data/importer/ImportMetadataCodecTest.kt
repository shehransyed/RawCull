package dev.shehran.rawcull.data.importer

import dev.shehran.rawcull.util.ExtractedRawMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportMetadataCodecTest {
    private val metadata = ExtractedRawMetadata(
        cameraMake = "SONY",
        cameraModel = "ILCE-7M4",
        lensModel = null,
        shutterSpeed = "1/250s",
        aperture = "f/2.8",
        iso = "400",
        focalLength = "50 mm",
        exposureBias = null,
        dateTaken = 1_700_000_000_000L,
        width = 7008,
        height = 4672,
        orientation = 1,
    )

    @Test
    fun `metadata survives a round trip, nulls included`() {
        assertEquals(metadata, ImportMetadataCodec.decode(ImportMetadataCodec.encode(metadata)))
    }

    @Test
    fun `fallback values are not stored as if they had been read`() {
        assertNull(ImportMetadataCodec.encode(metadata.copy(parsed = false)))
    }

    @Test
    fun `nothing or garbage decodes to nothing`() {
        assertNull(ImportMetadataCodec.decode(null))
        assertNull(ImportMetadataCodec.decode("not json"))
    }
}
