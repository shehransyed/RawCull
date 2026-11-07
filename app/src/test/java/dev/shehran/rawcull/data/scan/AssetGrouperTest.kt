package dev.shehran.rawcull.data.scan

import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetGrouperTest {

    private fun entry(name: String, dir: String = "DCIM/100MSDCF") = ScannedEntry(
        docId = "$dir/$name",
        docUri = "content://doc/$dir/$name",
        parentDocId = dir,
        parentDocUri = "content://doc/$dir",
        relativeDir = dir,
        name = name,
        mime = null,
        size = 100L,
        lastModified = 1_000L,
    )

    private fun group(vararg names: String) = AssetGrouper.group(names.map { entry(it) })

    @Test
    fun `ARW and JPG pair into one asset with the RAW as primary`() {
        val asset = group("DSC001.ARW", "DSC001.JPG").assets.single()

        assertEquals("DSC001.ARW", asset.primary.name)
        assertEquals(PhotoFormat.ARW, asset.format)
        assertEquals("DSC001.JPG", asset.companion?.name)
        assertEquals(PhotoFormat.JPEG, asset.companionFormat)
        assertEquals(PairMode.RAW_AND_RASTER, asset.pairMode)
    }

    @Test
    fun `JPG alone is a raster-only asset`() {
        val asset = group("DSC001.JPG").assets.single()

        assertEquals(PhotoFormat.JPEG, asset.format)
        assertEquals(PairMode.RASTER_ONLY, asset.pairMode)
        assertNull(asset.companion)
    }

    @Test
    fun `HIF alone is a raster-only asset`() {
        val asset = group("DSC001.HIF").assets.single()

        assertEquals(PhotoFormat.HEIF, asset.format)
        assertEquals(PairMode.RASTER_ONLY, asset.pairMode)
    }

    @Test
    fun `ARW with JPG and HIF pairs the JPG and leaves the HIF standalone`() {
        val assets = group("DSC001.HIF", "DSC001.ARW", "DSC001.JPG").assets

        assertEquals(2, assets.size)
        val raw = assets.single { it.format == PhotoFormat.ARW }
        assertEquals("DSC001.JPG", raw.companion?.name)
        assertEquals(PairMode.RAW_AND_RASTER, raw.pairMode)
        val hif = assets.single { it.format == PhotoFormat.HEIF }
        assertEquals(PairMode.RASTER_ONLY, hif.pairMode)
        assertNull(hif.companion)
    }

    @Test
    fun `HIF pairs with a RAW when there is no JPG`() {
        val asset = group("DSC001.ARW", "DSC001.HIF").assets.single()

        assertEquals(PhotoFormat.HEIF, asset.companionFormat)
    }

    @Test
    fun `JPG and HIF without a RAW stay separate`() {
        val assets = group("DSC001.JPG", "DSC001.HIF").assets

        assertEquals(2, assets.size)
        assertEquals(setOf(PairMode.RASTER_ONLY), assets.map { it.pairMode }.toSet())
    }

    @Test
    fun `names pair case-insensitively`() {
        val asset = group("dsc001.arw", "DSC001.JPG").assets.single()

        assertEquals("dsc001.arw", asset.primary.name)
        assertEquals("DSC001.JPG", asset.companion?.name)
    }

    @Test
    fun `the same stem in two folders gives two assets`() {
        val result = AssetGrouper.group(listOf(entry("DSC001.ARW", "DCIM/100"), entry("DSC001.ARW", "DCIM/101")))

        assertEquals(2, result.assets.size)
        assertNotEquals(result.assets[0].idIn("root"), result.assets[1].idIn("root"))
    }

    @Test
    fun `the stem is the name before the last dot`() {
        val asset = group("trip.v2.ARW", "trip.v2.JPG", "trip.v2.xmp").assets.single()

        assertEquals("trip.v2", asset.stem)
        assertEquals("trip.v2.JPG", asset.companion?.name)
        assertEquals("trip.v2.xmp", asset.adobeXmp?.name)
    }

    @Test
    fun `both sidecar naming styles are recorded and Adobe style wins`() {
        val asset = group("DSC001.ARW", "DSC001.xmp", "DSC001.ARW.xmp").assets.single()

        assertEquals("DSC001.xmp", asset.adobeXmp?.name)
        assertEquals("DSC001.ARW.xmp", asset.darktableXmp?.name)
        assertEquals("DSC001.xmp", asset.sidecar?.name)
    }

    @Test
    fun `darktable-style sidecar is used when it is the only one`() {
        val asset = group("DSC001.ARW", "dsc001.arw.XMP").assets.single()

        assertNull(asset.adobeXmp)
        assertEquals("dsc001.arw.XMP", asset.sidecar?.name)
    }

    @Test
    fun `sidecars with no matching photo are orphans`() {
        val result = group("DSC001.ARW", "DSC999.xmp")

        assertNull(result.assets.single().sidecar)
        assertEquals(listOf("DSC999.xmp"), result.orphanSidecars.map { it.name })
    }

    @Test
    fun `sidecars only match within their own folder`() {
        val result = AssetGrouper.group(listOf(entry("DSC001.ARW", "DCIM/100"), entry("DSC001.xmp", "DCIM/101")))

        assertNull(result.assets.single().sidecar)
        assertEquals(1, result.orphanSidecars.size)
    }

    @Test
    fun `video and thumbnail files are ignored`() {
        val result = group("C0001.MP4", "C0001.THM", "DSC001.ARW", "notes.txt", "README")

        assertEquals(listOf("DSC001.ARW"), result.assets.map { it.primary.name })
    }

    @Test
    fun `files in the system trash are ignored`() {
        val result = group(".trashed-1794074468-DSC001.ARW", ".trashed-1794074468-DSC001.xmp", "DSC002.ARW")

        assertEquals(listOf("DSC002.ARW"), result.assets.map { it.primary.name })
        assertEquals(emptyList<ScannedEntry>(), result.orphanSidecars)
    }

    @Test
    fun `macOS AppleDouble stubs are ignored`() {
        val result = group("._DSC001.ARW", "._DSC001.xmp", "DSC001.ARW")

        assertEquals(listOf("DSC001.ARW"), result.assets.map { it.primary.name })
        assertEquals(emptyList<ScannedEntry>(), result.orphanSidecars)
    }

    @Test
    fun `two RAWs with the same stem become two assets with distinct ids`() {
        val assets = group("DSC001.DNG", "DSC001.ARW", "DSC001.JPG").assets

        assertEquals(2, assets.size)
        val arw = assets.single { it.format == PhotoFormat.ARW }
        val dng = assets.single { it.format == PhotoFormat.DNG }
        assertEquals("the raster attaches to the first by PhotoFormat order", "DSC001.JPG", arw.companion?.name)
        assertEquals(PairMode.RAW_ONLY, dng.pairMode)
        assertNotEquals(arw.idIn("root"), dng.idIn("root"))
    }

    @Test
    fun `a shared Adobe sidecar goes to the first asset only`() {
        val assets = group("DSC001.ARW", "DSC001.DNG", "DSC001.xmp", "DSC001.DNG.xmp").assets

        assertEquals("DSC001.xmp", assets.single { it.format == PhotoFormat.ARW }.sidecar?.name)
        assertEquals("DSC001.DNG.xmp", assets.single { it.format == PhotoFormat.DNG }.sidecar?.name)
    }

    @Test
    fun `a leftover raster has an id distinct from the pair`() {
        val assets = group("DSC001.ARW", "DSC001.JPG", "DSC001.HIF").assets

        assertEquals(2, assets.map { it.idIn("root") }.toSet().size)
    }
}
